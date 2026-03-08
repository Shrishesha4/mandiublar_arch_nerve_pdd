"""
DICOM Processor
----------------
Handles loading CBCT DICOM volumes, generating OPG projections,
detecting the dental arch, and computing bone measurements.
"""

from __future__ import annotations

import io
import base64
import tempfile
from pathlib import Path

import numpy as np
import pydicom
import SimpleITK as sitk
from scipy import ndimage
from skimage.morphology import skeletonize
from PIL import Image


# ======================================================================
# Loading
# ======================================================================

def load_dicom_volume(dicom_bytes: bytes) -> tuple[np.ndarray, dict]:
    """
    Load a DICOM file (single multi-frame or enhanced CT) and return
    the 3D volume + relevant metadata.

    Parameters
    ----------
    dicom_bytes : bytes
        Raw bytes of a single .dcm file.

    Returns
    -------
    volume : np.ndarray  – shape (slices, H, W), float64
    metadata : dict       – pixel_spacing, slice_thickness, patient_name, etc.
    """
    # Write to temp file because pydicom / SimpleITK work best with paths
    tmp = tempfile.NamedTemporaryFile(suffix=".dcm", delete=False)
    tmp.write(dicom_bytes)
    tmp.flush()
    tmp_path = tmp.name
    tmp.close()

    ds = pydicom.dcmread(tmp_path)

    # Extract metadata
    pixel_spacing = _get_pixel_spacing(ds)
    slice_thickness = float(getattr(ds, "SliceThickness", 1.0))
    patient_name = str(getattr(ds, "PatientName", "Unknown"))

    # Try SimpleITK first (handles multi-frame well)
    try:
        sitk_img = sitk.ReadImage(tmp_path)
        volume = sitk.GetArrayFromImage(sitk_img).astype(np.float64)
        spacing = list(sitk_img.GetSpacing())  # (x, y, z)
        if len(spacing) >= 3:
            pixel_spacing = [spacing[1], spacing[0]]
            slice_thickness = spacing[2]
    except Exception:
        # Fallback: single-frame → make pseudo-3D
        arr = ds.pixel_array.astype(np.float64)
        if arr.ndim == 2:
            volume = arr[np.newaxis, :, :]
        else:
            volume = arr

    # Apply rescale slope / intercept if present
    slope = float(getattr(ds, "RescaleSlope", 1))
    intercept = float(getattr(ds, "RescaleIntercept", 0))
    volume = volume * slope + intercept

    metadata = {
        "pixel_spacing": pixel_spacing,  # [row_spacing, col_spacing] in mm
        "slice_thickness": slice_thickness,  # mm
        "patient_name": patient_name,
        "rows": int(getattr(ds, "Rows", volume.shape[-2])),
        "columns": int(getattr(ds, "Columns", volume.shape[-1])),
        "num_slices": volume.shape[0],
    }

    Path(tmp_path).unlink(missing_ok=True)
    return volume, metadata


def _get_pixel_spacing(ds) -> list[float]:
    """Extract pixel spacing from various DICOM tags."""
    if hasattr(ds, "PixelSpacing"):
        return [float(ds.PixelSpacing[0]), float(ds.PixelSpacing[1])]
    if hasattr(ds, "ImagerPixelSpacing"):
        return [float(ds.ImagerPixelSpacing[0]), float(ds.ImagerPixelSpacing[1])]
    return [1.0, 1.0]


# ======================================================================
# OPG (Panoramic Projection)
# ======================================================================

def detect_dental_arch(volume: np.ndarray, metadata: dict) -> np.ndarray:
    """
    Detect the dental arch curve on an axial MIP.

    Returns an Nx2 array of (row, col) control points tracing the arch
    from left to right.
    """
    # Take an axial MIP of the lower third (mandible region)
    n_slices = volume.shape[0]
    lower = volume[n_slices // 2:, :, :]
    axial_mip = np.max(lower, axis=0)  # shape (H, W)

    # Threshold to find dense bone
    thresh = np.percentile(axial_mip, 80)
    binary = axial_mip > thresh

    # Morphological cleanup
    binary = ndimage.binary_closing(binary, iterations=5)
    binary = ndimage.binary_fill_holes(binary)

    # Skeletonise to get arch centreline
    skel = skeletonize(binary)
    coords = np.argwhere(skel)  # (N, 2): row, col

    if len(coords) < 10:
        # Fallback: parabolic arch
        W = volume.shape[2]
        H = volume.shape[1]
        xs = np.linspace(0, W - 1, 60).astype(int)
        ys = (0.001 * (xs - W // 2) ** 2 + H // 2).astype(int)
        return np.stack([ys, xs], axis=1)

    # Sort by column (left → right)
    coords = coords[coords[:, 1].argsort()]

    # Subsample for smooth spline
    step = max(1, len(coords) // 60)
    return coords[::step]


def generate_opg(volume: np.ndarray, metadata: dict) -> str:
    """
    Generate an OPG / panoramic radiograph image from the volume.

    • Single-slice (2-D) DICOM  → render the slice directly with
      aggressive percentile contrast stretch so anatomy is clearly visible.
    • Multi-slice (3-D) CBCT    → arch-unwrapped MIP.

    Returns a base64-encoded PNG string.
    """
    n_slices, H, W = volume.shape

    if n_slices <= 3:
        # ── 2-D path ─────────────────────────────────────────────────────
        mid = volume[n_slices // 2].astype(np.float64)

        # Percentile contrast stretch: ignore extreme outliers (air, metal)
        p_low  = np.percentile(mid, 1)
        p_high = np.percentile(mid, 99)
        if p_high <= p_low:
            # Flat image fallback
            p_low, p_high = mid.min(), mid.max()

        mid_clipped = np.clip(mid, p_low, p_high)

        if p_high > p_low:
            opg_norm = ((mid_clipped - p_low) / (p_high - p_low) * 255).astype(np.uint8)
        else:
            opg_norm = np.zeros((H, W), dtype=np.uint8)

    else:
        # ── 3-D path: arch-unwrapped MIP ─────────────────────────────────
        arch_points = detect_dental_arch(volume, metadata)
        num_points  = len(arch_points)
        opg = np.zeros((n_slices, num_points), dtype=np.float64)

        for i, (r, c) in enumerate(arch_points):
            r = int(np.clip(r, 0, H - 1))
            c = int(np.clip(c, 0, W - 1))
            r_lo, r_hi = max(0, r - 3), min(H, r + 4)
            c_lo, c_hi = max(0, c - 3), min(W, c + 4)
            patch = volume[:, r_lo:r_hi, c_lo:c_hi]
            opg[:, i] = np.max(patch, axis=(1, 2))

        p_low  = np.percentile(opg, 1)
        p_high = np.percentile(opg, 99)
        opg_c  = np.clip(opg, p_low, p_high)
        if p_high > p_low:
            opg_norm = ((opg_c - p_low) / (p_high - p_low) * 255).astype(np.uint8)
        else:
            opg_norm = np.zeros_like(opg, dtype=np.uint8)

    img = Image.fromarray(opg_norm, mode="L")
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return base64.b64encode(buf.getvalue()).decode("utf-8")


# ======================================================================
# Bone Measurements
# ======================================================================

def _classify_measurement(width_mm: float, safe_height_mm: float) -> tuple[str, str]:
    """
    Backend safety classification for planning preview.
    Kept more conservative than final surgical planning and less alarmist than before.
    """
    if width_mm < 4.5 or safe_height_mm < 4.0:
        return (
            "danger",
            "Bone at the selected site looks limited on this preview; augmentation or an alternate site may be needed."
        )
    if width_mm < 6.0 or safe_height_mm < 8.0:
        return (
            "warning",
            "Bone is borderline at this site; review another position or a narrower implant before deciding."
        )
    return (
        "safe",
        "Bone dimensions at this site look acceptable for preliminary implant planning."
    )


def calculate_bone_metrics(
    volume: np.ndarray,
    bone_mask: np.ndarray,
    nerve_mask: np.ndarray,
    metadata: dict,
    tooth_coords: dict | None = None,
) -> dict:
    """
    Calculate bone width (buccolingual) and height (crest to IAN canal)
    at a specified tooth coordinate.

    Handles both 2-D single-slice and 3-D multi-slice volumes automatically.
    """
    pixel_spacing = metadata["pixel_spacing"]   # [row_sp, col_sp] in mm
    slice_thickness = metadata["slice_thickness"]  # mm

    n_slices, H, W = volume.shape
    is_2d = n_slices <= 3

    if tooth_coords is None:
        cx, cy = W // 2, H // 2
    else:
        cx = int(tooth_coords.get("x", W // 2))
        cy = int(tooth_coords.get("y", H // 2))

    cx = int(np.clip(cx, 0, W - 1))
    cy = int(np.clip(cy, 0, H - 1))

    SAFETY_MARGIN_MM = 2.0

    # ── Working slice ────────────────────────────────────────────────────
    mid_slice = n_slices // 2
    axial_bone  = bone_mask[mid_slice]   # (H, W)
    axial_nerve = nerve_mask[mid_slice]  # (H, W)
    axial_vol   = volume[mid_slice]      # (H, W)

    # ── Bone Width (buccolingual / labio-lingual) ────────────────────────
    if is_2d:
        profiles = _extract_2d_mandible_profiles(axial_bone)
        if profiles is not None:
            prof_x, prof_top, prof_bottom = profiles
            idx = int(np.argmin(np.abs(prof_x - cx)))
            idx = int(np.clip(idx, 0, len(prof_x) - 1))
            width_px = max(0.0, float(prof_bottom[idx] - prof_top[idx]))
        else:
            row_bone = axial_bone[cy, :]
            bone_cols = np.where(row_bone)[0]
            width_px = float(bone_cols[-1] - bone_cols[0]) if len(bone_cols) >= 2 else 0.0
        width_mm = width_px * pixel_spacing[0]
    else:
        row_bone = axial_bone[cy, :]
        bone_cols = np.where(row_bone)[0]
        if len(bone_cols) < 2:
            search_region = axial_bone[max(0, cy - 10): min(H, cy + 11), :]
            row_sums = search_region.sum(axis=0)
            bone_cols_broad = np.where(row_sums > 0)[0]
            width_px = (bone_cols_broad[-1] - bone_cols_broad[0]) if len(bone_cols_broad) >= 2 else 0
        else:
            width_px = bone_cols[-1] - bone_cols[0]
        width_mm = width_px * pixel_spacing[1]

    # ── Bone Height (crest → IAN nerve, or crest → base of bone) ─────────
    if is_2d:
        profiles = _extract_2d_mandible_profiles(axial_bone)
        if profiles is not None:
            prof_x, prof_top, prof_bottom = profiles
            idx = int(np.argmin(np.abs(prof_x - cx)))
            idx = int(np.clip(idx, 0, len(prof_x) - 1))
            crest_row = int(round(prof_top[idx]))
            base_row = int(round(prof_bottom[idx]))
            col_nerve = axial_nerve[:, int(prof_x[idx])]
            nerve_rows = np.where(col_nerve)[0]
            if len(nerve_rows) >= 1:
                nerve_top_row = int(nerve_rows.min())
                height_px = max(0, nerve_top_row - crest_row)
            else:
                height_px = max(0, base_row - crest_row)
        else:
            col_bone  = axial_bone[:, cx]
            col_nerve = axial_nerve[:, cx]
            bone_rows  = np.where(col_bone)[0]
            nerve_rows = np.where(col_nerve)[0]
            if len(bone_rows) >= 2:
                crest_row = int(bone_rows.min())
                if len(nerve_rows) >= 1:
                    height_px = max(0, int(nerve_rows.min()) - crest_row)
                else:
                    height_px = int(bone_rows.max() - crest_row)
            elif len(bone_rows) == 1:
                height_px = 1
            else:
                height_px = 0
        height_mm = height_px * pixel_spacing[0]
    else:
        coronal_bone  = bone_mask[:, cy, cx]
        coronal_nerve = nerve_mask[:, cy, cx]
        bone_slices  = np.where(coronal_bone)[0]
        nerve_slices = np.where(coronal_nerve)[0]
        if len(bone_slices) >= 1 and len(nerve_slices) >= 1:
            crest_slice = bone_slices[0]
            nerve_top   = nerve_slices[0]
            height_px   = abs(nerve_top - crest_slice)
        elif len(bone_slices) >= 2:
            height_px = bone_slices[-1] - bone_slices[0]
        else:
            height_px = 0
        height_mm = height_px * slice_thickness

    safe_height_mm = max(0.0, height_mm - SAFETY_MARGIN_MM)
    safety_status, safety_reason = _classify_measurement(width_mm, safe_height_mm)

    # ── Bone density estimate ────────────────────────────────────────────
    region_bone = axial_bone[
        max(0, cy - 10): cy + 11,
        max(0, cx - 10): cx + 11,
    ]
    region_vol = axial_vol[
        max(0, cy - 10): cy + 11,
        max(0, cx - 10): cx + 11,
    ]
    density_estimate = float(np.mean(region_vol[region_bone])) if region_bone.any() else 0.0

    return {
        "width_mm":             round(width_mm, 2),
        "height_mm":            round(height_mm, 2),
        "safe_height_mm":       round(safe_height_mm, 2),
        "safety_margin_mm":     SAFETY_MARGIN_MM,
        "density_estimate_hu":  round(density_estimate, 1),
        "measurement_location": {"x": cx, "y": cy},
        "safety_status":        safety_status,
        "safety_reason":        safety_reason,
    }


# ======================================================================
# 2-D planning overlay helpers
# ======================================================================

def _extract_2d_mandible_profiles(axial_bone: np.ndarray):
    """
    Extract smooth superior/inferior mandibular profiles from a filled 2-D
    mandible mask.

    Returns (x, top_y, bottom_y) arrays ordered left→right, or None.
    """
    H, W = axial_bone.shape
    cols = np.where(axial_bone.any(axis=0))[0]
    if len(cols) < 20:
        return None

    x_vals: list[int] = []
    top_vals: list[float] = []
    bottom_vals: list[float] = []
    thickness_vals: list[int] = []

    for x in cols:
        rows = np.where(axial_bone[:, x])[0]
        if len(rows) < 3:
            continue
        top = int(rows.min())
        bottom = int(rows.max())
        thickness = bottom - top + 1
        if thickness < 6:
            continue
        x_vals.append(int(x))
        top_vals.append(float(top))
        bottom_vals.append(float(bottom))
        thickness_vals.append(int(thickness))

    if len(x_vals) < 20:
        return None

    x = np.asarray(x_vals, dtype=np.int32)
    top = np.asarray(top_vals, dtype=np.float64)
    bottom = np.asarray(bottom_vals, dtype=np.float64)
    thickness = np.asarray(thickness_vals, dtype=np.float64)

    # Trim unstable edges where the mask gets too thin.
    cutoff = max(8.0, np.percentile(thickness, 15))
    valid = thickness >= cutoff
    if valid.sum() >= 20:
        x = x[valid]
        top = top[valid]
        bottom = bottom[valid]

    if len(x) < 20:
        return None

    # Smooth profiles.
    top = ndimage.gaussian_filter1d(
        ndimage.median_filter(top, size=9, mode="nearest"),
        sigma=4,
        mode="nearest"
    )
    bottom = ndimage.gaussian_filter1d(
        ndimage.median_filter(bottom, size=9, mode="nearest"),
        sigma=4,
        mode="nearest"
    )

    return x, top, bottom


def _profile_points(x: np.ndarray, y: np.ndarray, step_target: int = 120) -> list[dict]:
    step = max(1, len(x) // step_target)
    return [
        {"x": int(px), "y": int(py)}
        for px, py in zip(x[::step], y[::step])
    ]


def _crest_preview_profile(top: np.ndarray, bottom: np.ndarray, inset_ratio: float = 0.14) -> np.ndarray:
    """
    Return a display crest that sits slightly inside the superior cortical edge.

    Using the raw top edge makes the plotted arch ride on the outer silhouette and
    looks unstable near the mandibular corners. A small inward inset tracks the
    clinically relevant crest more closely in the preview.
    """
    thickness = np.maximum(1.0, bottom - top)
    return top + thickness * inset_ratio


def _expand_preview_profiles(
    top: np.ndarray,
    bottom: np.ndarray,
    image_height: int,
    spread_ratio: float = 0.62,
) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """
    Build aesthetically wider preview contours around the true mandibular midline.

    The raw top/bottom mask edges can sit visually too close together in the app.
    For the planning overlay preview we expand both away from the local midline so the
    red guides have clearer spacing, while keeping a central guide exactly on the midline.
    """
    mid = (top + bottom) / 2.0
    thickness = np.maximum(1.0, bottom - top)
    half_span = np.maximum(4.0, thickness * spread_ratio)
    preview_top = np.clip(mid - half_span, 0, image_height - 1)
    preview_bottom = np.clip(mid + half_span, 0, image_height - 1)
    preview_mid = (preview_top + preview_bottom) / 2.0
    return preview_top, preview_mid, preview_bottom


def _trim_overlay_profiles(
    x: np.ndarray,
    top: np.ndarray,
    mid: np.ndarray,
    bottom: np.ndarray,
    trim_ratio: float = 0.08,
) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
    """
    Trim unstable ends so the preview shows three clean parallel arch curves
    instead of long tails near the mandibular edges.
    """
    trim = max(2, int(len(x) * trim_ratio))
    if len(x) <= trim * 2 + 4:
        return x, top, mid, bottom
    return (
        x[trim:-trim],
        top[trim:-trim],
        mid[trim:-trim],
        bottom[trim:-trim],
    )


def _make_width_indicator(
    x: np.ndarray,
    top: np.ndarray,
    bottom: np.ndarray,
    measure_x: int,
) -> dict | None:
    """
    Build a short normal-ish width indicator at the selected x-position.
    Returns {start:{x,y}, end:{x,y}}.
    """
    if len(x) < 3:
        return None

    idx = int(np.argmin(np.abs(x - measure_x)))
    idx = int(np.clip(idx, 1, len(x) - 2))

    x0 = float(x[idx])
    y_outer = float(top[idx])
    y_inner = float(bottom[idx])
    thickness = max(1.0, y_inner - y_outer)

    # Tangent from the local midline profile, then rotate to a normal.
    mid_prev = (top[idx - 1] + bottom[idx - 1]) / 2.0
    mid_next = (top[idx + 1] + bottom[idx + 1]) / 2.0
    dx = float(x[idx + 1] - x[idx - 1])
    dy = float(mid_next - mid_prev)
    tangent = np.array([dx, dy], dtype=np.float64)
    if np.linalg.norm(tangent) < 1e-6:
        tangent = np.array([1.0, 0.0])
    tangent = tangent / np.linalg.norm(tangent)
    normal = np.array([-tangent[1], tangent[0]], dtype=np.float64)

    mid = np.array([x0, (y_outer + y_inner) / 2.0], dtype=np.float64)
    p1 = mid - normal * (thickness / 2.0)
    p2 = mid + normal * (thickness / 2.0)

    return {
        "start": {"x": int(round(p1[0])), "y": int(round(p1[1]))},
        "end": {"x": int(round(p2[0])), "y": int(round(p2[1]))},
    }


def _compute_planning_sector_lines(
    x: np.ndarray,
    top: np.ndarray,
    bottom: np.ndarray,
    image_height: int,
) -> list[dict]:
    """
    Compute planning sector lines to match the viewer layout:
      - left outer wall
      - left arm of central inverted-V
      - right arm of central inverted-V
      - right outer wall

    This intentionally avoids the trapezoid/bottom-connector look.
    """
    if len(x) < 12:
        return []

    lines: list[dict] = []
    H = image_height
    arch_w = float(x[-1] - x[0])

    # Anchor along the superior arch, avoiding noisy extremes.
    i_left_outer = max(0, int(len(x) * 0.04))
    i_left_inner = max(i_left_outer + 1, int(len(x) * 0.22))
    i_right_inner = min(len(x) - 2, int(len(x) * 0.78))
    i_right_outer = min(len(x) - 1, int(len(x) * 0.96))
    i_mid = len(x) // 2

    left_outer = (int(x[i_left_outer]), int(top[i_left_outer]))
    left_inner = (int(x[i_left_inner]), int(top[i_left_inner]))
    right_inner = (int(x[i_right_inner]), int(top[i_right_inner]))
    right_outer = (int(x[i_right_outer]), int(top[i_right_outer]))

    # Lower support points near the mandibular base, left and right.
    left_base_y = float(bottom[i_left_inner]) + max(10.0, (bottom[i_left_inner] - top[i_left_inner]) * 0.18)
    right_base_y = float(bottom[i_right_inner]) + max(10.0, (bottom[i_right_inner] - top[i_right_inner]) * 0.18)
    left_base = (
        int(x[i_left_inner] - arch_w * 0.06),
        int(np.clip(left_base_y, 0, H - 1)),
    )
    right_base = (
        int(x[i_right_inner] + arch_w * 0.06),
        int(np.clip(right_base_y, 0, H - 1)),
    )

    # Central apex of the inverted V, below the crest but above the lower image edge.
    support_y = min(left_base[1], right_base[1])
    mid_top = float(top[i_mid])
    apex_y = mid_top + (support_y - mid_top) * 0.42
    apex = (
        int(x[i_mid]),
        int(np.clip(apex_y, 0, H - 1)),
    )

    lines.append({"start": {"x": left_outer[0], "y": left_outer[1]}, "end": {"x": left_base[0], "y": left_base[1]}})
    lines.append({"start": {"x": left_base[0], "y": left_base[1]}, "end": {"x": apex[0], "y": apex[1]}})
    lines.append({"start": {"x": apex[0], "y": apex[1]}, "end": {"x": right_base[0], "y": right_base[1]}})
    lines.append({"start": {"x": right_base[0], "y": right_base[1]}, "end": {"x": right_outer[0], "y": right_outer[1]}})

    return lines


def build_planning_overlay(
    volume: np.ndarray,
    bone_mask: np.ndarray,
    bone_metrics: dict,
) -> dict:
    """
    Build the planning-style overlay for a 2-D axial mandible slice.

    Returns:
      {
                outer_contour: [...],
                inner_contour: [...],
                base_guide: [...],
                width_indicator: {start,end} | None,
                sector_lines: [],
      }
    """
    n_slices = volume.shape[0]
    _empty = {
        "outer_contour": [],
        "inner_contour": [],
        "base_guide": [],
        "width_indicator": None,
        "sector_lines": [],
    }
    if n_slices > 3:
        return _empty

    mid_slice = n_slices // 2
    axial_bone = bone_mask[mid_slice]
    profiles = _extract_2d_mandible_profiles(axial_bone)
    if profiles is None:
        return _empty

    x, top, bottom = profiles
    preview_top, preview_mid, preview_bottom = _expand_preview_profiles(
        top,
        bottom,
        image_height=axial_bone.shape[0],
    )

    # Outer contour = left lower side -> superior contour -> right lower side.
    outer_x = np.concatenate(([x[0]], x, [x[-1]]))
    outer_y = np.concatenate(([preview_bottom[0]], preview_top, [preview_bottom[-1]]))

    inner_points = _profile_points(x, preview_bottom, step_target=120)
    outer_points = _profile_points(outer_x, outer_y, step_target=140)

    # Central guide through the arch middle section.
    guide_trim = max(2, int(len(x) * 0.08))
    if len(x) > guide_trim * 2 + 2:
        guide_x = x[guide_trim:-guide_trim]
        guide_y = preview_mid[guide_trim:-guide_trim]
    else:
        guide_x = x
        guide_y = preview_mid
    base_guide = _profile_points(guide_x, guide_y, step_target=110)

    measure_x = int(bone_metrics.get("measurement_location", {}).get("x", int((x[0] + x[-1]) / 2)))
    width_indicator = _make_width_indicator(x, preview_top, preview_bottom, measure_x)

    return {
        "outer_contour": outer_points,
        "inner_contour": inner_points,
        "base_guide": base_guide,
        "width_indicator": width_indicator,
        "sector_lines": [],
    }


def _estimate_local_safe_height(top_y: float, bottom_y: float) -> float:
    return max(0.0, float(bottom_y - top_y) - 2.0)


def select_default_measurement_site(
    volume: np.ndarray,
    bone_mask: np.ndarray,
    nerve_mask: np.ndarray,
) -> dict | None:
    """
    Pick a sensible automatic planning site on 2-D axial scans.

    Strategy:
      - use mandibular profiles
      - avoid the anterior midline (often thin)
      - prefer posterior/body regions with the best local safe height
      - return an image-space point {x, y}
    """
    n_slices = volume.shape[0]
    if n_slices > 3:
        return None

    mid_slice = n_slices // 2
    axial_bone = bone_mask[mid_slice]
    axial_nerve = nerve_mask[mid_slice]
    profiles = _extract_2d_mandible_profiles(axial_bone)
    if profiles is None:
        return None

    x, top, bottom = profiles
    if len(x) < 20:
        return None

    # Exclude the central anterior 25% and the extreme edges.
    x_min = float(x.min())
    x_max = float(x.max())
    width = x_max - x_min
    left_band = x <= x_min + 0.35 * width
    right_band = x >= x_max - 0.35 * width
    lateral_mask = left_band | right_band

    candidate_indices = np.where(lateral_mask)[0]
    if len(candidate_indices) == 0:
        candidate_indices = np.arange(len(x))

    best_idx = None
    best_score = -1.0
    for idx in candidate_indices:
        local_top = float(top[idx])
        local_bottom = float(bottom[idx])
        local_width = max(0.0, local_bottom - local_top)

        nerve_rows = np.where(axial_nerve[:, int(x[idx])])[0]
        if len(nerve_rows) >= 1:
            local_height = max(0.0, float(nerve_rows.min()) - local_top)
        else:
            local_height = local_width
        local_safe = max(0.0, local_height - 2.0)

        # Prefer good safe height first, then width, then being away from the centre.
        center_bias = abs(float(x[idx]) - (x_min + x_max) / 2.0)
        score = 3.0 * local_safe + 1.5 * local_width + 0.02 * center_bias
        if score > best_score:
            best_score = score
            best_idx = int(idx)

    if best_idx is None:
        return None

    return {
        "x": int(x[best_idx]),
        "y": int(round((top[best_idx] + bottom[best_idx]) / 2.0)),
    }
