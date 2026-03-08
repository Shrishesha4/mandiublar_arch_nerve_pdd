"""
Segmentation Model (Placeholder)
---------------------------------
Provides a placeholder U-Net inference class for:
  1. Bone / mandible segmentation
  2. Inferior Alveolar Nerve (IAN) canal detection

Replace `predict()` with real ONNX / TFLite / PyTorch inference
once a trained model is available.
"""

import numpy as np
from scipy import ndimage


class UNetSegmentor:
    """Placeholder segmentation model that returns synthetic masks."""

    def __init__(self, model_path: str | None = None):
        self.model_path = model_path
        self.loaded = False

    def predict(self, volume: np.ndarray) -> dict:
        """
        Run segmentation on a 3-D CBCT volume or a single 2-D axial DICOM slice.
        Returns bone_mask and nerve_mask (same shape as volume).
        Also returns mandible_center: (row, col) of the mandible centroid.
        """
        is_2d = volume.shape[0] <= 3
        bone_mask, mandible_center = self._segment_bone(volume, is_2d)
        nerve_mask = self._detect_nerve(volume, bone_mask, is_2d)
        return {
            "bone_mask": bone_mask,
            "nerve_mask": nerve_mask,
            "mandible_center": mandible_center,   # (row, col) in image coords
        }

    # ------------------------------------------------------------------

    @staticmethod
    def _segment_bone(volume: np.ndarray, is_2d: bool):
        """
        Segment cortical bone.

        For 2-D axial slices: the mandible is the LARGEST bright connected
        component after thresholding.  We keep only that component so we
        don't include the skull base, spine, etc.

        Returns (mask, centroid_rc).
        """
        struct2d = ndimage.generate_binary_structure(2, 1)
        n_slices, H, W = volume.shape
        result = np.zeros_like(volume, dtype=bool)
        centroid = (H // 2, W // 2)   # fallback

        for s in range(n_slices):
            sl = volume[s].astype(np.float64)

            # Threshold at 70th percentile → bright = bone/cortex
            thresh = np.percentile(sl, 70)
            binary = sl > thresh

            # Clean with opening
            binary = ndimage.binary_opening(binary, structure=struct2d, iterations=3)

            if not binary.any():
                continue

            # Label connected components
            labelled, n_feat = ndimage.label(binary, structure=struct2d)
            if n_feat == 0:
                continue

            # For a 2-D axial CBCT slice the mandible is usually a U-shaped
            # ring in the lower half. Pick the largest component.
            sizes = [
                (ndimage.sum(binary, labelled, i), i)
                for i in range(1, n_feat + 1)
            ]
            sizes.sort(reverse=True)

            # Keep the top-1 (largest) component as the mandible
            best_label = sizes[0][1]
            mandible_mask = labelled == best_label

            # Fill holes so the trabecular interior is included
            mandible_mask = ndimage.binary_fill_holes(mandible_mask)
            result[s] = mandible_mask

            # Centroid of the mandible component
            rows, cols = np.where(mandible_mask)
            if len(rows):
                centroid = (int(rows.mean()), int(cols.mean()))

        return result.astype(bool), centroid

    @staticmethod
    def _detect_nerve(
        volume: np.ndarray, bone_mask: np.ndarray, is_2d: bool
    ) -> np.ndarray:
        """
        Detect the IAN canals as small DARK bilateral ovals strictly
        inside the mandibular cortex.

        Key constraints that eliminate the "orange mess":
        - Component area must be < 1% of mandible area  (small ovals only)
        - Component must be INSIDE the filled mandible mask (not on edges)
        - We expect at most 2 bilateral canals; keep the 2 largest qualifying
        """
        struct2d = ndimage.generate_binary_structure(2, 1)
        nerve_mask = np.zeros_like(volume, dtype=bool)

        for s in range(volume.shape[0]):
            sl_bone = bone_mask[s]
            if not sl_bone.any():
                continue

            # Erode the filled mandible slightly to get strictly interior
            interior = ndimage.binary_erosion(
                sl_bone, structure=struct2d, iterations=6
            )
            if not interior.any():
                continue

            sl = volume[s]
            interior_vals = sl[interior]
            if interior_vals.size == 0:
                continue

            # Dark threshold: bottom 25th percentile of interior voxels
            dark_thresh = np.percentile(interior_vals, 25)
            candidate = (sl < dark_thresh) & interior

            labelled, n_feat = ndimage.label(candidate, structure=struct2d)
            if n_feat == 0:
                continue

            mandible_area = sl_bone.sum()
            # IAN canal: area between 0.05% and 0.8% of mandible
            min_area = 0.0005 * mandible_area
            max_area = 0.008  * mandible_area

            qualifying = []
            for i in range(1, n_feat + 1):
                comp_mask = labelled == i
                sz = comp_mask.sum()
                if min_area <= sz <= max_area:
                    qualifying.append((sz, i))

            # Keep at most 2 largest qualifying components (bilateral canals)
            qualifying.sort(reverse=True)
            for _, idx in qualifying[:2]:
                nerve_mask[s] |= labelled == idx

        return nerve_mask


def extract_nerve_path_2d(nerve_mask: np.ndarray) -> list[dict]:
    """
    Project nerve mask onto the image plane and return centreline points
    sorted left → right.
    """
    projection = nerve_mask.any(axis=0).astype(np.uint8)   # (H, W)

    if projection.sum() == 0:
        return []

    from skimage.morphology import skeletonize
    skeleton = skeletonize(projection).astype(bool)
    coords = np.argwhere(skeleton)   # (N, 2): row, col

    if len(coords) == 0:
        return []

    coords = coords[coords[:, 1].argsort()]   # sort by column (left → right)
    step = max(1, len(coords) // 200)
    sampled = coords[::step]
    return [{"x": int(c[1]), "y": int(c[0])} for c in sampled]


def detect_arch_from_2d_slice(volume: np.ndarray) -> list[dict] | None:
    """
    Detect a smooth OPEN mandibular arch on a 2-D axial CBCT slice.

    Contract:
      - returns points ordered left → right
      - represents the superior outer mandibular contour (not a closed ring)
      - suitable for direct overlay as a red arch/path
    """
    sl = volume[0].astype(np.float64)
    H, W = sl.shape
    struct2d = ndimage.generate_binary_structure(2, 1)

    # 1) Bone threshold with gentle cleanup
    thresh = np.percentile(sl, 68)
    binary = sl > thresh
    binary = ndimage.binary_opening(binary, structure=struct2d, iterations=2)
    binary = ndimage.binary_closing(binary, structure=struct2d, iterations=2)
    if not binary.any():
        return None

    # 2) Keep the best mandibular component.
    # Score favors large area and lower-half presence, which matches axial mandible.
    labelled, n_feat = ndimage.label(binary, structure=struct2d)
    if n_feat == 0:
        return None

    best_label = None
    best_score = -1.0
    for i in range(1, n_feat + 1):
        comp = labelled == i
        area = float(comp.sum())
        if area < 200:
            continue
        rows, cols = np.where(comp)
        if len(rows) == 0:
            continue
        cy = float(rows.mean())
        lower_frac = float((rows > H * 0.30).mean())
        width = float(cols.max() - cols.min() + 1)
        score = area * (0.6 + lower_frac) + 0.05 * width + 0.02 * cy
        if score > best_score:
            best_score = score
            best_label = i

    if best_label is None:
        return None

    mandible = labelled == best_label

    # 3) Column-wise superior contour.
    # For each x, take the top-most bone pixel. This produces the open U-shaped arch.
    cols = np.where(mandible.any(axis=0))[0]
    if len(cols) < 20:
        return None

    x_vals: list[int] = []
    y_vals: list[float] = []
    thickness_vals: list[int] = []

    for x in cols:
        rows = np.where(mandible[:, x])[0]
        if len(rows) < 3:
            continue
        top = int(rows.min())
        bottom = int(rows.max())
        thickness = bottom - top + 1
        # Ignore tiny vertical slivers/noise.
        if thickness < 6:
            continue
        x_vals.append(int(x))
        y_vals.append(float(top))
        thickness_vals.append(int(thickness))

    if len(x_vals) < 20:
        return None

    x = np.asarray(x_vals, dtype=np.int32)
    y = np.asarray(y_vals, dtype=np.float64)
    t = np.asarray(thickness_vals, dtype=np.float64)

    # 4) Trim unstable edge columns where the component gets too thin.
    # This removes the bad diagonal closure behavior seen in the screenshot.
    thickness_cutoff = max(8.0, np.percentile(t, 15))
    valid = t >= thickness_cutoff
    if valid.sum() >= 20:
        x = x[valid]
        y = y[valid]

    if len(x) < 20:
        return None

    # 5) Smooth the superior contour.
    # Median filter kills spikes, then Gaussian-like 1-D smoothing gives a clean arch.
    y_med = ndimage.median_filter(y, size=9, mode="nearest")
    y_smooth = ndimage.gaussian_filter1d(y_med, sigma=4, mode="nearest")

    # 6) Enforce a single y per x and downsample to a manageable number of points.
    # x is already increasing because cols came left→right.
    step = max(1, len(x) // 120)
    sampled_x = x[::step]
    sampled_y = y_smooth[::step]

    points = [
        {"x": int(px), "y": int(py)}
        for px, py in zip(sampled_x, sampled_y)
    ]

    return points if len(points) >= 8 else None

