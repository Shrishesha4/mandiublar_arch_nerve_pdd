"""
Dental Implant Planning – FastAPI Backend
==========================================
Endpoints:
  POST /analyze-jaw   – accepts a DICOM (.dcm) file, returns analysis JSON
  POST /measure       – accepts coordinates + cached volume ID, returns bone metrics
  GET  /health        – health check
"""

from __future__ import annotations

import io
import uuid
import logging
import traceback
from typing import Optional
import numpy as np

from fastapi import FastAPI, UploadFile, File, HTTPException, Query, Depends, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from PIL import Image
import pydicom
from pydicom import config as pydicom_config
from pydantic import BaseModel

from auth_db import AuthDatabase
from dicom_processor import (
    load_dicom_volume,
    load_cbct_zip,
    build_cbct_panoramic_proxy,
    load_image_projection,
    generate_opg,
    get_last_cpr_arch_points,
    detect_mandibular_canal_path_2d,
    calculate_bone_metrics,
    build_planning_overlay,
    select_default_measurement_site,
)
from segmentation_model import UNetSegmentor, extract_nerve_path_2d

# ---------- Logging ----------
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("implant-api")
logger.info("Available pydicom pixel handlers: %s", pydicom_config.pixel_data_handlers)

# ---------- App ----------
app = FastAPI(
    title="Dental Implant Planning API",
    version="1.0.0",
    description="AI-assisted CBCT analysis for dental implant planning.",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# ---------- In-memory cache (demo) ----------
# In production, use Redis or a proper cache / object store.
_volume_cache: dict[str, dict] = {}

# ---------- Model ----------
segmentor = UNetSegmentor()
auth_db = AuthDatabase()
auth_scheme = HTTPBearer(auto_error=False)


# ---------- Response schemas ----------
class NervePoint(BaseModel):
    x: int
    y: int


class OverlayLine(BaseModel):
    start: NervePoint
    end: NervePoint


class PlanningOverlay(BaseModel):
    outer_contour: list[NervePoint]
    inner_contour: list[NervePoint]
    base_guide: list[NervePoint]
    width_indicator: OverlayLine | None = None
    sector_lines: list[OverlayLine] = []


class BoneMetrics(BaseModel):
    width_mm: float
    height_mm: float
    safe_height_mm: float
    safety_margin_mm: float
    density_estimate_hu: float
    measurement_location: dict
    safety_status: str
    safety_reason: str


class AnalysisResponse(BaseModel):
    session_id: str
    workflow: str = "cbct_implant"
    patient_name: str
    opg_image_base64: str
    nerve_path: list[NervePoint]
    arch_path: list[NervePoint]
    planning_overlay: PlanningOverlay
    bone_metrics: BoneMetrics
    scan_region: str = "unknown"
    ian_applicable: bool = False
    ian_detected: bool = False
    ian_status_message: str = ""
    safe_zone_path: list[NervePoint] = []
    recommendation_line: str = ""
    metadata: dict


class MeasureRequest(BaseModel):
    session_id: str
    x: int
    y: int


class MeasureResponse(BaseModel):
    bone_metrics: BoneMetrics
    planning_overlay: PlanningOverlay
    scan_region: str = "unknown"
    ian_applicable: bool = False
    ian_detected: bool = False
    ian_status_message: str = ""
    safe_zone_path: list[NervePoint] = []
    recommendation_line: str = ""


class AuthRequest(BaseModel):
    email: str
    password: str


class AuthUser(BaseModel):
    id: int
    email: str


class AuthResponse(BaseModel):
    token: str
    user: AuthUser


# ---------- Endpoints ----------

def create_default_masks(volume):
    n_slices, height, width = volume.shape
    _ = (n_slices, height, width)

    threshold = np.percentile(volume, 70)
    bone_mask = volume > threshold
    nerve_mask = np.zeros_like(volume, dtype=bool)
    return bone_mask.astype(bool), nerve_mask.astype(bool)


def _masks_match_volume(volume, bone_mask, nerve_mask) -> bool:
    return (
        bone_mask is not None
        and nerve_mask is not None
        and bone_mask.shape == volume.shape
        and nerve_mask.shape == volume.shape
    )


def _classify_scan_region(arch_path: list[dict], image_rows: int) -> str:
    if not arch_path:
        return "unknown"
    ys = [float(p.get("y", 0.0)) for p in arch_path]
    if not ys:
        return "unknown"
    median_y = float(np.median(np.asarray(ys, dtype=np.float64)))
    return "mandible" if median_y >= (float(image_rows) * 0.5) else "maxilla"


def _is_ian_detected(scan_region: str, nerve_path: list[dict]) -> bool:
    if scan_region != "mandible":
        return False
    return len(nerve_path) >= 8


def _build_safe_zone_path(
    nerve_path: list[dict],
    pixel_spacing: list[float],
    image_rows: int,
    safety_margin_mm: float,
) -> list[dict]:
    if not nerve_path:
        return []
    row_spacing = float(pixel_spacing[0]) if pixel_spacing else 1.0
    if not np.isfinite(row_spacing) or row_spacing <= 0:
        row_spacing = 1.0
    shift_px = max(1, int(round(safety_margin_mm / row_spacing)))
    safe_path: list[dict] = []
    for pt in nerve_path:
        y = int(np.clip(int(pt.get("y", 0)) - shift_px, 0, image_rows - 1))
        safe_path.append({"x": int(pt.get("x", 0)), "y": y})
    return safe_path


def _build_recommendation_line(scan_region: str, ian_detected: bool, safe_height_mm: float) -> str:
    if scan_region == "mandible" and ian_detected:
        return (
            "Recommended implant placement region: above IAN with safe height "
            f"of {safe_height_mm:.2f} mm."
        )
    return "IAN not applicable / not detected - manual clinical verification required."


def _build_ian_status_message(scan_region: str, ian_detected: bool) -> str:
    if scan_region == "mandible" and ian_detected:
        return "IAN detected. Safe implant zone highlighted above the nerve."
    return "IAN not applicable / not detected - manual clinical verification required."

def detect_input_type(upload_bytes: bytes, filename: Optional[str]) -> Optional[str]:
    if filename and filename.lower().endswith(".zip"):
        return "zip_cbct"
    if len(upload_bytes) >= 2 and upload_bytes[:2] == b"PK":
        return "zip_cbct"

    try:
        pydicom.dcmread(io.BytesIO(upload_bytes), stop_before_pixels=True)
        return "dicom_single"
    except Exception:
        pass

    try:
        with Image.open(io.BytesIO(upload_bytes)) as img:
            img.verify()
        return "image_projection"
    except Exception:
        return None


def _peek_upload_header(upload_file: UploadFile, max_bytes: int = 4096) -> bytes:
    upload_file.file.seek(0)
    header = upload_file.file.read(max_bytes)
    upload_file.file.seek(0)
    return header


def detect_upload_type(upload_file: UploadFile) -> Optional[str]:
    header = _peek_upload_header(upload_file)
    if upload_file.filename and upload_file.filename.lower().endswith(".zip"):
        return "zip_cbct"
    if len(header) >= 2 and header[:2] == b"PK":
        return "zip_cbct"

    try:
        upload_file.file.seek(0)
        pydicom.dcmread(upload_file.file, stop_before_pixels=True)
        return "dicom_single"
    except Exception:
        pass
    finally:
        upload_file.file.seek(0)

    try:
        with Image.open(upload_file.file) as img:
            img.verify()
        return "image_projection"
    except Exception:
        return None
    finally:
        upload_file.file.seek(0)


def _load_volume_for_workflow(dicom_bytes: bytes, workflow: str, file_name: Optional[str]) -> tuple:
    input_type = detect_input_type(dicom_bytes, file_name)

    if workflow == "cbct_implant":
        if input_type == "zip_cbct":
            return load_cbct_zip(dicom_bytes)
        if input_type == "dicom_single":
            return load_dicom_volume(dicom_bytes)
        raise HTTPException(
            status_code=400,
            detail="CBCT input must be a DICOM (.dcm) file or a ZIP archive of DICOM slices.",
        )

    if workflow == "panoramic_mandibular_canal":
        if input_type == "dicom_single":
            return load_dicom_volume(dicom_bytes)
        if input_type == "image_projection":
            return load_image_projection(dicom_bytes)
        raise HTTPException(
            status_code=400,
            detail="Panoramic input must be a DICOM (.dcm), JPEG, or PNG file.",
        )

    raise HTTPException(status_code=400, detail=f"Unsupported workflow: {workflow}")


def _load_volume_for_upload(upload_file: UploadFile, workflow: str) -> tuple:
    input_type = detect_upload_type(upload_file)

    if workflow == "cbct_implant":
        if input_type == "zip_cbct":
            try:
                logger.info("Detected ZIP CBCT upload")
                return load_cbct_zip(upload_file.file)
            except Exception as exc:
                raise HTTPException(status_code=400, detail=f"Invalid CBCT dataset: {exc}") from exc
        if input_type == "dicom_single":
            dicom_bytes = upload_file.file.read()
            upload_file.file.seek(0)
            return load_dicom_volume(dicom_bytes)
        raise HTTPException(
            status_code=400,
            detail="CBCT input must be a DICOM (.dcm) file or a ZIP archive of DICOM slices.",
        )

    if workflow == "panoramic_mandibular_canal":
        if input_type == "dicom_single":
            dicom_bytes = upload_file.file.read()
            upload_file.file.seek(0)
            return load_dicom_volume(dicom_bytes)
        if input_type == "image_projection":
            image_bytes = upload_file.file.read()
            upload_file.file.seek(0)
            return load_image_projection(image_bytes)
        raise HTTPException(
            status_code=400,
            detail="Panoramic input must be a DICOM (.dcm), JPEG, or PNG file.",
        )

    raise HTTPException(status_code=400, detail=f"Unsupported workflow: {workflow}")



@app.get("/health")
async def health():
    return {"status": "ok"}


def require_user(
    credentials: HTTPAuthorizationCredentials | None = Depends(auth_scheme),
) -> dict:
    if credentials is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Missing authorization token.",
        )

    user = auth_db.get_user_by_token(credentials.credentials)
    if user is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or expired authorization token.",
        )
    return user


@app.post("/auth/signup", response_model=AuthResponse)
async def signup(req: AuthRequest):
    try:
        user = auth_db.create_user(req.email, req.password)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    token = auth_db.create_session(user_id=user["id"])
    return AuthResponse(token=token, user=AuthUser(**user))


@app.post("/auth/login", response_model=AuthResponse)
async def login(req: AuthRequest):
    user = auth_db.authenticate(req.email, req.password)
    if user is None:
        raise HTTPException(status_code=401, detail="Invalid email or password.")

    token = auth_db.create_session(user_id=user["id"])
    return AuthResponse(token=token, user=AuthUser(**user))


def _analyze_dicom_bytes(
    dicom_bytes: bytes,
    tooth_x: Optional[int],
    tooth_y: Optional[int],
    workflow: str,
    file_name: Optional[str] = None,
) -> AnalysisResponse:
    if len(dicom_bytes) == 0:
        raise HTTPException(status_code=400, detail="Empty file.")

    try:
        volume, metadata = _load_volume_for_workflow(dicom_bytes, workflow, file_name)
    except HTTPException:
        raise
    except Exception as e:
        logger.exception("Failed to decode input")
        raise HTTPException(status_code=422, detail=f"Input decode error: {e}")

    return _analyze_loaded_volume(
        volume=volume,
        metadata=metadata,
        tooth_x=tooth_x,
        tooth_y=tooth_y,
        workflow=workflow,
    )


def _analyze_loaded_volume(
    volume,
    metadata,
    tooth_x: Optional[int],
    tooth_y: Optional[int],
    workflow: str,
) -> AnalysisResponse:
    dataset_type = "volumetric_cbct" if int(volume.shape[0]) > 1 else "2d_radiograph"
    analysis_volume = volume
    analysis_metadata = metadata
    cbct_ridge_path: list[dict] = []

    if workflow == "cbct_implant" and dataset_type == "volumetric_cbct":
        analysis_volume, analysis_metadata, cbct_bone_2d, cbct_ridge_path = build_cbct_panoramic_proxy(
            volume,
            metadata,
        )
        bone_mask = cbct_bone_2d[np.newaxis, :, :].astype(bool)
        nerve_mask = np.zeros_like(bone_mask, dtype=bool)
        if cbct_ridge_path:
            mid_pt = cbct_ridge_path[len(cbct_ridge_path) // 2]
            mandible_center = (int(mid_pt["y"]), int(mid_pt["x"]))
        else:
            mandible_center = None
    elif workflow == "cbct_implant":
        try:
            seg_result = segmentor.predict(analysis_volume)
            bone_mask = seg_result.get("bone_mask")
            nerve_mask = seg_result.get("nerve_mask")
            mandible_center = seg_result.get("mandible_center", None)
        except Exception:
            bone_mask = None
            nerve_mask = None
            mandible_center = None
    else:
        seg_result = segmentor.predict(analysis_volume)
        bone_mask = seg_result["bone_mask"]
        nerve_mask = seg_result["nerve_mask"]
        mandible_center = seg_result.get("mandible_center", None)

    logger.info(
        "Volume loaded: shape=%s, spacing=%s, patient=%s",
        analysis_volume.shape,
        analysis_metadata["pixel_spacing"],
        analysis_metadata["patient_name"],
    )

    try:
        opg_b64 = generate_opg(analysis_volume, analysis_metadata)
    except Exception:
        logger.exception("OPG generation failed")
        opg_b64 = ""

    if workflow == "cbct_implant":
        bone_mask = np.asarray(bone_mask, dtype=bool) if bone_mask is not None else None
        nerve_mask = np.asarray(nerve_mask, dtype=bool) if nerve_mask is not None else None

        if not _masks_match_volume(analysis_volume, bone_mask, nerve_mask):
            bone_mask, nerve_mask = create_default_masks(analysis_volume)

        print("CBCT volume shape:", analysis_volume.shape)
        print("Pixel spacing:", analysis_metadata.get("pixel_spacing"))
        print("Bone mask shape:", bone_mask.shape)
        print("Nerve mask shape:", nerve_mask.shape)

        if bone_mask.shape != analysis_volume.shape or nerve_mask.shape != analysis_volume.shape:
            raise ValueError("Mask shape mismatch")

        print("Bone mask voxels:", int(bone_mask.sum()))
        if int(bone_mask.sum()) < 1000:
            raise ValueError("Bone mask detection failed")

    tooth_coords = None
    if tooth_x is not None and tooth_y is not None:
        tooth_coords = {"x": tooth_x, "y": tooth_y}
    else:
        auto_site = select_default_measurement_site(analysis_volume, bone_mask, nerve_mask)
        if auto_site is not None:
            tooth_coords = auto_site
        elif mandible_center is not None:
            tooth_coords = {"x": mandible_center[1], "y": mandible_center[0]}

    if workflow == "cbct_implant":
        bone_metrics = calculate_bone_metrics(
            analysis_volume, bone_mask, nerve_mask, analysis_metadata, tooth_coords
        )
        print("Analysis complete")
        print("Bone width:", bone_metrics.get("width_mm"))
        print("Bone height:", bone_metrics.get("height_mm"))
    else:
        bone_metrics = calculate_bone_metrics(
            analysis_volume, bone_mask, nerve_mask, analysis_metadata, tooth_coords
        )
    if workflow == "panoramic_mandibular_canal":
        nerve_path = detect_mandibular_canal_path_2d(analysis_volume, analysis_metadata)
    else:
        nerve_path = extract_nerve_path_2d(
            nerve_mask,
            bone_mask=bone_mask,
            preferred_x=bone_metrics["measurement_location"]["x"],
        )
    planning_overlay = build_planning_overlay(analysis_volume, bone_mask, bone_metrics)

    # For CBCT, prefer ridge extracted from panoramic proxy reconstruction.
    if workflow == "cbct_implant" and cbct_ridge_path:
        arch_pts = cbct_ridge_path
    elif workflow == "cbct_implant" and int(analysis_volume.shape[0]) > 1:
        cpr_arch = get_last_cpr_arch_points()
        arch_pts = cpr_arch if cpr_arch else planning_overlay["outer_contour"]
    else:
        arch_pts = planning_overlay["outer_contour"]

    scan_region = _classify_scan_region(arch_pts, int(analysis_metadata.get("rows", volume.shape[-2])))
    ian_detected = _is_ian_detected(scan_region, nerve_path)
    ian_applicable = scan_region == "mandible"
    ian_status_message = _build_ian_status_message(scan_region, ian_detected)
    safe_zone_path = (
        _build_safe_zone_path(
            nerve_path=nerve_path,
            pixel_spacing=list(analysis_metadata.get("pixel_spacing", [1.0, 1.0])),
            image_rows=int(analysis_metadata.get("rows", volume.shape[-2])),
            safety_margin_mm=float(bone_metrics.get("safety_margin_mm", 2.0)),
        )
        if ian_detected
        else []
    )
    recommendation_line = _build_recommendation_line(
        scan_region=scan_region,
        ian_detected=ian_detected,
        safe_height_mm=float(bone_metrics.get("safe_height_mm", 0.0)),
    )

    session_id = str(uuid.uuid4())
    _volume_cache[session_id] = {
        "volume": analysis_volume,
        "bone_mask": bone_mask,
        "nerve_mask": nerve_mask,
        "metadata": analysis_metadata,
        "workflow": workflow,
        "scan_region": scan_region,
        "ian_applicable": ian_applicable,
        "ian_detected": ian_detected,
        "ian_status_message": ian_status_message,
        "safe_zone_path": safe_zone_path,
    }
    if len(_volume_cache) > 5:
        oldest = next(iter(_volume_cache))
        del _volume_cache[oldest]

    return AnalysisResponse(
        session_id=session_id,
        workflow=workflow,
        patient_name=metadata["patient_name"],
        opg_image_base64=opg_b64,
        nerve_path=[NervePoint(**p) for p in nerve_path],
        arch_path=[NervePoint(**p) for p in arch_pts],
        planning_overlay=PlanningOverlay(
            outer_contour=[NervePoint(**p) for p in planning_overlay["outer_contour"]],
            inner_contour=[NervePoint(**p) for p in planning_overlay["inner_contour"]],
            base_guide=[NervePoint(**p) for p in planning_overlay["base_guide"]],
            width_indicator=(
                OverlayLine(
                    start=NervePoint(**planning_overlay["width_indicator"]["start"]),
                    end=NervePoint(**planning_overlay["width_indicator"]["end"]),
                ) if planning_overlay.get("width_indicator") else None
            ),
            sector_lines=[
                OverlayLine(
                    start=NervePoint(**sl["start"]),
                    end=NervePoint(**sl["end"]),
                )
                for sl in planning_overlay.get("sector_lines", [])
            ],
        ),
        bone_metrics=BoneMetrics(**bone_metrics),
        scan_region=scan_region,
        ian_applicable=ian_applicable,
        ian_detected=ian_detected,
        ian_status_message=ian_status_message,
        safe_zone_path=[NervePoint(**p) for p in safe_zone_path],
        recommendation_line=recommendation_line,
        metadata={
            "pixel_spacing": analysis_metadata["pixel_spacing"],
            "slice_thickness": analysis_metadata["slice_thickness"],
            "rows": analysis_metadata["rows"],
            "columns": analysis_metadata["columns"],
            "num_slices": analysis_metadata["num_slices"],
            "patient_name": analysis_metadata.get("patient_name", "Unknown"),
            "dataset_type": dataset_type,
            "modality": analysis_metadata.get("modality", "UNKNOWN"),
            "is_calibrated_hu": bool(analysis_metadata.get("is_calibrated_hu", False)),
        },
    )


@app.post("/analyze-jaw", response_model=AnalysisResponse)
async def analyze_jaw(
    file: UploadFile = File(...),
    tooth_x: Optional[int] = Query(None, description="Tooth region X coordinate"),
    tooth_y: Optional[int] = Query(None, description="Tooth region Y coordinate"),
    _user: dict = Depends(require_user),
):
    """
    Upload a DICOM (.dcm) CBCT file for full analysis.

    Returns OPG projection, nerve path, bone measurements, and metadata.
    """
    logger.info("Received file: %s (%s)", file.filename, file.content_type)
    try:
        volume, metadata = _load_volume_for_upload(file, workflow="cbct_implant")
        return _analyze_loaded_volume(
            volume=volume,
            metadata=metadata,
            tooth_x=tooth_x,
            tooth_y=tooth_y,
            workflow="cbct_implant",
        )
    except Exception as exc:
        traceback.print_exc()
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@app.post("/analyze-panoramic", response_model=AnalysisResponse)
async def analyze_panoramic(
    file: UploadFile = File(...),
    tooth_x: Optional[int] = Query(None, description="Optional X for canal region"),
    tooth_y: Optional[int] = Query(None, description="Optional Y for canal region"),
    _user: dict = Depends(require_user),
):
    """
    Upload a panoramic / OPG DICOM focused on mandibular canal tracing and
    inferior border visualization.
    """
    logger.info("Received panoramic file: %s (%s)", file.filename, file.content_type)
    try:
        volume, metadata = _load_volume_for_upload(file, workflow="panoramic_mandibular_canal")
    except HTTPException:
        raise
    except Exception as exc:
        logger.exception("Failed to decode input")
        raise HTTPException(status_code=422, detail=f"Input decode error: {exc}") from exc

    return _analyze_loaded_volume(
        volume=volume,
        metadata=metadata,
        tooth_x=tooth_x,
        tooth_y=tooth_y,
        workflow="panoramic_mandibular_canal",
    )


@app.post("/measure", response_model=MeasureResponse)
async def measure(
    req: MeasureRequest,
    _user: dict = Depends(require_user),
):
    """
    Compute bone metrics at a specific (x, y) coordinate
    for a previously uploaded volume.
    """
    cached = _volume_cache.get(req.session_id)
    if cached is None:
        raise HTTPException(status_code=404, detail="Session not found. Re-upload DICOM.")

    bone_metrics = calculate_bone_metrics(
        cached["volume"],
        cached["bone_mask"],
        cached["nerve_mask"],
        cached["metadata"],
        {"x": req.x, "y": req.y},
    )
    planning_overlay = build_planning_overlay(
        cached["volume"], cached["bone_mask"], bone_metrics
    )
    scan_region = str(cached.get("scan_region", "unknown"))
    ian_detected = bool(cached.get("ian_detected", False))
    ian_applicable = bool(cached.get("ian_applicable", False))
    ian_status_message = str(
        cached.get(
            "ian_status_message",
            "IAN not applicable / not detected - manual clinical verification required.",
        )
    )
    safe_zone_path = list(cached.get("safe_zone_path", []))
    recommendation_line = _build_recommendation_line(
        scan_region=scan_region,
        ian_detected=ian_detected,
        safe_height_mm=float(bone_metrics.get("safe_height_mm", 0.0)),
    )
    return MeasureResponse(
        bone_metrics=BoneMetrics(**bone_metrics),
        planning_overlay=PlanningOverlay(
            outer_contour=[NervePoint(**p) for p in planning_overlay["outer_contour"]],
            inner_contour=[NervePoint(**p) for p in planning_overlay["inner_contour"]],
            base_guide=[NervePoint(**p) for p in planning_overlay["base_guide"]],
            width_indicator=(
                OverlayLine(
                    start=NervePoint(**planning_overlay["width_indicator"]["start"]),
                    end=NervePoint(**planning_overlay["width_indicator"]["end"]),
                ) if planning_overlay.get("width_indicator") else None
            ),
            sector_lines=[
                OverlayLine(
                    start=NervePoint(**sl["start"]),
                    end=NervePoint(**sl["end"]),
                )
                for sl in planning_overlay.get("sector_lines", [])
            ],
        ),
        scan_region=scan_region,
        ian_applicable=ian_applicable,
        ian_detected=ian_detected,
        ian_status_message=ian_status_message,
        safe_zone_path=[NervePoint(**p) for p in safe_zone_path],
        recommendation_line=recommendation_line,
    )


# ---------- Run ----------
if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host="0.0.0.0", port=8000, reload=True)

