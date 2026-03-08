import unittest

import numpy as np

from dicom_processor import build_planning_overlay


class PlanningOverlayGeometryTests(unittest.TestCase):
    def _make_mandible(self, h=220, w=220):
        volume = np.zeros((1, h, w), dtype=float)
        bone = np.zeros((1, h, w), dtype=bool)
        xs = np.arange(25, 195)
        center = w / 2
        for x in xs:
            top = 42 + 0.008 * (x - center) ** 2
            thickness = 24 + 0.02 * abs(x - center)
            bottom = top + thickness
            bone[0, int(round(top)): int(round(bottom)) + 1, x] = True
        return volume, bone

    def test_overlay_emits_three_clean_parallel_arch_curves(self):
        volume, bone = self._make_mandible()
        overlay = build_planning_overlay(
            volume, bone, {"measurement_location": {"x": 150, "y": 100}}
        )
        outer = overlay["outer_contour"]
        inner = overlay["inner_contour"]
        mid = overlay["base_guide"]

        self.assertGreater(len(outer), 10, "outer_contour should have arch points")
        self.assertGreater(len(inner), 10, "inner_contour should have arch points")
        self.assertGreater(len(mid), 8, "base_guide should have arch points")

        outer_x = [p["x"] for p in outer]
        inner_x = [p["x"] for p in inner]
        mid_x = [p["x"] for p in mid]
        self.assertEqual(outer_x, sorted(outer_x))
        self.assertEqual(inner_x, sorted(inner_x))
        self.assertEqual(mid_x, sorted(mid_x))

        shared = min(len(inner), len(mid))
        for i in range(shared):
            self.assertLess(mid[i]["y"], inner[i]["y"])

    def test_sector_lines_are_not_used_in_preview(self):
        volume, bone = self._make_mandible()
        overlay = build_planning_overlay(
            volume, bone, {"measurement_location": {"x": 150, "y": 100}}
        )
        self.assertEqual(overlay["sector_lines"], [])

    def test_width_indicator_is_present(self):
        volume, bone = self._make_mandible()
        overlay = build_planning_overlay(
            volume, bone, {"measurement_location": {"x": 150, "y": 100}}
        )
        self.assertIsNotNone(overlay["width_indicator"])


if __name__ == "__main__":
    unittest.main()

