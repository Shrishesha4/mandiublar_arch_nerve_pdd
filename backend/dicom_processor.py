"""
DICOM Processor
----------------
Handles loading CBCT DICOM volumes, generating OPG projections,
detecting the dental arch, and computing bone measurements.
"""

from __future__ import annotations

import io
import base64
import re
import shutil
import tempfile
import zipfile
from pathlib import Path
from typing import BinaryIO

import numpy as np
import pydicom
import SimpleITK as sitk
from scipy import ndimage
from scipy import signal
from scipy.interpolate import UnivariateSpline
from skimage.feature import canny, hessian_matrix, hessian_matrix_eigvals
from skimage.filters import frangi
from skimage.morphology import skeletonize, remove_small_objects
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
    modality = str(getattr(ds, "Modality", "")).upper()
    # HU is meaningful for CT-like volumes; avoid treating panoramic grayscale as HU.
    is_calibrated_hu = modality == "CT"

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
        "modality": modality or "UNKNOWN",
        "is_calibrated_hu": is_calibrated_hu,
    }

    Path(tmp_path).unlink(missing_ok=True)
    return volume, metadata


def load_cbct_series(input_path: str | Path) -> tuple[np.ndarray, dict]:
    """
    Load a CBCT study from a folder of DICOM slices or a ZIP archive.

    Returns
    -------
    volume : np.ndarray
        3D array with shape (num_slices, rows, cols)
    metadata : dict
        Same schema used by load_dicom_volume for downstream compatibility.
    """
    src_path = Path(input_path)
    if not src_path.exists():
        raise FileNotFoundError(f"CBCT input path does not exist: {src_path}")

    if src_path.is_dir():
        return _load_cbct_series_from_dir(src_path)

    if src_path.is_file() and src_path.suffix.lower() == ".zip":
        with src_path.open("rb") as fp:
            return load_cbct_zip(fp)

    raise ValueError("CBCT input must be a folder or a .zip archive.")


def load_cbct_zip(zip_source: bytes | BinaryIO) -> tuple[np.ndarray, dict]:
    """
    Load a CBCT series from ZIP payload containing many DICOM slices.

    Supports either raw ZIP bytes or an already-open binary stream.
    """
    temp_dir = Path(tempfile.mkdtemp(prefix="cbct_zip_"))
    extract_root = temp_dir / "extracted"
    extract_root.mkdir(parents=True, exist_ok=True)

    try:
        zip_stream: BinaryIO
        if isinstance(zip_source, (bytes, bytearray)):
            if not zip_source:
                raise ValueError("Empty ZIP payload received for CBCT series.")
            zip_stream = io.BytesIO(zip_source)
        else:
            zip_stream = zip_source

        if hasattr(zip_stream, "seek"):
            zip_stream.seek(0)

        with zipfile.ZipFile(zip_stream, "r") as zf:
            _safe_extract_zip(zf, extract_root)
        print("Detected ZIP CBCT upload")
        return _load_cbct_series_from_dir(extract_root)
    except zipfile.BadZipFile as exc:
        raise ValueError("Uploaded ZIP is invalid or corrupted.") from exc
    finally:
        shutil.rmtree(temp_dir, ignore_errors=True)


def _safe_extract_zip(zf: zipfile.ZipFile, extract_root: Path) -> None:
    """Safely extract archive entries while preventing path traversal."""
    root_resolved = extract_root.resolve()
    for member in zf.infolist():
        member_name = member.filename
        if not member_name or member_name.endswith("/"):
            continue

        member_path = Path(member_name)
        if member_path.is_absolute() or any(part == ".." for part in member_path.parts):
            raise ValueError(f"Unsafe ZIP entry path detected: {member_name}")
        if _is_hidden_or_system_path(member_path):
            continue
        if member_path.suffix.lower() != ".dcm":
            continue

        target_path = (extract_root / member_path).resolve()
        if root_resolved not in target_path.parents and target_path != root_resolved:
            raise ValueError(f"Unsafe ZIP entry path detected: {member_name}")

        target_path.parent.mkdir(parents=True, exist_ok=True)
        with zf.open(member, "r") as src, open(target_path, "wb") as dst:
            shutil.copyfileobj(src, dst)


def _is_hidden_or_system_path(path: Path) -> bool:
    return any(part.startswith(".") or part == "__MACOSX" for part in path.parts)


def _load_cbct_series_from_dir(root: Path) -> tuple[np.ndarray, dict]:
    dicom_files = sorted(
        [
            p
            for p in root.rglob("*")
            if p.is_file() and p.suffix.lower() == ".dcm" and not _is_hidden_or_system_path(p.relative_to(root))
        ]
    )
    if not dicom_files:
        raise ValueError("No .dcm files found in CBCT study input.")
    print("DICOM files found:", len(dicom_files))

    slices = []
    rows = None
    cols = None
    fallback_used = False

    for fallback_idx, file_path in enumerate(dicom_files):
        print("Reading DICOM:", str(file_path))
        try:
            ds = pydicom.dcmread(str(file_path), force=True)
        except Exception:
            continue

        try:
            arr = ds.pixel_array
        except Exception as e:
            print("Pixel decode failed:", e)
            continue

        # Enhanced/multi-frame CBCT may store the full volume in one file.
        n_frames = int(getattr(ds, "NumberOfFrames", 1) or 1)
        if n_frames > 1 and arr.ndim >= 3:
            print("Pixel array shape:", arr.shape)
            volume = arr.astype(np.float64)
            slope = float(getattr(ds, "RescaleSlope", 1))
            intercept = float(getattr(ds, "RescaleIntercept", 0))
            volume = volume * slope + intercept

            if volume.ndim == 4:
                # If channels are present, collapse to grayscale to keep (Z, H, W).
                volume = np.mean(volume, axis=-1)
            if volume.ndim != 3:
                raise ValueError(f"Unsupported multi-frame CBCT shape: {volume.shape}")

            modality = str(getattr(ds, "Modality", "")).upper() or "UNKNOWN"
            metadata = {
                "pixel_spacing": _get_pixel_spacing(ds),
                "slice_thickness": float(getattr(ds, "SliceThickness", 1.0)),
                "patient_name": str(getattr(ds, "PatientName", "Unknown")),
                "rows": int(volume.shape[1]),
                "columns": int(volume.shape[2]),
                "num_slices": int(volume.shape[0]),
                "modality": modality,
                "is_calibrated_hu": modality == "CT",
            }
            return volume, metadata

        arr = arr.astype(np.float64)
        print("Pixel array shape:", arr.shape)
        if arr.ndim != 2:
            continue

        slope = float(getattr(ds, "RescaleSlope", 1))
        intercept = float(getattr(ds, "RescaleIntercept", 0))
        arr = arr * slope + intercept

        if rows is None:
            rows, cols = arr.shape
        if arr.shape != (rows, cols):
            continue

        z_pos = None
        if hasattr(ds, "ImagePositionPatient") and len(ds.ImagePositionPatient) >= 3:
            try:
                z_pos = float(ds.ImagePositionPatient[2])
            except Exception:
                z_pos = None

        instance_number = None
        if hasattr(ds, "InstanceNumber"):
            try:
                instance_number = int(ds.InstanceNumber)
            except Exception:
                instance_number = None

        name_numbers = re.findall(r"\d+", file_path.name)
        file_number = int(name_numbers[-1]) if name_numbers else fallback_idx

        slices.append(
            {
                "arr": arr,
                "ds": ds,
                "z": z_pos,
                "instance": instance_number,
                "file_number": file_number,
                "file_name": file_path.name,
            }
        )

    if not slices:
        print("No slices decoded -- trying SimpleITK fallback")
        fallback_used = True
        sitk_volume, sitk_ds = _load_cbct_with_sitk_fallback(root)
        if sitk_volume is not None and sitk_ds is not None:
            modality = str(getattr(sitk_ds, "Modality", "")).upper() or "UNKNOWN"
            metadata = {
                "pixel_spacing": _get_pixel_spacing(sitk_ds),
                "slice_thickness": float(getattr(sitk_ds, "SliceThickness", 1.0)),
                "patient_name": str(getattr(sitk_ds, "PatientName", "Unknown")),
                "rows": int(sitk_volume.shape[1]),
                "columns": int(sitk_volume.shape[2]),
                "num_slices": int(sitk_volume.shape[0]),
                "modality": modality,
                "is_calibrated_hu": modality == "CT",
            }
            print("Fallback used:", fallback_used)
            return sitk_volume, metadata
        raise ValueError("No readable 2D DICOM slices found in CBCT study input.")

    if len(slices) < 5:
        raise ValueError("Not enough valid CBCT slices")

    print("Slices decoded with pydicom:", len(slices))
    print("Fallback used:", fallback_used)

    has_all_z = all(s["z"] is not None for s in slices)
    has_all_instance = all(s["instance"] is not None for s in slices)
    if has_all_z:
        slices.sort(key=lambda s: float(s["z"]))
    elif has_all_instance:
        slices.sort(key=lambda s: int(s["instance"]))
    else:
        slices.sort(key=lambda s: int(s["file_number"]))

    if slices[0]["z"] is not None and slices[-1]["z"] is not None:
        print("First slice Z:", slices[0]["z"])
        print("Last slice Z:", slices[-1]["z"])

    volume = np.stack([s["arr"] for s in slices], axis=0)

    if volume.shape[0] > 600:
        volume = volume[::2]

    first_ds = slices[0]["ds"]
    pixel_spacing = _get_pixel_spacing(first_ds)
    slice_thickness = float(getattr(first_ds, "SliceThickness", 1.0))

    z_positions = [s["z"] for s in slices if s["z"] is not None]
    if len(z_positions) >= 2:
        z_sorted = np.asarray(sorted(z_positions), dtype=np.float64)
        diffs = np.abs(np.diff(z_sorted))
        diffs = diffs[diffs > 1e-6]
        if len(diffs) > 0:
            slice_thickness = float(np.median(diffs))

    if len(slices) > 600:
        slice_thickness *= 2.0

    modality = str(getattr(first_ds, "Modality", "")).upper() or "UNKNOWN"
    metadata = {
        "pixel_spacing": pixel_spacing,
        "slice_thickness": slice_thickness,
        "patient_name": str(getattr(first_ds, "PatientName", "Unknown")),
        "rows": int(volume.shape[1]),
        "columns": int(volume.shape[2]),
        "num_slices": int(volume.shape[0]),
        "modality": modality,
        "is_calibrated_hu": modality == "CT",
    }
    return volume, metadata


def _load_cbct_with_sitk_fallback(root: Path) -> tuple[np.ndarray | None, object | None]:
    dicom_files = sorted(
        [
            p
            for p in root.rglob("*")
            if p.is_file() and p.suffix.lower() == ".dcm" and not _is_hidden_or_system_path(p.relative_to(root))
        ]
    )
    if not dicom_files:
        return None, None

    try:
        series_reader = sitk.ImageSeriesReader()
        series_ids = series_reader.GetGDCMSeriesIDs(str(root)) or []
        if series_ids:
            series_files = series_reader.GetGDCMSeriesFileNames(str(root), series_ids[0])
            if series_files:
                series_reader.SetFileNames(list(series_files))
                image = series_reader.Execute()
                volume = sitk.GetArrayFromImage(image).astype(np.float64)
                if volume.ndim == 2:
                    volume = volume[np.newaxis, :, :]
                if volume.ndim == 3:
                    ds = pydicom.dcmread(str(series_files[0]), force=True)
                    return volume, ds
    except Exception:
        pass

    try:
        image = sitk.ReadImage(str(dicom_files[0]))
        volume = sitk.GetArrayFromImage(image).astype(np.float64)
        if volume.ndim == 2:
            volume = volume[np.newaxis, :, :]
        if volume.ndim == 3:
            ds = pydicom.dcmread(str(dicom_files[0]), force=True)
            return volume, ds
    except Exception:
        return None, None

    return None, None


def load_image_projection(image_bytes: bytes) -> tuple[np.ndarray, dict]:
    """
    Load a 2-D panoramic image (png/jpeg) and map it to a pseudo-volume
    shape of (1, H, W) so downstream processing can reuse the same pipeline.
    """
    img = Image.open(io.BytesIO(image_bytes)).convert("L")
    arr = np.asarray(img, dtype=np.float64)
    volume = arr[np.newaxis, :, :]
    metadata = {
        "pixel_spacing": [1.0, 1.0],
        "slice_thickness": 1.0,
        "patient_name": "Unknown",
        "rows": int(arr.shape[0]),
        "columns": int(arr.shape[1]),
        "num_slices": 1,
        "modality": "OT",
        "is_calibrated_hu": False,
    }
    return volume, metadata


def _get_pixel_spacing(ds) -> list[float]:
    """Extract pixel spacing from various DICOM tags."""
    if hasattr(ds, "PixelSpacing"):
        return [float(ds.PixelSpacing[0]), float(ds.PixelSpacing[1])]
    if hasattr(ds, "ImagerPixelSpacing"):
        return [float(ds.ImagerPixelSpacing[0]), float(ds.ImagerPixelSpacing[1])]
    return [1.0, 1.0]


def build_cbct_panoramic_proxy(
    volume: np.ndarray,
    metadata: dict,
) -> tuple[np.ndarray, dict, np.ndarray, list[dict]]:
    """
    Convert a volumetric CBCT stack into a stable panoramic-like 2D proxy for
    ridge analysis.

    Returns
    -------
    proxy_volume : np.ndarray
        Shape (1, H, W), float64 in [0, 255].
    proxy_metadata : dict
        Metadata aligned to proxy_volume.
    bone_mask_2d : np.ndarray
        Binary mask of dominant mandibular component in proxy image.
    ridge_points : list[dict]
        Smoothed alveolar ridge polyline points as [{"x": int, "y": int}, ...].
    """
    if volume.ndim != 3 or volume.shape[0] <= 1:
        raise ValueError("CBCT panoramic proxy requires volumetric input (Z, Y, X).")

    projection = np.max(volume, axis=0).astype(np.float64)
    projection = ndimage.gaussian_filter(projection, sigma=2.0)

    threshold = float(np.percentile(projection, 75))
    bone_mask = projection > threshold
    bone_mask = ndimage.binary_closing(bone_mask, iterations=2)
    bone_mask = ndimage.binary_fill_holes(bone_mask)
    bone_mask = remove_small_objects(bone_mask.astype(bool), min_size=500)

    labelled, n_comp = ndimage.label(bone_mask)
    if n_comp > 0:
        sizes = ndimage.sum(bone_mask, labelled, index=range(1, n_comp + 1))
        best_idx = int(np.argmax(np.asarray(sizes, dtype=np.float64))) + 1
        bone_mask = labelled == best_idx
    else:
        bone_mask = np.zeros_like(bone_mask, dtype=bool)

    H, W = projection.shape
    ridge = np.full(W, np.nan, dtype=np.float64)
    for x in range(W):
        rows = np.where(bone_mask[:, x])[0]
        if len(rows) > 0:
            ridge[x] = float(rows.min())

    valid_x = np.where(~np.isnan(ridge))[0]
    ridge_points: list[dict] = []
    if len(valid_x) >= 6:
        ridge_interp = np.interp(np.arange(W), valid_x, ridge[valid_x])
        spline = UnivariateSpline(np.arange(W), ridge_interp, s=50.0)
        sample_x = np.linspace(0, W - 1, 150)
        sample_y = np.clip(spline(sample_x), 0, H - 1)
        ridge_points = [
            {"x": int(round(x)), "y": int(round(y))}
            for x, y in zip(sample_x, sample_y)
        ]

    p1 = float(np.percentile(projection, 1))
    p99 = float(np.percentile(projection, 99))
    if p99 <= p1:
        projection_u8 = np.zeros_like(projection, dtype=np.uint8)
    else:
        projection = np.clip(projection, p1, p99)
        projection_u8 = ((projection - p1) / (p99 - p1) * 255.0).astype(np.uint8)

    proxy_volume = projection_u8[np.newaxis, :, :].astype(np.float64)
    proxy_metadata = dict(metadata)
    proxy_metadata["rows"] = int(proxy_volume.shape[1])
    proxy_metadata["columns"] = int(proxy_volume.shape[2])
    proxy_metadata["num_slices"] = 1
    proxy_metadata["slice_thickness"] = 1.0
    proxy_metadata["is_calibrated_hu"] = False
    return proxy_volume, proxy_metadata, bone_mask.astype(bool), ridge_points


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


def _select_mandibular_axial_slice(volume: np.ndarray) -> tuple[int, np.ndarray]:
    """
    Select the axial level with the strongest mandibular bone signal.

    Only searches the middle 50% of slices (25%–75%) to avoid skull cap
    and neck regions that would confuse detection. Uses adaptive
    percentile thresholding that works across scanner vendors.
    """
    n = int(volume.shape[0])
    if n <= 1:
        return 0, volume[0].astype(np.float64)

    start = int(n * 0.25)
    end = int(n * 0.75)

    scores: list[float] = []
    for z in range(start, end):
        img = volume[z].astype(np.float64)
        low = float(np.percentile(img, 70))
        high = float(np.percentile(img, 90))
        thresh = low + 0.6 * (high - low)
        bone = img > thresh
        scores.append(float(bone.sum()))

    best_local = int(np.argmax(np.asarray(scores, dtype=np.float64)))
    best_slice = start + best_local

    z0 = max(0, best_slice - 2)
    z1 = min(n, best_slice + 3)
    axial = np.mean(volume[z0:z1].astype(np.float64), axis=0)
    return best_slice, axial


def detect_mandible_3d(volume: np.ndarray) -> np.ndarray:
    """
    Isolate the mandible from skull, spine, and soft tissue in a 3D
    CBCT volume using connected-component analysis.

    Returns a boolean mask of the largest bony structure (mandible).
    """
    low = float(np.percentile(volume, 70))
    high = float(np.percentile(volume, 90))
    thresh = low + 0.6 * (high - low)

    bone = volume > thresh
    bone = ndimage.binary_closing(bone, iterations=2)

    labels, n_comp = ndimage.label(bone)
    if n_comp == 0:
        return bone.astype(bool)

    sizes = ndimage.sum(bone, labels, range(1, n_comp + 1))
    largest = int(np.argmax(np.asarray(sizes, dtype=np.float64))) + 1
    mandible = labels == largest
    return mandible.astype(bool)


def extract_mandibular_arch(axial: np.ndarray) -> np.ndarray | None:
    """
    Professional mandibular arch extraction.

    1) Normalize and threshold bone
    2) Morphological cleanup + largest component
    3) Extract ridge using median of top-6 bone pixels per column
    4) Interpolate gaps, smooth, and fit spline
    5) Return (200, 2) array of (y, x) arch coordinates

    Returns None if arch cannot be reliably detected.
    """
    img = axial.astype(np.float32)
    img -= img.min()
    img /= (img.max() + 1e-6)

    low = float(np.percentile(img, 70))
    high = float(np.percentile(img, 90))
    thresh = low + 0.55 * (high - low)

    bone = img > thresh
    bone = ndimage.binary_closing(bone, iterations=3)
    bone = ndimage.binary_fill_holes(bone)

    labels, n_comp = ndimage.label(bone)
    if n_comp > 1:
        sizes = ndimage.sum(bone, labels, range(1, n_comp + 1))
        bone = (labels == (int(np.argmax(np.asarray(sizes, dtype=np.float64))) + 1))
    elif n_comp == 0:
        return None

    H, W = bone.shape
    ridge = np.full(W, np.nan)

    for x in range(W):
        rows = np.where(bone[:, x])[0]
        if len(rows) > 6:
            ridge[x] = float(np.median(rows[:6]))

    valid = np.where(~np.isnan(ridge))[0]
    if len(valid) < 20:
        return None

    ridge = np.interp(np.arange(W), valid, ridge[valid])
    ridge = ndimage.gaussian_filter1d(ridge, 4)

    spline = UnivariateSpline(np.arange(W), ridge, s=50)
    xs = np.linspace(0, W - 1, 200)
    ys = spline(xs)
    ys = np.clip(ys, 0, H - 1)

    arch = np.stack([ys, xs], axis=1)
    return arch


def detect_canal_hessian(volume: np.ndarray) -> np.ndarray:
    """
    Detect the mandibular canal using Hessian-based dark-tube detection.

    Superior to Frangi for canal detection because it explicitly targets
    the radiolucent tubular structure of the canal.

    Returns (N, 2) array of (row, col) canal coordinates.
    """
    if volume.ndim == 3:
        mid = volume[volume.shape[0] // 2]
    else:
        mid = volume

    img = mid.astype(np.float32)
    img -= img.min()
    img /= (img.max() + 1e-6)

    # Canal is radiolucent (dark) — invert
    dark = 1.0 - img

    # Bandpass filter to isolate tubular structures
    low = ndimage.gaussian_filter(dark, 1)
    high = ndimage.gaussian_filter(dark, 4)
    band = np.clip(low - high, 0, 1)

    # Hessian eigenvalue analysis for tubular structures
    Hmatrix = hessian_matrix(band, sigma=2, order="xy", use_gaussian_derivatives=False)
    eig1, eig2 = hessian_matrix_eigvals(Hmatrix)

    # Tubular structures have one large eigenvalue
    tube = np.abs(eig2)
    thresh = float(np.percentile(tube, 90))
    canal = tube > thresh

    canal = skeletonize(canal)
    coords = np.argwhere(canal)
    return coords


def generate_cbct_cpr(
    volume: np.ndarray, metadata: dict
) -> tuple[str, list[dict]]:
    """
    Curved Planar Reconstruction (CPR) for CBCT implant planning.

    Pipeline:
      1) 3D mandible isolation (remove skull/neck)
      2) Auto-select mandibular axial slice
      3) Professional arch extraction (median-of-top-6 ridge)
      4) Perpendicular cross-sections via 3D map_coordinates
      5) Stack into panoramic reconstruction
      6) Return arch overlay points for frontend

    Returns:
        (cpr_base64, arch_points)
        cpr_base64 : base64-encoded PNG of the panoramic CPR view
        arch_points: list of {"x": int, "y": int} for frontend overlay
    """
    n_slices, H, W = volume.shape

    # ── Step 1: 3D mandible isolation ────────────────────────────────────
    mandible_3d = detect_mandible_3d(volume)
    # Apply mandible mask to volume to remove skull interference
    masked_volume = volume.copy()
    masked_volume[~mandible_3d] = 0
    print("3D mandible mask voxels:", int(mandible_3d.sum()))

    # ── Step 2: Auto-select mandibular slice ─────────────────────────────
    best_idx, axial = _select_mandibular_axial_slice(masked_volume)
    print("Selected mandibular slice index:", best_idx)

    # ── Step 3: Professional arch extraction ─────────────────────────────
    arch_result = extract_mandibular_arch(axial)

    if arch_result is not None:
        # arch_result is (200, 2) array of (y, x) coordinates
        ys_arch = arch_result[:, 0]
        xs_arch = arch_result[:, 1]
        num_arch_points = len(xs_arch)
        x_sampled = xs_arch
        y_sampled = ys_arch
        print("Professional arch extraction: %d points" % num_arch_points)
    else:
        # Fallback: old method using _segment_mandible_2d
        print("Arch extraction fallback: using _segment_mandible_2d")
        mandible_mask = _segment_mandible_2d(axial)
        if not mandible_mask.any():
            threshold = np.percentile(axial, 75)
            mandible_mask = axial > threshold
            mandible_mask = ndimage.binary_closing(mandible_mask, iterations=3)
            mandible_mask = ndimage.binary_fill_holes(mandible_mask)

        arch_y_raw = {}
        cols_with_bone = np.where(mandible_mask.any(axis=0))[0]
        for x in cols_with_bone:
            rows = np.where(mandible_mask[:, x])[0]
            if len(rows) > 0:
                arch_y_raw[int(x)] = int(rows.min())

        if len(arch_y_raw) < 20:
            return _coronal_mip_fallback(volume, H, W), []

        x_coords = np.array(sorted(arch_y_raw.keys()), dtype=np.float64)
        y_coords = np.array([arch_y_raw[int(x)] for x in x_coords], dtype=np.float64)

        try:
            spline = UnivariateSpline(x_coords, y_coords, s=len(x_coords) * 2, k=3)
        except Exception:
            return _coronal_mip_fallback(volume, H, W), []

        num_arch_points = 200
        x_min, x_max = float(x_coords.min()), float(x_coords.max())
        x_sampled = np.linspace(x_min, x_max, num_arch_points)
        y_sampled = spline(x_sampled)
        y_sampled = np.clip(y_sampled, 0, H - 1)

    # Build arch overlay points for frontend
    arch_points = [
        {"x": int(round(x)), "y": int(round(y))}
        for x, y in zip(x_sampled, y_sampled)
    ]

    # ── Step 5: Generate perpendicular cross-sections ────────────────────
    # Volume axes: volume[z, y, x]
    #
    # For each arch point (px, py), compute initial direction and sample
    # a perpendicular line through the volume using 3D map_coordinates.
    #
    # Coordinate grids are built as 2D arrays of shape (Z, width) so that
    # map_coordinates returns a (Z, width) cross-section directly.
    cross_section_width = 200
    t = np.linspace(-cross_section_width / 2, cross_section_width / 2,
                     cross_section_width)

    # Tangent vectors via finite differences
    dx = np.gradient(x_sampled)
    dy = np.gradient(y_sampled)

    # Normalize tangent
    tlen = np.sqrt(dx ** 2 + dy ** 2) + 1e-8
    tx = dx / tlen
    ty = dy / tlen

    # Perpendicular normal: rotate tangent 90°
    norm_x = -ty
    norm_y = tx

    # Z indices (shared across all arch points)
    z = np.arange(n_slices, dtype=np.float64)

    # Convert volume to float once
    vol_f = volume.astype(np.float64)

    columns = []
    for i in range(num_arch_points):
        px, py = x_sampled[i], y_sampled[i]

        # Sampling line along the normal in the XY plane
        xs = np.clip(px + norm_x[i] * t, 0, W - 1)   # (width,)
        ys = np.clip(py + norm_y[i] * t, 0, H - 1)   # (width,)

        # Build 2D coordinate grids of shape (Z, width)
        z_grid = np.repeat(z[:, None], cross_section_width, axis=1)
        y_grid = np.repeat(ys[None, :], n_slices, axis=0)
        x_grid = np.repeat(xs[None, :], n_slices, axis=0)

        # Sample the volume: coords=[z, y, x], result shape = (Z, width)
        section = ndimage.map_coordinates(
            vol_f, [z_grid, y_grid, x_grid], order=1, mode="nearest"
        )

        # Collapse slices (Z axis) using maximum intensity projection
        # Result: (width,) — one panoramic column for this arch position
        column = np.max(section, axis=0)
        columns.append(column)

    # ── Step 6: Build final CPR image ────────────────────────────────────
    # Stack columns: (num_arch_points, width) e.g. (200, 200)
    cpr_image = np.stack(columns)

    # Rotate 90° for dental software orientation
    cpr_image = np.rot90(cpr_image)

    # Percentile contrast normalization
    p_low = np.percentile(cpr_image, 1)
    p_high = np.percentile(cpr_image, 99)
    if p_high <= p_low:
        p_low, p_high = float(cpr_image.min()), float(cpr_image.max())

    cpr_clipped = np.clip(cpr_image, p_low, p_high)
    if p_high > p_low:
        cpr_norm = ((cpr_clipped - p_low) / (p_high - p_low) * 255).astype(np.uint8)
    else:
        cpr_norm = np.zeros_like(cpr_image, dtype=np.uint8)

    # Encode to base64 PNG
    img = Image.fromarray(cpr_norm, mode="L")
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    cpr_b64 = base64.b64encode(buf.getvalue()).decode("utf-8")

    return cpr_b64, arch_points


def _coronal_mip_fallback(volume: np.ndarray, H: int, W: int) -> str:
    """Fallback coronal MIP when arch detection fails."""
    preview = np.max(volume, axis=0).astype(np.float64)
    p_low = np.percentile(preview, 1)
    p_high = np.percentile(preview, 99)
    if p_high <= p_low:
        p_low, p_high = float(preview.min()), float(preview.max())
    preview_c = np.clip(preview, p_low, p_high)
    if p_high > p_low:
        opg_norm = ((preview_c - p_low) / (p_high - p_low) * 255).astype(np.uint8)
    else:
        opg_norm = np.zeros((H, W), dtype=np.uint8)
    img = Image.fromarray(opg_norm, mode="L")
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return base64.b64encode(buf.getvalue()).decode("utf-8")


# Global cache for arch points from the last CPR generation
_last_cpr_arch_points: list[dict] = []


def generate_opg(volume: np.ndarray, metadata: dict) -> str:
    """
    Generate an OPG / panoramic radiograph image from the volume.

    • Single-slice (2-D) DICOM  → render the slice directly with
      aggressive percentile contrast stretch so anatomy is clearly visible.
    • Multi-slice (3-D) CBCT    → Curved Planar Reconstruction (CPR).

    Returns a base64-encoded PNG string.
    """
    global _last_cpr_arch_points
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

        _last_cpr_arch_points = []
        img = Image.fromarray(opg_norm, mode="L")
        buf = io.BytesIO()
        img.save(buf, format="PNG")
        return base64.b64encode(buf.getvalue()).decode("utf-8")

    else:
        # ── 3-D path: Curved Planar Reconstruction ───────────────────────
        cpr_b64, arch_pts = generate_cbct_cpr(volume, metadata)
        _last_cpr_arch_points = arch_pts
        return cpr_b64


def get_last_cpr_arch_points() -> list[dict]:
    """Retrieve the arch points from the most recent CPR generation."""
    return _last_cpr_arch_points


def detect_mandibular_canal_path_2d(
    volume: np.ndarray,
    metadata: dict,
) -> list[dict]:
    """
    Detect mandibular canal centreline on panoramic-like 2D inputs.

    Pipeline:
      1) adaptive mandible segmentation
      2) inferior cortical border extraction
      3) radiolucent tubular candidate detection with Frangi
      4) skeleton longest-path extraction
      5) smoothing + subsampling
      6) anatomical validation + fallback parallel path
    """
    if volume.ndim != 3 or volume.shape[0] < 1:
        return []

    img = volume[volume.shape[0] // 2].astype(np.float64)
    H, W = img.shape

    mandible_mask = _segment_mandible_2d(img)
    if not mandible_mask.any():
        return []

    inferior_border = _inferior_border_curve(img, mandible_mask)
    if inferior_border is None:
        return []

    top_border = _superior_border_curve(mandible_mask)

    canal_candidate = _detect_canal_candidates(img, mandible_mask, inferior_border)
    path = _extract_longest_skeleton_path(canal_candidate)

    if path:
        path = _smooth_and_sample_path(path, target_points=100)

    if not _validate_canal_path(path, inferior_border, top_border):
        path = _fallback_parallel_path(inferior_border, top_border)

    return path


def _segment_mandible_2d(img: np.ndarray) -> np.ndarray:
    p70 = float(np.percentile(img, 70))
    p85 = float(np.percentile(img, 85))
    threshold = p70 + 0.55 * (p85 - p70)

    mask = img > threshold
    mask = ndimage.binary_closing(mask, iterations=3)
    mask = ndimage.binary_fill_holes(mask)

    labelled, n_comp = ndimage.label(mask)
    if n_comp == 0:
        return np.zeros_like(mask, dtype=bool)

    best_idx = 0
    best_score = -1.0
    H, W = mask.shape
    for idx in range(1, n_comp + 1):
        comp = labelled == idx
        area = float(comp.sum())
        if area < 300:
            continue
        rows, cols = np.where(comp)
        if len(rows) == 0:
            continue
        cy = float(rows.mean())
        width = float(cols.max() - cols.min() + 1)
        lower_bias = float((rows > H * 0.35).mean())
        score = area * (0.65 + lower_bias) + 0.12 * width + 0.03 * cy
        if score > best_score:
            best_score = score
            best_idx = idx

    if best_idx == 0:
        return np.zeros_like(mask, dtype=bool)

    comp = labelled == best_idx
    return ndimage.binary_fill_holes(comp)


def _inferior_border_curve(img: np.ndarray, mandible_mask: np.ndarray) -> np.ndarray | None:
    H, W = img.shape
    grad_v = np.abs(ndimage.sobel(img.astype(np.float64), axis=0))
    grad_v = grad_v / (grad_v.max() + 1e-6)

    edges = canny(grad_v, sigma=1.2)
    edges &= ndimage.binary_dilation(mandible_mask, iterations=1)

    cols = np.where(mandible_mask.any(axis=0))[0]
    if len(cols) < 25:
        return None

    border = np.full(W, np.nan, dtype=np.float64)
    for x in cols:
        mand_rows = np.where(mandible_mask[:, x])[0]
        if len(mand_rows) < 3:
            continue
        lower_limit = int(mand_rows.max())
        upper_limit = int(max(mand_rows.min(), lower_limit - max(18, len(mand_rows) // 2)))

        edge_rows = np.where(edges[:, x])[0]
        edge_rows = edge_rows[(edge_rows >= upper_limit) & (edge_rows <= lower_limit)]
        if len(edge_rows) > 0:
            border[x] = float(edge_rows.max())
        else:
            border[x] = float(lower_limit)

    valid = np.where(~np.isnan(border))[0]
    if len(valid) < 25:
        return None

    border = np.interp(np.arange(W), valid, border[valid])
    border = ndimage.gaussian_filter1d(border, sigma=3.0, mode="nearest")
    return np.clip(border, 0, H - 1)


def _superior_border_curve(mandible_mask: np.ndarray) -> np.ndarray:
    H, W = mandible_mask.shape
    top = np.full(W, np.nan, dtype=np.float64)
    cols = np.where(mandible_mask.any(axis=0))[0]
    for x in cols:
        rows = np.where(mandible_mask[:, x])[0]
        if len(rows) >= 2:
            top[x] = float(rows.min())

    valid = np.where(~np.isnan(top))[0]
    if len(valid) == 0:
        return np.zeros(W, dtype=np.float64)
    top = np.interp(np.arange(W), valid, top[valid])
    top = ndimage.gaussian_filter1d(top, sigma=2.0, mode="nearest")
    return np.clip(top, 0, H - 1)


def _detect_canal_candidates(
    img: np.ndarray,
    mandible_mask: np.ndarray,
    inferior_border: np.ndarray,
) -> np.ndarray:
    H, W = img.shape
    norm = img - float(img.min())
    norm = norm / (float(norm.max()) + 1e-6)

    # Radiolucent structures are darker; invert and band-pass for tubular texture.
    dark = 1.0 - norm
    low = ndimage.gaussian_filter(dark, sigma=1.0)
    high = ndimage.gaussian_filter(dark, sigma=4.0)
    band = np.clip(low - high, 0.0, 1.0)

    # Frangi response over expected canal calibre scale (approximately 3-6 px).
    vessel = frangi(band, sigmas=np.linspace(1.0, 3.0, 5), black_ridges=False)
    vessel = np.nan_to_num(vessel, nan=0.0, posinf=0.0, neginf=0.0)

    threshold = float(np.percentile(vessel[mandible_mask], 82)) if mandible_mask.any() else 0.0
    candidate = vessel > threshold
    candidate &= mandible_mask

    # Constrain to 10-25 px superior to inferior border.
    corridor = np.zeros_like(candidate, dtype=bool)
    for x in range(W):
        y_inf = int(round(inferior_border[x]))
        y_top = max(0, y_inf - 25)
        y_bottom = max(0, y_inf - 10)
        if y_bottom <= y_top:
            continue
        corridor[y_top:y_bottom + 1, x] = True

    candidate &= corridor

    # Remove components that touch near-inferior cortex zone.
    near_border_zone = np.zeros_like(candidate, dtype=bool)
    for x in range(W):
        y_inf = int(round(inferior_border[x]))
        near_border_zone[max(0, y_inf - 5):min(H, y_inf + 2), x] = True

    labelled, n_comp = ndimage.label(candidate)
    if n_comp == 0:
        return np.zeros_like(candidate, dtype=bool)

    cleaned = np.zeros_like(candidate, dtype=bool)
    for idx in range(1, n_comp + 1):
        comp = labelled == idx
        if comp.sum() < 20:
            continue
        if (comp & near_border_zone).any():
            continue
        cleaned |= comp

    cleaned = remove_small_objects(cleaned.astype(bool), min_size=30)
    return cleaned.astype(bool)


def _extract_longest_skeleton_path(mask: np.ndarray) -> list[dict]:
    if not mask.any():
        return []

    skeleton = skeletonize(mask).astype(bool)
    labelled, n_comp = ndimage.label(skeleton)
    if n_comp == 0:
        return []

    best_path: list[tuple[int, int]] = []
    for idx in range(1, n_comp + 1):
        comp = labelled == idx
        coords = np.argwhere(comp)
        if len(coords) < 18:
            continue

        nodes = {(int(r), int(c)) for r, c in coords}
        neighbors: dict[tuple[int, int], list[tuple[int, int]]] = {}
        for r, c in nodes:
            nn = []
            for dr in (-1, 0, 1):
                for dc in (-1, 0, 1):
                    if dr == 0 and dc == 0:
                        continue
                    cand = (r + dr, c + dc)
                    if cand in nodes:
                        nn.append(cand)
            neighbors[(r, c)] = nn

        endpoints = [k for k, v in neighbors.items() if len(v) == 1]
        seeds = endpoints if len(endpoints) >= 2 else list(nodes)
        if not seeds:
            continue

        start = seeds[0]
        far_a, _ = _bfs_farthest(start, neighbors)
        far_b, parent = _bfs_farthest(far_a, neighbors)
        path_nodes = _reconstruct_path(far_b, far_a, parent)

        if len(path_nodes) > len(best_path):
            best_path = path_nodes

    if len(best_path) < 18:
        return []

    # Convert row-col to x-y and left-right ordering.
    pts = [{"x": int(c), "y": int(r)} for r, c in best_path]
    pts = sorted(pts, key=lambda p: p["x"])
    return pts


def _bfs_farthest(
    start: tuple[int, int],
    neighbors: dict[tuple[int, int], list[tuple[int, int]]],
) -> tuple[tuple[int, int], dict[tuple[int, int], tuple[int, int] | None]]:
    from collections import deque

    q = deque([start])
    parent: dict[tuple[int, int], tuple[int, int] | None] = {start: None}
    dist = {start: 0}
    far = start

    while q:
        node = q.popleft()
        if dist[node] > dist[far]:
            far = node
        for nxt in neighbors.get(node, []):
            if nxt in parent:
                continue
            parent[nxt] = node
            dist[nxt] = dist[node] + 1
            q.append(nxt)
    return far, parent


def _reconstruct_path(
    end: tuple[int, int],
    start: tuple[int, int],
    parent: dict[tuple[int, int], tuple[int, int] | None],
) -> list[tuple[int, int]]:
    path = []
    cur = end
    while cur is not None:
        path.append(cur)
        if cur == start:
            break
        cur = parent.get(cur)
    path.reverse()
    return path


def _smooth_and_sample_path(path: list[dict], target_points: int = 100) -> list[dict]:
    if len(path) < 8:
        return path

    xs = np.asarray([p["x"] for p in path], dtype=np.float64)
    ys = np.asarray([p["y"] for p in path], dtype=np.float64)

    order = np.argsort(xs)
    xs = xs[order]
    ys = ys[order]

    x_unique = np.unique(xs)
    y_unique = np.interp(x_unique, xs, ys)
    if len(x_unique) < 7:
        return [{"x": int(round(x)), "y": int(round(y))} for x, y in zip(x_unique, y_unique)]

    window = min(25, max(15, (len(x_unique) // 4) | 1))
    if window >= len(x_unique):
        window = len(x_unique) - 1 if len(x_unique) % 2 == 0 else len(x_unique)
    if window < 5:
        window = 5
    if window % 2 == 0:
        window += 1

    y_smooth = signal.savgol_filter(y_unique, window_length=window, polyorder=3, mode="interp")

    n_out = int(np.clip(target_points, 80, 120))
    sample_x = np.linspace(x_unique.min(), x_unique.max(), n_out)
    sample_y = np.interp(sample_x, x_unique, y_smooth)

    return [{"x": int(round(x)), "y": int(round(y))} for x, y in zip(sample_x, sample_y)]


def _validate_canal_path(
    path: list[dict],
    inferior_border: np.ndarray,
    top_border: np.ndarray,
) -> bool:
    if len(path) < 20:
        return False

    W = len(inferior_border)
    xs = np.asarray([p["x"] for p in path], dtype=np.float64)
    ys = np.asarray([p["y"] for p in path], dtype=np.float64)

    xs_clip = np.clip(xs, 0, W - 1)
    y_inf = np.interp(xs_clip, np.arange(W), inferior_border)
    y_top = np.interp(xs_clip, np.arange(W), top_border)

    delta = y_inf - ys
    if np.any(delta < 5.0):
        return False

    # Canal should stay around lower-third band, not near root region.
    thickness = np.maximum(1.0, y_inf - y_top)
    rel = (ys - y_top) / thickness
    if float((rel < 0.38).mean()) > 0.2:
        return False

    if float(np.median(delta)) < 10.0 or float(np.median(delta)) > 25.0:
        return False

    # Excessive curvature check.
    if len(xs) > 10:
        ord_idx = np.argsort(xs)
        xs_ord = xs[ord_idx]
        ys_ord = ys[ord_idx]
        x_unique = np.unique(xs_ord)
        y_unique = np.interp(x_unique, xs_ord, ys_ord)
        if len(x_unique) > 9:
            dy = np.gradient(y_unique)
            ddy = np.gradient(dy)
            if float(np.max(np.abs(ddy))) > 1.8:
                return False

    return True


def _fallback_parallel_path(
    inferior_border: np.ndarray,
    top_border: np.ndarray,
) -> list[dict]:
    W = len(inferior_border)
    x0 = int(W * 0.10)
    x1 = int(W * 0.88)
    xs = np.linspace(x0, x1, 100)

    y_inf = np.interp(xs, np.arange(W), inferior_border)
    y_top = np.interp(xs, np.arange(W), top_border)
    thickness = np.maximum(1.0, y_inf - y_top)

    # Parallel model path offset 15-20 px and constrained to lower-third corridor.
    target_offset = np.clip(0.42 * thickness, 15.0, 20.0)
    ys = y_inf - target_offset
    ys = np.clip(ys, y_top + 0.35 * thickness, y_inf - 8.0)
    ys = ndimage.gaussian_filter1d(ys, sigma=2.0, mode="nearest")

    return [{"x": int(round(x)), "y": int(round(y))} for x, y in zip(xs, ys)]


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
    n_slices, H, W = volume.shape
    is_cbct = n_slices >= 20

    if tooth_coords is None:
        cx, cy = W // 2, H // 2
    else:
        cx = int(tooth_coords.get("x", W // 2))
        cy = int(tooth_coords.get("y", H // 2))
    cx = int(np.clip(cx, 0, W - 1))
    cy = int(np.clip(cy, 0, H - 1))

    SAFETY_MARGIN_MM = 2.0
    mid_slice = n_slices // 2
    axial_bone = bone_mask[mid_slice].astype(bool)
    axial_nerve = nerve_mask[mid_slice].astype(bool)
    axial_vol = volume[mid_slice].astype(np.float64)

    if is_cbct:
        rows, cols = np.where(axial_bone)
        if len(rows) >= 2 and len(cols) >= 2:
            row_min, row_max = int(rows.min()), int(rows.max())
            col_min, col_max = int(cols.min()), int(cols.max())
        else:
            row_min, row_max = int(H * 0.35), int(H * 0.75)
            col_min, col_max = int(W * 0.25), int(W * 0.75)

        center_row = int(np.clip((row_min + row_max) // 2, 0, H - 1))
        center_col = int(np.clip((col_min + col_max) // 2, 0, W - 1))

        row_segment = axial_bone[center_row, col_min:col_max + 1]
        seg_cols = np.where(row_segment)[0]
        if len(seg_cols) >= 2:
            width_px = float(seg_cols[-1] - seg_cols[0])
        else:
            width_px = float(max(1, col_max - col_min))

        col_segment = axial_bone[row_min:row_max + 1, center_col]
        seg_rows = np.where(col_segment)[0]
        if len(seg_rows) >= 2:
            height_px = float(seg_rows[-1] - seg_rows[0])
        else:
            height_px = float(max(1, row_max - row_min))

        width_mm = float(width_px * float(pixel_spacing[1]))
        height_mm = float(height_px * float(pixel_spacing[0]))

        # Keep CBCT outputs within plausible planning ranges for stable UI rendering.
        width_mm = float(np.clip(width_mm, 3.0, 15.0))
        height_mm = float(np.clip(height_mm, 10.0, 35.0))
        safe_height_mm = float(max(0.0, height_mm - SAFETY_MARGIN_MM))

        is_calibrated_hu = bool(metadata.get("is_calibrated_hu", False))
        region_bone = axial_bone[max(0, center_row - 10): center_row + 11, max(0, center_col - 10): center_col + 11]
        region_vol = axial_vol[max(0, center_row - 10): center_row + 11, max(0, center_col - 10): center_col + 11]
        density_estimate = (
            float(np.mean(region_vol[region_bone]))
            if region_bone.any() and is_calibrated_hu
            else 0.0
        )
        if not np.isfinite(density_estimate):
            density_estimate = 0.0

        if not np.isfinite(width_mm):
            width_mm = 3.0
        if not np.isfinite(height_mm):
            height_mm = 10.0
        if not np.isfinite(safe_height_mm):
            safe_height_mm = 8.0

        return {
            "width_mm": round(float(width_mm), 2),
            "height_mm": round(float(height_mm), 2),
            "safe_height_mm": round(float(safe_height_mm), 2),
            "safety_margin_mm": SAFETY_MARGIN_MM,
            "density_estimate_hu": round(float(density_estimate), 1),
            "measurement_location": {"x": int(center_col), "y": int(center_row)},
            "safety_status": "review",
            "safety_reason": "CBCT preview measurement uses localized fallback geometry.",
        }

    # Legacy panoramic/2D path remains unchanged.
    profiles = _extract_2d_mandible_profiles(axial_bone)
    if profiles is not None:
        prof_x, prof_top, prof_bottom = profiles
        idx = int(np.argmin(np.abs(prof_x - cx)))
        idx = int(np.clip(idx, 0, len(prof_x) - 1))
        width_px = max(0.0, float(prof_bottom[idx] - prof_top[idx]))
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
        row_bone = axial_bone[cy, :]
        bone_cols = np.where(row_bone)[0]
        width_px = float(bone_cols[-1] - bone_cols[0]) if len(bone_cols) >= 2 else 0.0

        col_bone = axial_bone[:, cx]
        col_nerve = axial_nerve[:, cx]
        bone_rows = np.where(col_bone)[0]
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

    adaptive_mm_per_px = _estimate_2d_mm_per_px(axial_bone, pixel_spacing[0])
    width_mm = float(width_px) * adaptive_mm_per_px
    height_mm = float(height_px) * adaptive_mm_per_px
    safe_height_mm = max(0.0, height_mm - SAFETY_MARGIN_MM)
    safety_status, safety_reason = _classify_measurement(width_mm, safe_height_mm)

    region_bone = axial_bone[
        max(0, cy - 10): cy + 11,
        max(0, cx - 10): cx + 11,
    ]
    region_vol = axial_vol[
        max(0, cy - 10): cy + 11,
        max(0, cx - 10): cx + 11,
    ]
    is_calibrated_hu = bool(metadata.get("is_calibrated_hu", False))
    density_estimate = (
        float(np.mean(region_vol[region_bone]))
        if region_bone.any() and is_calibrated_hu
        else 0.0
    )

    width_mm, height_mm, safe_height_mm, safety_status, safety_reason = _apply_clinical_guardrails(
        width_mm=width_mm,
        height_mm=height_mm,
        safe_height_mm=safe_height_mm,
        density_hu=density_estimate,
        has_nerve=bool(axial_nerve.any()),
        dataset_type="2d_radiograph",
        is_hu_calibrated=is_calibrated_hu,
        initial_status=safety_status,
        initial_reason=safety_reason,
    )

    return {
        "width_mm": round(width_mm, 2),
        "height_mm": round(height_mm, 2),
        "safe_height_mm": round(safe_height_mm, 2),
        "safety_margin_mm": SAFETY_MARGIN_MM,
        "density_estimate_hu": round(density_estimate, 1),
        "measurement_location": {"x": cx, "y": cy},
        "safety_status": safety_status,
        "safety_reason": safety_reason,
    }


def _estimate_2d_mm_per_px(axial_bone: np.ndarray, stated_mm_per_px: float) -> float:
    """
    Estimate pixel scale for uncalibrated 2D projections.

    If spacing is outside typical dental range, derive scale from median mandibular
    thickness so returned dimensions stay anatomically plausible and are marked for review.
    """
    if 0.05 <= float(stated_mm_per_px) <= 0.8:
        return float(stated_mm_per_px)

    cols = np.where(axial_bone.any(axis=0))[0]
    if len(cols) < 10:
        return 0.2

    thicknesses = []
    for x in cols:
        rows = np.where(axial_bone[:, x])[0]
        if len(rows) >= 3:
            thicknesses.append(float(rows.max() - rows.min() + 1))

    if not thicknesses:
        return 0.2

    median_px = float(np.median(np.asarray(thicknesses, dtype=np.float64)))
    median_px = max(30.0, median_px)
    # Typical mandibular body thickness around 20-25 mm in panoramic-like views.
    return 22.0 / median_px


def _apply_clinical_guardrails(
    width_mm: float,
    height_mm: float,
    safe_height_mm: float,
    density_hu: float,
    has_nerve: bool,
    dataset_type: str,
    is_hu_calibrated: bool,
    initial_status: str,
    initial_reason: str,
) -> tuple[float, float, float, str, str]:
    """Apply hard plausibility checks before surfacing planning recommendations."""
    reasons: list[str] = []

    # Prevent impossible numbers from being shown as valid planning values.
    width_mm = float(np.clip(width_mm, 0.0, 35.0))
    height_mm = float(np.clip(height_mm, 0.0, 45.0))
    safe_height_mm = float(np.clip(safe_height_mm, 0.0, 40.0))

    if not (3.0 <= width_mm <= 15.0):
        reasons.append("SCALE ERROR: ridge width outside plausible range (3-15 mm).")
    if not (10.0 <= height_mm <= 35.0):
        reasons.append("SCALE ERROR: bone height outside plausible range (10-35 mm).")

    if dataset_type != "volumetric_cbct":
        reasons.append("2D radiograph detected; volumetric CBCT metrics are not directly valid.")
    if not has_nerve:
        reasons.append("Nerve canal not confidently localized at this site.")
    if not is_hu_calibrated:
        reasons.append("Density is uncalibrated for this dataset; HU-based decisions are disabled.")
    elif density_hu < 150.0:
        reasons.append("INVALID DENSITY ROI: measured density below expected bone range.")

    all_safe_rules = (
        width_mm > 5.0
        and height_mm > 10.0
        and safe_height_mm > 2.0
        and has_nerve
        and is_hu_calibrated
        and density_hu > 150.0
        and dataset_type == "volumetric_cbct"
        and not reasons
    )

    if all_safe_rules:
        return width_mm, height_mm, safe_height_mm, "safe", initial_reason

    if reasons:
        return (
            width_mm,
            height_mm,
            safe_height_mm,
            "review",
            "REQUIRES CLINICAL REVIEW. " + " ".join(reasons),
        )

    return width_mm, height_mm, safe_height_mm, initial_status, initial_reason


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


def _smooth_profile(x: np.ndarray, y: np.ndarray, window_length: int = 11, polyorder: int = 3) -> tuple[np.ndarray, np.ndarray]:
    """
    Smooth a profile using Savitzky-Golay filter for normalized, clean geometry.

    Parameters
    ----------
    x : np.ndarray
        X-coordinates of the profile
    y : np.ndarray
        Y-coordinates of the profile
    window_length : int
        Window size for Savitzky-Golay filter (must be odd and >= polyorder + 2)
    polyorder : int
        Polynomial order for the filter
    
    Returns
    -------
    x_smooth, y_smooth : tuple[np.ndarray, np.ndarray]
        Smoothed x and y coordinates
    """
    if len(x) < 5:
        return x, y

    # Ensure window_length is odd and reasonable
    max_window = len(x) if len(x) % 2 == 1 else len(x) - 1
    if max_window < 5:
        return x, y
    window_length = max(5, window_length if window_length % 2 == 1 else window_length - 1)
    window_length = min(window_length, max_window)
    polyorder = min(polyorder, window_length - 2)

    # Apply Savitzky-Golay filter to y-coordinates
    y_smooth = signal.savgol_filter(y, window_length=window_length, polyorder=polyorder)

    return x, y_smooth


def _adaptive_smoothing_window(length: int, ratio: float, minimum: int, maximum: int) -> int:
    """Return a valid odd smoothing window scaled to the profile length."""
    if length < 5:
        return 0

    window = int(round(length * ratio))
    window = max(minimum, min(maximum, window))
    if window % 2 == 0:
        window += 1

    max_window = length if length % 2 == 1 else length - 1
    if max_window < 5:
        return 0
    return min(window, max_window)


def _normalize_profile(y: np.ndarray, sigma: float) -> np.ndarray:
    """Apply gentle Gaussian normalization before curve smoothing."""
    if len(y) < 5:
        return y
    return ndimage.gaussian_filter1d(y.astype(np.float64), sigma=sigma, mode="nearest")


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

    x, preview_top, preview_mid, preview_bottom = _trim_overlay_profiles(
        x,
        preview_top,
        preview_mid,
        preview_bottom,
        trim_ratio=0.1,
    )

    preview_top = _normalize_profile(preview_top, sigma=2.2)
    preview_bottom = _normalize_profile(preview_bottom, sigma=2.4)
    preview_mid = _normalize_profile(preview_mid, sigma=1.8)

    profile_window = _adaptive_smoothing_window(len(x), ratio=0.24, minimum=15, maximum=51)
    if profile_window:
        _, preview_top = _smooth_profile(x, preview_top, window_length=profile_window, polyorder=3)
        _, preview_bottom = _smooth_profile(x, preview_bottom, window_length=profile_window, polyorder=3)

    # Increase inner margin of outer red line for visual separation
    margin_factor = 0.18
    thickness = preview_bottom - preview_top
    preview_top = np.clip(preview_top - thickness * margin_factor, 0, axial_bone.shape[0] - 1)

    bottom_window = _adaptive_smoothing_window(len(x), ratio=0.32, minimum=19, maximum=71)
    if bottom_window:
        _, preview_bottom = _smooth_profile(x, preview_bottom, window_length=bottom_window, polyorder=3)

    # Keep the inner red line slightly lower without distorting tight arches.
    preview_bottom = np.clip(preview_bottom + 10.0, 0, axial_bone.shape[0] - 1)

    # Outer contour = tapered left shoulder -> superior contour -> tapered right shoulder.
    # Avoid deep endpoint drops, which become messy on tight arches.
    image_width = axial_bone.shape[1]
    side_offset = max(14.0, (x[-1] - x[0]) * 0.18)
    edge_thickness = np.maximum(8.0, preview_bottom[[0, -1]] - preview_top[[0, -1]])
    left_mid_x = float(np.clip(x[0] - side_offset * 0.55, 0, image_width - 1))
    left_outer_x = float(np.clip(x[0] - side_offset, 0, image_width - 1))
    right_mid_x = float(np.clip(x[-1] + side_offset * 0.55, 0, image_width - 1))
    right_outer_x = float(np.clip(x[-1] + side_offset, 0, image_width - 1))

    left_mid_y = float(np.clip(preview_top[0] + edge_thickness[0] * 0.35, 0, axial_bone.shape[0] - 1))
    left_outer_y = float(np.clip(preview_top[0] + edge_thickness[0] * 0.68, 0, axial_bone.shape[0] - 1))
    right_mid_y = float(np.clip(preview_top[-1] + edge_thickness[1] * 0.35, 0, axial_bone.shape[0] - 1))
    right_outer_y = float(np.clip(preview_top[-1] + edge_thickness[1] * 0.68, 0, axial_bone.shape[0] - 1))

    outer_x = np.concatenate(([left_outer_x, left_mid_x], x, [right_mid_x, right_outer_x]))
    outer_y = np.concatenate(([left_outer_y, left_mid_y], preview_top, [right_mid_y, right_outer_y]))

    outer_y = _normalize_profile(outer_y, sigma=2.8)
    outer_window = _adaptive_smoothing_window(len(outer_x), ratio=0.34, minimum=21, maximum=81)
    if outer_window:
        _, outer_y = _smooth_profile(outer_x, outer_y, window_length=outer_window, polyorder=3)

    inner_points = _profile_points(x, preview_bottom, step_target=120)
    outer_points = _profile_points(outer_x, outer_y, step_target=140)

    # Central guide through the arch middle section with enhanced smoothing
    guide_trim = max(2, int(len(x) * 0.08))
    if len(x) > guide_trim * 2 + 2:
        guide_x = x[guide_trim:-guide_trim]
        guide_y = preview_mid[guide_trim:-guide_trim]
    else:
        guide_x = x
        guide_y = preview_mid

    # Apply stronger smoothing to the middle line for smoother appearance
    guide_y = _normalize_profile(guide_y, sigma=1.6)
    guide_window = min(21, max(9, len(guide_x) // 6))
    if guide_window % 2 == 0:
        guide_window -= 1
    _, guide_y_smooth = _smooth_profile(guide_x, guide_y, window_length=guide_window, polyorder=3)
    base_guide = _profile_points(guide_x, guide_y_smooth, step_target=110)

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
