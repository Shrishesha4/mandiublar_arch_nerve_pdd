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
import os
import shutil
import time
import traceback
from typing import Optional
import numpy as np

from fastapi import FastAPI, UploadFile, File, HTTPException, Query, Depends, status, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.routing import APIRoute
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from PIL import Image
import pydicom
from pydicom import config as pydicom_config
from pydantic import BaseModel, Field

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

try:
    import google.generativeai as genai
except Exception:  # pragma: no cover
    genai = None

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


# ---------- Web-compatible schemas ----------
class LoginRequest(BaseModel):
    email: str
    password: str


class RegisterRequest(BaseModel):
    name: str
    email: str
    phone: str = "N/A"
    practice: Optional[str] = None
    practice_name: Optional[str] = None
    password: str


class UserProfile(BaseModel):
    name: str
    email: str
    phone: Optional[str] = None
    practice_name: Optional[str] = None
    bio: Optional[str] = None
    specialty: Optional[str] = None


class UserUpdate(UserProfile):
    pass


class BillingInfo(BaseModel):
    plan_name: str
    card_last4: str


class TeamMemberCreate(BaseModel):
    name: str
    email: str
    role: str


class CaseCreate(BaseModel):
    fname: str
    lname: str
    patient_age: Optional[int] = None
    tooth_number: Optional[str] = None
    complaint: Optional[str] = None
    case_type: Optional[str] = None
    details: Optional[str] = None
    case_id: Optional[str] = None


class CaseResponse(BaseModel):
    id: int
    case_id: str
    fname: str
    lname: str
    patient_age: Optional[int] = None
    tooth_number: Optional[str] = None
    complaint: Optional[str] = None
    case_type: Optional[str] = None
    status: str
    created_at: str


class CaseAnalysisResponse(BaseModel):
    id: int
    case_id: int
    created_at: str
    workflow: str = "cbct_implant"
    scan_region: str = "unknown"
    ian_applicable: bool = False
    ian_detected: bool = False
    arch_curve_data: list[list[float]]
    nerve_path_data: list[list[float]]
    planning_overlay_data: dict = Field(default_factory=dict)
    safe_zone_path_data: list[list[float]] = Field(default_factory=list)
    bone_width_36: str
    bone_height: str
    nerve_distance: str
    safe_implant_length: str
    opg_image_base64: Optional[str] = None
    ian_status_message: Optional[str] = None
    recommendation_line: Optional[str] = None
    clinical_report: Optional[str] = None
    patient_explanation: Optional[str] = None


class HistoryReportSummary(BaseModel):
    case_id: str
    created_at: str
    bone_width_36: str
    bone_height: str
    nerve_distance: str
    safe_implant_length: str
    clinical_report: Optional[str] = None


class HistoryInsightResponse(BaseModel):
    case_id: str
    patient_name: str
    selected_oldest_reports: list[HistoryReportSummary]
    selected_newest_reports: list[HistoryReportSummary]
    insight: str
    source: str


class ChatRequest(BaseModel):
    message: str
    context: Optional[str] = None


class ChatResponse(BaseModel):
    reply: str


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


def _classify_scan_region(
    arch_path: list[dict],
    image_rows: int,
    workflow: str,
    nerve_path: list[dict] | None = None,
) -> str:
    if arch_path:
        ys = [float(p.get("y", 0.0)) for p in arch_path]
        if ys:
            median_y = float(np.median(np.asarray(ys, dtype=np.float64)))
            return "mandible" if median_y >= (float(image_rows) * 0.5) else "maxilla"

    # Panoramic canal workflow is mandibular-focused; prefer mandibular fallback
    # when arch extraction is missing but a canal-like path exists.
    if workflow == "panoramic_mandibular_canal":
        if nerve_path and len(nerve_path) >= 8:
            return "mandible"
        return "mandible"

    if nerve_path:
        ys = [float(p.get("y", 0.0)) for p in nerve_path]
        if ys:
            median_y = float(np.median(np.asarray(ys, dtype=np.float64)))
            return "mandible" if median_y >= (float(image_rows) * 0.5) else "maxilla"

    return "unknown"


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
    if scan_region == "mandible":
        return "IAN not confidently detected - manual clinical verification required."
    if scan_region == "maxilla":
        return "IAN not applicable / not detected - manual clinical verification required."
    return "IAN not confidently detected - manual clinical verification required."


def _build_ian_status_message(scan_region: str, ian_detected: bool) -> str:
    if scan_region == "mandible" and ian_detected:
        return "IAN detected. Safe implant zone highlighted above the nerve."
    if scan_region == "mandible":
        return "IAN not confidently detected; manual clinical verification required."
    if scan_region == "maxilla":
        return "IAN not applicable / not detected - manual clinical verification required."
    return "IAN not confidently detected; manual clinical verification required."

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


@app.post("/login")
async def web_login(req: LoginRequest, request: Request):
    user = auth_db.authenticate(req.email, req.password)
    if user is None:
        raise HTTPException(status_code=401, detail="Invalid email or password")

    token = auth_db.create_session(user_id=user["id"], ttl_hours=24 * 7)
    auth_db.log_login(
        user_id=user["id"],
        ip_address=request.client.host if request.client else None,
        user_agent=request.headers.get("user-agent"),
    )
    return {
        "token": token,
        "token_type": "bearer",
        "user": {
            "name": user.get("name") or "Test Doctor",
            "email": user["email"],
            "phone": user.get("phone") or "N/A",
        },
    }


@app.post("/register")
async def web_register(req: RegisterRequest):
    practice_name = req.practice_name or req.practice or "Private Practice"
    try:
        user = auth_db.create_user(
            req.email,
            req.password,
            name=req.name,
            phone=req.phone,
            practice_name=practice_name,
        )
    except ValueError as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc

    token = auth_db.create_session(user_id=user["id"], ttl_hours=24 * 7)
    return {
        "token": token,
        "token_type": "bearer",
        "user": {
            "name": user.get("name") or req.name,
            "email": user["email"],
            "phone": user.get("phone") or req.phone,
            "practice": user.get("practice_name") or practice_name,
        },
    }


@app.get("/auth/google")
async def auth_google_placeholder():
    raise HTTPException(status_code=501, detail="Google OAuth is not configured in this backend")


def _chat_reply(message: str) -> str:
    msg = message.lower()
    if "bone" in msg:
        return "Bone density ranges from D1 to D4. Use CBCT cross-sections to confirm stability at the exact implant site."
    if "nerve" in msg:
        return "Maintain at least a 2mm safety margin from the inferior alveolar nerve during implant planning."
    if "implant" in msg:
        return "Implant diameter and length should be selected from measured bone width and safe height, then verified clinically."
    if "cost" in msg or "price" in msg or "billing" in msg:
        return "Billing and subscription details are available in Settings > Billing."
    return "I can help with implant planning, CBCT interpretation, and safety checks."


def _history_item_to_text(item: dict) -> str:
    return (
        f"case_id={item.get('case_id')}"
        f", created_at={item.get('created_at')}"
        f", bone_width_36={item.get('bone_width_36')}"
        f", bone_height={item.get('bone_height')}"
        f", nerve_distance={item.get('nerve_distance')}"
        f", safe_implant_length={item.get('safe_implant_length')}"
        f", clinical_report={item.get('clinical_report') or '-'}"
    )


def _safe_float(raw_value: str | None) -> float:
    try:
        return float(raw_value or 0.0)
    except Exception:
        return 0.0


def _build_history_fallback(selected: list[dict], patient_name: str) -> str:
    if not selected:
        return f"No prior reports found for {patient_name}."

    avg_height = float(np.mean([_safe_float(item.get("bone_height")) for item in selected]))
    avg_width = float(np.mean([_safe_float(item.get("bone_width_36")) for item in selected]))
    min_nerve_distance = min(_safe_float(item.get("nerve_distance")) for item in selected)

    return (
        f"Historical trend for {patient_name}: based on {len(selected)} prior reports, "
        f"average bone height is {avg_height:.1f} mm and average bone width is {avg_width:.1f} mm. "
        f"Lowest recorded nerve distance is {min_nerve_distance:.1f} mm. "
        "Use this as context and confirm final implant decisions with current CBCT cross-sections."
    )


def _generate_history_insight_with_gemini(patient_name: str, selected_reports: list[dict]) -> tuple[str, str]:
    api_key = os.getenv("GEMINI_API_KEY", "").strip()
    if not api_key or genai is None:
        return _build_history_fallback(selected_reports, patient_name), "fallback"

    prompt = "\n".join([
        "You are assisting a dental implant clinician.",
        f"Patient: {patient_name}",
        "Use only the provided historical reports to summarize trends and risks.",
        "Provide concise clinical insight in 4-6 sentences.",
        "Include trend in bone dimensions, nerve-distance stability, and one clear recommendation.",
        "Historical reports:",
        *[f"- {_history_item_to_text(item)}" for item in selected_reports],
    ])

    try:
        genai.configure(api_key=api_key)
        model = genai.GenerativeModel("gemini-1.5-flash")
        response = model.generate_content(prompt)
        text = (getattr(response, "text", "") or "").strip()
        if text:
            return text, "gemini"
    except Exception:
        logger.exception("Gemini history insight failed")

    return _build_history_fallback(selected_reports, patient_name), "fallback"


def _build_case_report_fallback(case_row: dict, analysis: AnalysisResponse) -> tuple[str, str, str]:
    metrics = analysis.bone_metrics
    patient_name = f"{case_row.get('fname', '')} {case_row.get('lname', '')}".strip() or "the patient"
    clinical_report = (
        f"Clinical summary for {patient_name}: bone width is {metrics.width_mm:.1f} mm, "
        f"bone height is {metrics.height_mm:.1f} mm, and safe height is {metrics.safe_height_mm:.1f} mm. "
        f"{analysis.ian_status_message} {analysis.recommendation_line}"
    )
    patient_explanation = (
        f"Your scan shows a bone width of {metrics.width_mm:.1f} mm and safe implant height of "
        f"{metrics.safe_height_mm:.1f} mm. {analysis.recommendation_line}"
    )
    return clinical_report, patient_explanation, "fallback"


def _generate_case_report_with_gemini(case_row: dict, analysis: AnalysisResponse) -> tuple[str, str, str]:
    api_key = os.getenv("GEMINI_API_KEY", "").strip()
    if not api_key or genai is None:
        return _build_case_report_fallback(case_row, analysis)

    metrics = analysis.bone_metrics
    patient_name = f"{case_row.get('fname', '')} {case_row.get('lname', '')}".strip() or "Unknown"
    prompt = "\n".join([
        "You are assisting a dental implant clinician.",
        f"Patient: {patient_name}",
        "Use ONLY the provided CBCT/panoramic analysis outputs.",
        "Return valid JSON with keys: clinical_report, patient_explanation.",
        "clinical_report: 4-6 concise clinician-facing sentences.",
        "patient_explanation: 2-3 plain-language sentences.",
        "Analysis inputs:",
        f"- bone_width_mm: {metrics.width_mm}",
        f"- bone_height_mm: {metrics.height_mm}",
        f"- safe_height_mm: {metrics.safe_height_mm}",
        f"- safety_status: {metrics.safety_status}",
        f"- safety_reason: {metrics.safety_reason}",
        f"- ian_status_message: {analysis.ian_status_message}",
        f"- recommendation_line: {analysis.recommendation_line}",
        "Do not include markdown fences.",
    ])

    try:
        genai.configure(api_key=api_key)
        model = genai.GenerativeModel("gemini-1.5-flash")
        response = model.generate_content(prompt)
        raw = (getattr(response, "text", "") or "").strip()
        if raw:
            import json

            data = json.loads(raw)
            clinical = str(data.get("clinical_report") or "").strip()
            patient = str(data.get("patient_explanation") or "").strip()
            if clinical and patient:
                return clinical, patient, "gemini"
    except Exception:
        logger.exception("Gemini case report generation failed")

    return _build_case_report_fallback(case_row, analysis)


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

    scan_region = _classify_scan_region(
        arch_pts,
        int(analysis_metadata.get("rows", volume.shape[-2])),
        workflow=workflow,
        nerve_path=nerve_path,
    )
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
    if workflow == "panoramic_mandibular_canal":
        recommendation_line = (
            "Panoramic measurements are orientation-only. Final implant decision requires "
            "verified CBCT cross-sections and nerve relation."
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
            "rescale_slope": float(analysis_metadata.get("rescale_slope", 1.0)),
            "rescale_intercept": float(analysis_metadata.get("rescale_intercept", 0.0)),
            "has_rescale": bool(analysis_metadata.get("has_rescale", False)),
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
    if str(cached.get("workflow", "")) == "panoramic_mandibular_canal":
        recommendation_line = (
            "Panoramic measurements are orientation-only. Final implant decision requires "
            "verified CBCT cross-sections and nerve relation."
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


@app.get("/user", response_model=UserProfile)
async def get_user_profile(user: dict = Depends(require_user)):
    profile = auth_db.get_user_by_id(int(user["id"]))
    if profile is None:
        raise HTTPException(status_code=404, detail="User not found")
    return UserProfile(
        name=profile.get("name") or "Test Doctor",
        email=profile["email"],
        phone=profile.get("phone"),
        practice_name=profile.get("practice_name"),
        bio=profile.get("bio"),
        specialty=profile.get("specialty"),
    )


@app.put("/user")
async def update_user_profile(payload: UserUpdate, user: dict = Depends(require_user)):
    try:
        auth_db.update_user_profile(int(user["id"]), payload.model_dump())
        auth_db.log_activity(int(user["id"]), "Update Profile")
    except ValueError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    return {"message": "Profile updated"}


@app.get("/settings")
async def get_settings(user: dict = Depends(require_user)):
    return auth_db.get_settings(int(user["id"]))


@app.put("/settings")
async def update_settings(payload: dict, user: dict = Depends(require_user)):
    data = auth_db.update_settings(int(user["id"]), payload)
    auth_db.log_activity(int(user["id"]), "Update Settings", payload)
    return {"message": "Settings saved", **data}


@app.get("/team")
async def get_team(user: dict = Depends(require_user)):
    return auth_db.list_team_members(int(user["id"]))


@app.post("/team")
async def add_team_member(payload: TeamMemberCreate, user: dict = Depends(require_user)):
    member = auth_db.add_team_member(int(user["id"]), payload.model_dump())
    auth_db.log_activity(int(user["id"]), "Add Team Member", member)
    return {"message": "Member added", "id": member["id"]}


@app.delete("/team/{member_id}")
async def remove_team_member(member_id: int, user: dict = Depends(require_user)):
    deleted = auth_db.remove_team_member(int(user["id"]), member_id)
    if not deleted:
        raise HTTPException(status_code=404, detail="Member not found")
    auth_db.log_activity(int(user["id"]), "Remove Team Member", {"member_id": member_id})
    return {"message": "Member removed"}


@app.get("/billing")
async def get_billing(user: dict = Depends(require_user)):
    return auth_db.get_billing(int(user["id"]))


@app.post("/billing")
async def update_billing(payload: BillingInfo, user: dict = Depends(require_user)):
    billing = auth_db.update_billing(int(user["id"]), payload.plan_name, payload.card_last4)
    auth_db.log_activity(int(user["id"]), "Update Billing", {"plan_name": payload.plan_name})
    return {"message": "Plan updated successfully", "plan": billing.get("plan_name")}


@app.get("/cases", response_model=list[CaseResponse])
async def get_cases(user: dict = Depends(require_user)):
    rows = auth_db.list_cases(int(user["id"]))
    return [CaseResponse(**row) for row in rows]


@app.post("/cases", response_model=CaseResponse)
async def create_case(payload: CaseCreate, user: dict = Depends(require_user)):
    created = auth_db.create_case(int(user["id"]), payload.model_dump())
    auth_db.log_activity(int(user["id"]), "Create Case", {"case_id": created.get("case_id")})
    return CaseResponse(**created)


@app.get("/cases/{case_id}", response_model=CaseResponse)
async def get_case(case_id: str, user: dict = Depends(require_user)):
    case_row = auth_db.get_case(int(user["id"]), case_id)
    if case_row is None:
        raise HTTPException(status_code=404, detail="Case not found")
    return CaseResponse(**case_row)


@app.post("/cases/{case_id}/upload")
async def upload_case_file(
    case_id: str,
    file: UploadFile = File(...),
    user: dict = Depends(require_user),
):
    case_row = auth_db.get_case(int(user["id"]), case_id)
    if case_row is None:
        raise HTTPException(status_code=404, detail="Case not found")

    project_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    upload_dir = os.path.join(project_root, "uploads")
    os.makedirs(upload_dir, exist_ok=True)
    filename = f"{int(time.time())}_{file.filename}"
    file_path = os.path.join(upload_dir, filename)

    with open(file_path, "wb") as buffer:
        shutil.copyfileobj(file.file, buffer)

    auth_db.add_case_file(case_row["id"], file.filename, file_path)
    auth_db.update_case_status(int(user["id"]), case_id, "Ready")
    return {"message": "File uploaded successfully", "path": file_path}


@app.put("/cases/{case_id}/status")
async def update_case_status(case_id: str, status: str, user: dict = Depends(require_user)):
    ok = auth_db.update_case_status(int(user["id"]), case_id, status)
    if not ok:
        raise HTTPException(status_code=404, detail="Case not found")
    return {"message": "Status updated"}


@app.post("/analysis/run/{case_id}", response_model=CaseAnalysisResponse)
async def run_case_analysis(case_id: str, user: dict = Depends(require_user)):
    case_row = auth_db.get_case(int(user["id"]), case_id)
    if case_row is None:
        raise HTTPException(status_code=404, detail="Case not found")

    auth_db.update_case_status(int(user["id"]), case_id, "Analyzing...")
    files = auth_db.list_case_files(case_row["id"])
    if not files:
        auth_db.update_case_status(int(user["id"]), case_id, "Ready")
        raise HTTPException(status_code=400, detail="No uploaded files found for this case")

    latest_file = files[-1]
    file_path = latest_file.get("file_path") or ""
    if not file_path or not os.path.exists(file_path):
        auth_db.update_case_status(int(user["id"]), case_id, "Ready")
        raise HTTPException(status_code=400, detail="Uploaded file is missing on server")

    try:
        with open(file_path, "rb") as fp:
            payload = fp.read()

        input_type = detect_input_type(payload, latest_file.get("filename"))
        workflow = "cbct_implant" if input_type in ("zip_cbct", "dicom_single") else "panoramic_mandibular_canal"
        analysis_result = _analyze_dicom_bytes(
            dicom_bytes=payload,
            tooth_x=None,
            tooth_y=None,
            workflow=workflow,
            file_name=latest_file.get("filename"),
        )

        clinical_report, patient_explanation, report_source = _generate_case_report_with_gemini(
            case_row,
            analysis_result,
        )

        bone_metrics = analysis_result.bone_metrics
        nerve_points = [[float(p.x), float(p.y)] for p in analysis_result.nerve_path]
        arch_points = [[float(p.x), float(p.y)] for p in analysis_result.arch_path]
        safe_zone_points = [[float(p.x), float(p.y)] for p in analysis_result.safe_zone_path]
        nerve_distance = max(0.0, float(bone_metrics.height_mm - bone_metrics.safe_height_mm))

        saved = auth_db.save_case_analysis(
            case_row["id"],
            {
            "workflow": analysis_result.workflow,
            "scan_region": analysis_result.scan_region,
            "ian_applicable": analysis_result.ian_applicable,
            "ian_detected": analysis_result.ian_detected,
            "ian_status_message": analysis_result.ian_status_message,
            "recommendation_line": analysis_result.recommendation_line,
            "opg_image_base64": analysis_result.opg_image_base64,
                "arch_curve_data": arch_points,
                "nerve_path_data": nerve_points,
            "planning_overlay_data": analysis_result.planning_overlay.model_dump(),
            "safe_zone_path_data": safe_zone_points,
                "bone_width_36": f"{bone_metrics.width_mm:.1f}",
                "bone_height": f"{bone_metrics.height_mm:.1f}",
                "nerve_distance": f"{nerve_distance:.1f}",
                "safe_implant_length": f"{bone_metrics.safe_height_mm:.1f}",
                "clinical_report": clinical_report,
                "patient_explanation": patient_explanation,
            },
        )

        auth_db.update_case_status(int(user["id"]), case_id, "Analysis Complete")
        response_payload = {
            **saved,
            "opg_image_base64": analysis_result.opg_image_base64,
            "ian_status_message": analysis_result.ian_status_message,
            "recommendation_line": analysis_result.recommendation_line,
            "clinical_report": (
                f"{saved.get('clinical_report') or ''}"
                f" (generated via {report_source})"
            ).strip(),
        }
        return CaseAnalysisResponse(**response_payload)
    except HTTPException:
        auth_db.update_case_status(int(user["id"]), case_id, "Ready")
        raise
    except Exception as exc:
        logger.exception("Case analysis failed for case_id=%s", case_id)
        auth_db.update_case_status(int(user["id"]), case_id, "Ready")
        raise HTTPException(status_code=500, detail=f"Case analysis failed: {exc}") from exc


@app.get("/analysis/result/{case_id}", response_model=CaseAnalysisResponse)
async def get_case_analysis_result(case_id: str, user: dict = Depends(require_user)):
    case_row = auth_db.get_case(int(user["id"]), case_id)
    if case_row is None:
        raise HTTPException(status_code=404, detail="Case not found")

    analysis = auth_db.get_analysis_by_case(case_row["id"])
    if analysis is None:
        raise HTTPException(status_code=404, detail="Analysis not found")
    analysis.setdefault("workflow", "cbct_implant")
    analysis.setdefault("scan_region", "unknown")
    analysis.setdefault("ian_applicable", False)
    analysis.setdefault("ian_detected", False)
    analysis.setdefault("planning_overlay_data", {})
    analysis.setdefault("safe_zone_path_data", [])
    analysis.setdefault("opg_image_base64", None)
    analysis.setdefault("ian_status_message", None)
    analysis.setdefault("recommendation_line", None)
    return CaseAnalysisResponse(**analysis)


@app.get("/analysis/history-insight/{case_id}", response_model=HistoryInsightResponse)
async def get_history_insight(case_id: str, user: dict = Depends(require_user)):
    case_row = auth_db.get_case(int(user["id"]), case_id)
    if case_row is None:
        raise HTTPException(status_code=404, detail="Case not found")

    history = auth_db.list_patient_analysis_history(
        int(user["id"]),
        case_row["fname"],
        case_row["lname"],
        exclude_case_pk=case_row["id"],
    )

    oldest = history[:2]
    newest = history[-2:] if len(history) > 2 else []

    selected_reports: list[dict] = []
    selected_case_ids: set[int] = set()
    for item in oldest + newest:
        case_pk = int(item["case_pk"])
        if case_pk in selected_case_ids:
            continue
        selected_case_ids.add(case_pk)
        selected_reports.append(item)

    insight_text, source = _generate_history_insight_with_gemini(
        f"{case_row['fname']} {case_row['lname']}",
        selected_reports,
    )

    return HistoryInsightResponse(
        case_id=case_row["case_id"],
        patient_name=f"{case_row['fname']} {case_row['lname']}",
        selected_oldest_reports=[HistoryReportSummary(**item) for item in oldest],
        selected_newest_reports=[HistoryReportSummary(**item) for item in newest],
        insight=insight_text,
        source=source,
    )


@app.post("/chat", response_model=ChatResponse)
async def chat(payload: ChatRequest, _user: dict = Depends(require_user)):
    return ChatResponse(reply=_chat_reply(payload.message))


def add_api_prefix_aliases(app: FastAPI, prefix: str = "/api") -> None:
    """Expose every existing route under an /api prefix for mobile and web parity."""

    existing_paths = {route.path for route in app.routes}
    for route in list(app.routes):
        if not isinstance(route, APIRoute):
            continue
        if route.path.startswith(prefix):
            continue

        prefixed_path = f"{prefix}{route.path}"
        if prefixed_path in existing_paths:
            continue

        app.add_api_route(
            path=prefixed_path,
            endpoint=route.endpoint,
            methods=list(route.methods or []),
            response_model=route.response_model,
            status_code=route.status_code,
            response_class=route.response_class,
            dependencies=route.dependencies,
            summary=route.summary,
            description=route.description,
            name=f"{route.name}-api" if route.name else None,
            response_model_include=route.response_model_include,
            response_model_exclude=route.response_model_exclude,
            response_model_exclude_unset=route.response_model_exclude_unset,
            response_model_exclude_defaults=route.response_model_exclude_defaults,
            response_model_exclude_none=route.response_model_exclude_none,
            include_in_schema=route.include_in_schema,
            responses=route.responses,
            tags=route.tags,
            deprecated=route.deprecated,
            operation_id=f"{route.operation_id}-api" if route.operation_id else None,
        )


add_api_prefix_aliases(app)


# ---------- Run ----------
if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host="0.0.0.0", port=8000, reload=True)

