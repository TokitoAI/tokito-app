#!/usr/bin/env python3
"""Tests for locate_component() (issue #40 slice, #87).

ResolvedBoardTransform mirrors android/ar-runtime's ResolvedTransform (PR
#60, merged to main) -- these tests check the same abstention semantics
that runtime's FrameGraph.resolve() encodes: no path (None), expired
(nowNanos > expiresAtNanos), and an uncertainty budget this consumer
enforces on top of that.
"""

from __future__ import annotations

import unittest

from component_locator import (
    ResolvedBoardTransform,
    apply_matrix3,
    footprint_corners_board_mm,
    identity_test_transform,
    locate_component,
    rotate_point,
)


def make_component(reference: str, x: float, y: float, rotation: float, width: float, height: float, side: str = "top") -> dict:
    return {
        "reference_designator": reference,
        "centroid": {"x": x, "y": y},
        "rotation_degrees": rotation,
        "side": side,
        "footprint_bbox_mm": {"width": width, "height": height},
    }


class RotatePointTest(unittest.TestCase):
    def test_zero_rotation_is_identity(self) -> None:
        self.assertEqual(rotate_point(3.0, 4.0, 0.0), (3.0, 4.0))

    def test_ninety_degree_rotation(self) -> None:
        x, y = rotate_point(1.0, 0.0, 90.0)
        self.assertAlmostEqual(x, 0.0, places=9)
        self.assertAlmostEqual(y, 1.0, places=9)


class FootprintCornersTest(unittest.TestCase):
    def test_unrotated_corners_centered_on_centroid(self) -> None:
        component = make_component("R1", 10.0, 20.0, 0.0, 2.0, 4.0)
        corners = footprint_corners_board_mm(component)
        xs = sorted(x for x, _ in corners)
        ys = sorted(y for _, y in corners)
        self.assertAlmostEqual(xs[0], 9.0)
        self.assertAlmostEqual(xs[-1], 11.0)
        self.assertAlmostEqual(ys[0], 18.0)
        self.assertAlmostEqual(ys[-1], 22.0)


class ApplyMatrix3Test(unittest.TestCase):
    def test_identity_scale(self) -> None:
        matrix = ((2.0, 0.0, 0.0), (0.0, 2.0, 0.0), (0.0, 0.0, 1.0))
        self.assertEqual(apply_matrix3(5.0, 3.0, matrix), (10.0, 6.0))

    def test_degenerate_matrix_raises(self) -> None:
        matrix = ((1.0, 0.0, 0.0), (0.0, 1.0, 0.0), (0.0, 0.0, 0.0))
        with self.assertRaises(ValueError):
            apply_matrix3(1.0, 1.0, matrix)


class LocateComponentTest(unittest.TestCase):
    def test_abstains_when_no_transform_available(self) -> None:
        components = {"R1": make_component("R1", 10.0, 20.0, 0.0, 2.0, 2.0)}
        result = locate_component("R1", components, None, now_nanos=0, max_uncertainty_px=5.0)
        self.assertFalse(result["located"])
        self.assertEqual(result["reason"], "registration_not_available")

    def test_abstains_when_transform_expired(self) -> None:
        components = {"R1": make_component("R1", 10.0, 20.0, 0.0, 2.0, 2.0)}
        transform = identity_test_transform(scale_px_per_mm=10.0, expires_at_nanos=1000)
        result = locate_component("R1", components, transform, now_nanos=2000, max_uncertainty_px=5.0)
        self.assertFalse(result["located"])
        self.assertEqual(result["reason"], "registration_expired")

    def test_abstains_when_uncertainty_too_high(self) -> None:
        components = {"R1": make_component("R1", 10.0, 20.0, 0.0, 2.0, 2.0)}
        transform = identity_test_transform(scale_px_per_mm=10.0, uncertainty_px=8.0)
        result = locate_component("R1", components, transform, now_nanos=0, max_uncertainty_px=5.0)
        self.assertFalse(result["located"])
        self.assertEqual(result["reason"], "registration_uncertainty_too_high")

    def test_abstains_when_reference_not_found(self) -> None:
        components = {"R1": make_component("R1", 10.0, 20.0, 0.0, 2.0, 2.0)}
        transform = identity_test_transform(scale_px_per_mm=10.0)
        result = locate_component("R99", components, transform, now_nanos=0, max_uncertainty_px=5.0)
        self.assertFalse(result["located"])
        self.assertEqual(result["reason"], "reference_not_found_in_assembly_context")

    def test_locates_with_real_scale_and_reports_measured_extent(self) -> None:
        components = {"R1": make_component("R1", 10.0, 20.0, 0.0, 2.0, 4.0)}
        transform = identity_test_transform(scale_px_per_mm=10.0, revision=7, uncertainty_px=1.5)
        result = locate_component("R1", components, transform, now_nanos=0, max_uncertainty_px=5.0)
        self.assertTrue(result["located"])
        self.assertEqual(result["footprint_extent_source"], "measured")
        self.assertAlmostEqual(result["roi"]["width"], 20.0)
        self.assertAlmostEqual(result["roi"]["height"], 40.0)
        self.assertAlmostEqual(result["roi"]["x"], 90.0)
        self.assertAlmostEqual(result["roi"]["y"], 180.0)
        self.assertEqual(result["transform_revision"], 7)
        self.assertEqual(result["uncertainty_px"], 1.5)
        self.assertEqual(result["coordinate_frame"], "preview")

    def test_zero_size_bbox_uses_documented_fallback_not_zero_roi(self) -> None:
        components = {"BH1": make_component("BH1", 0.0, 0.0, 0.0, 0.0, 0.0)}
        transform = identity_test_transform(scale_px_per_mm=10.0)
        result = locate_component("BH1", components, transform, now_nanos=0, max_uncertainty_px=5.0)
        self.assertTrue(result["located"])
        self.assertEqual(result["footprint_extent_source"], "fallback_default_margin")
        self.assertGreater(result["roi"]["width"], 0.0)
        self.assertGreater(result["roi"]["height"], 0.0)


if __name__ == "__main__":
    unittest.main()
