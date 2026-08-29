#!/usr/bin/env python3
"""Tests for locate_component() (issue #40 slice)."""

from __future__ import annotations

import unittest

from component_locator import (
    RegistrationResult,
    apply_homography,
    footprint_corners_board_mm,
    identity_registration,
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


class ApplyHomographyTest(unittest.TestCase):
    def test_identity_scale(self) -> None:
        homography = ((2.0, 0.0, 0.0), (0.0, 2.0, 0.0), (0.0, 0.0, 1.0))
        self.assertEqual(apply_homography(5.0, 3.0, homography), (10.0, 6.0))

    def test_degenerate_homography_raises(self) -> None:
        homography = ((1.0, 0.0, 0.0), (0.0, 1.0, 0.0), (0.0, 0.0, 0.0))
        with self.assertRaises(ValueError):
            apply_homography(1.0, 1.0, homography)


class LocateComponentTest(unittest.TestCase):
    def test_abstains_when_not_registered(self) -> None:
        components = {"R1": make_component("R1", 10.0, 20.0, 0.0, 2.0, 2.0)}
        registration = RegistrationResult("board", "A", False, 0.0, "none", None)
        result = locate_component("R1", components, registration, min_registration_confidence=0.5)
        self.assertFalse(result["located"])
        self.assertEqual(result["reason"], "registration_not_available")

    def test_abstains_below_confidence_threshold(self) -> None:
        components = {"R1": make_component("R1", 10.0, 20.0, 0.0, 2.0, 2.0)}
        registration = identity_registration("board", "A", scale_px_per_mm=10.0, confidence=0.4)
        result = locate_component("R1", components, registration, min_registration_confidence=0.6)
        self.assertFalse(result["located"])
        self.assertEqual(result["reason"], "registration_low_confidence")

    def test_abstains_when_reference_not_found(self) -> None:
        components = {"R1": make_component("R1", 10.0, 20.0, 0.0, 2.0, 2.0)}
        registration = identity_registration("board", "A", scale_px_per_mm=10.0)
        result = locate_component("R99", components, registration, min_registration_confidence=0.5)
        self.assertFalse(result["located"])
        self.assertEqual(result["reason"], "reference_not_found_in_assembly_context")

    def test_locates_with_real_scale_and_reports_measured_extent(self) -> None:
        components = {"R1": make_component("R1", 10.0, 20.0, 0.0, 2.0, 4.0)}
        registration = identity_registration("board", "A", scale_px_per_mm=10.0, confidence=0.9)
        result = locate_component("R1", components, registration, min_registration_confidence=0.5)
        self.assertTrue(result["located"])
        self.assertEqual(result["footprint_extent_source"], "measured")
        self.assertAlmostEqual(result["roi"]["width"], 20.0)
        self.assertAlmostEqual(result["roi"]["height"], 40.0)
        self.assertAlmostEqual(result["roi"]["x"], 90.0)
        self.assertAlmostEqual(result["roi"]["y"], 180.0)
        self.assertEqual(result["confidence"], 0.9)

    def test_zero_size_bbox_uses_documented_fallback_not_zero_roi(self) -> None:
        components = {"BH1": make_component("BH1", 0.0, 0.0, 0.0, 0.0, 0.0)}
        registration = identity_registration("board", "A", scale_px_per_mm=10.0)
        result = locate_component("BH1", components, registration, min_registration_confidence=0.5)
        self.assertTrue(result["located"])
        self.assertEqual(result["footprint_extent_source"], "fallback_default_margin")
        self.assertGreater(result["roi"]["width"], 0.0)
        self.assertGreater(result["roi"]["height"], 0.0)


if __name__ == "__main__":
    unittest.main()
