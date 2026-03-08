"""
Dental Implant Planning – FastAPI Backend
==========================================
Endpoints:
  POST /analyze-jaw   – accepts a DICOM (.dcm) file, returns analysis JSON
  POST /measure       – accepts coordinates + cached volume ID, returns bone metrics
  GET  /health        – health check
"""

from __future__ import annotations

import uuid
import logging
from typing import Optional

from fastapi import FastAPI, UploadFile, File, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

from dicom_processor import (
    load_dicom_volume,
    generate_opg,
    calculate_bone_metrics,
    build_planning_overlay,
    select_default_measurement_site,
)
from segmentation_model import UNetSegmentor, extract_nerve_path_2d

# ---------- Logging ----------
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("implant-api")

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
    patient_name: str
    opg_image_base64: str
    nerve_path: list[NervePoint]
    arch_path: list[NervePoint]
    planning_overlay: PlanningOverlay
    bone_metrics: BoneMetrics
    metadata: dict


class MeasureRequest(BaseModel):
    session_id: str
    x: int
    y: int


class MeasureResponse(BaseModel):
    bone_metrics: BoneMetrics
    planning_overlay: PlanningOverlay


# ---------- Endpoints ----------

def _is_dicom(data: bytes) -> bool:
    """
    Check if raw bytes are a DICOM file.

    Standard DICOM Part 10:  bytes 128-131 == b'DICM'
    Legacy ACR-NEMA (no preamble): first two bytes are a low group tag (0x0000–0x0009)
    """
    if len(data) < 132:
        # Too short for a standard preamble; try ACR-NEMA heuristic
        return len(data) > 4 and data[1] == 0x00 and data[0] in range(0x00, 0x10)
    # Standard DICOM preamble check
    if data[128:132] == b"DICM":
        return True
    # ACR-NEMA fallback: group tag in first two bytes is very small
    group = int.from_bytes(data[0:2], byteorder="little")
    return group < 0x0010

@app.get("/health")
async def health():
    return {"status": "ok"}


@app.post("/analyze-jaw", response_model=AnalysisResponse)
async def analyze_jaw(
    file: UploadFile = File(...),
    tooth_x: Optional[int] = Query(None, description="Tooth region X coordinate"),
    tooth_y: Optional[int] = Query(None, description="Tooth region Y coordinate"),
):
    """
    Upload a DICOM (.dcm) CBCT file for full analysis.

    Returns OPG projection, nerve path, bone measurements, and metadata.
    """
    logger.info("Received file: %s (%s)", file.filename, file.content_type)

    dicom_bytes = await file.read()
    if len(dicom_bytes) == 0:
        raise HTTPException(status_code=400, detail="Empty file.")

    # Validate by DICOM magic bytes rather than filename extension.
    # Standard DICOM files have "DICM" at byte offset 128.
    # Older ACR-NEMA files have no preamble but start with a valid tag (00 00 / 08 00).
    if not _is_dicom(dicom_bytes):
        raise HTTPException(
            status_code=400,
            detail="File does not appear to be a valid DICOM file. "
                   "Expected 'DICM' preamble at offset 128 or a valid ACR-NEMA header."
        )

    # 1. Load DICOM volume
    try:
        volume, metadata = load_dicom_volume(dicom_bytes)
    except Exception as e:
        logger.exception("Failed to load DICOM")
        raise HTTPException(status_code=422, detail=f"DICOM load error: {e}")

    logger.info(
        "Volume loaded: shape=%s, spacing=%s, patient=%s",
        volume.shape,
        metadata["pixel_spacing"],
        metadata["patient_name"],
    )

    # 2. Generate OPG
    try:
        opg_b64 = generate_opg(volume, metadata)
    except Exception as e:
        logger.exception("OPG generation failed")
        opg_b64 = ""

    # 3. Segmentation (bone + nerve)
    seg_result = segmentor.predict(volume)
    bone_mask = seg_result["bone_mask"]
    nerve_mask = seg_result["nerve_mask"]
    mandible_center = seg_result.get("mandible_center", None)

    tooth_coords = None
    if tooth_x is not None and tooth_y is not None:
        tooth_coords = {"x": tooth_x, "y": tooth_y}
    else:
        auto_site = select_default_measurement_site(volume, bone_mask, nerve_mask)
        if auto_site is not None:
            tooth_coords = auto_site
        elif mandible_center is not None:
            tooth_coords = {"x": mandible_center[1], "y": mandible_center[0]}

    bone_metrics = calculate_bone_metrics(
        volume, bone_mask, nerve_mask, metadata, tooth_coords
    )
    nerve_path = extract_nerve_path_2d(
        nerve_mask,
        bone_mask=bone_mask,
        preferred_x=bone_metrics["measurement_location"]["x"],
    )
    planning_overlay = build_planning_overlay(volume, bone_mask, bone_metrics)
    arch_pts = planning_overlay["outer_contour"]

    session_id = str(uuid.uuid4())
    _volume_cache[session_id] = {
        "volume": volume,
        "bone_mask": bone_mask,
        "nerve_mask": nerve_mask,
        "metadata": metadata,
    }
    if len(_volume_cache) > 5:
        oldest = next(iter(_volume_cache))
        del _volume_cache[oldest]

    return AnalysisResponse(
        session_id=session_id,
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
        metadata={
            "pixel_spacing": metadata["pixel_spacing"],
            "slice_thickness": metadata["slice_thickness"],
            "rows": metadata["rows"],
            "columns": metadata["columns"],
            "num_slices": metadata["num_slices"],
        },
    )


@app.post("/measure", response_model=MeasureResponse)
async def measure(req: MeasureRequest):
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
    )


# ---------- Run ----------
if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host="0.0.0.0", port=8000, reload=True)

