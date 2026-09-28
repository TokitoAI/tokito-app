#!/usr/bin/env python3
"""Tests for the extended per-component GLB validation checks."""

from __future__ import annotations

import unittest

from verify_populated_glb import component_checks, expected_body_extent_mm


def make_row(side: str = "top", rotation: float = 0.0, model_rotate: float = 0.0) -> dict:
    return {
        "reference": "SW1",
        # box provenance name carries the expected dimensions (4.2 x 3.2 x 2.5)
        "model_path": "/models/WS-TASV_4.2x3.2x2.5mm_placeholder_box.wrl",
        "x_mm": 10.0,
        "y_mm": -20.0,
        "rotation_degrees": rotation,
        "side": side,
        "model_rotate_z_deg": model_rotate,
        "model_offset_x_mm": 0.0,
        "model_offset_y_mm": 0.0,
    }


def box_at(x_mm: float, y_mm: float, w_mm: float, d_mm: float, y0_mm: float, y1_mm: float) -> list[float]:
    """GLB-space AABB (metres): x span, vertical y span, z = board y."""
    return [
        (x_mm - w_mm / 2) / 1000, (x_mm + w_mm / 2) / 1000,
        y0_mm / 1000, y1_mm / 1000,
        (y_mm - d_mm / 2) / 1000, (y_mm + d_mm / 2) / 1000,
    ]


class ExpectedBodyExtentTest(unittest.TestCase):
    def test_reads_dimensions_from_placeholder_filename(self) -> None:
        self.assertEqual(expected_body_extent_mm(make_row()), (4.2, 3.2))

    def test_none_when_no_source_exists(self) -> None:
        row = make_row()
        row["model_path"] = "/models/unknown_shape.wrl"
        self.assertIsNone(expected_body_extent_mm(row))


class ComponentChecksTest(unittest.TestCase):
    def test_correct_body_passes_everything(self) -> None:
        checks = component_checks(make_row(), box_at(10.0, -20.0, 4.2, 3.2, 1.6, 4.1), 1, 1.6, 2.5)
        self.assertEqual(checks["verdict"], "PASS")
        self.assertEqual(checks["side"], "PASS")
        self.assertEqual(checks["extent"], "PASS")
        self.assertAlmostEqual(checks["height_above_board_mm"], 2.5)

    def test_displaced_body_fails_position(self) -> None:
        checks = component_checks(make_row(), box_at(25.0, -20.0, 4.2, 3.2, 1.6, 4.1), 1, 1.6, 2.5)
        self.assertEqual(checks["position"], "FAIL")
        self.assertEqual(checks["verdict"], "FAIL")

    def test_ninety_degree_rotation_swaps_expected_extent(self) -> None:
        checks = component_checks(
            make_row(rotation=90.0), box_at(10.0, -20.0, 3.2, 4.2, 1.6, 4.1), 1, 1.6, 2.5
        )
        self.assertEqual(checks["extent"], "PASS")

    def test_wrong_extent_is_flagged(self) -> None:
        checks = component_checks(make_row(), box_at(10.0, -20.0, 12.0, 3.2, 1.6, 4.1), 1, 1.6, 2.5)
        self.assertEqual(checks["extent"], "FAIL")

    def test_body_on_wrong_side_fails(self) -> None:
        # top-side component whose body sits entirely below the board
        checks = component_checks(make_row(side="top"), box_at(10.0, -20.0, 4.2, 3.2, -4.1, -1.6), 1, 1.6, 2.5)
        self.assertEqual(checks["side"], "FAIL")

    def test_floating_body_fails_board_contact(self) -> None:
        checks = component_checks(make_row(), box_at(10.0, -20.0, 4.2, 3.2, 8.0, 10.5), 1, 1.6, 2.5)
        self.assertEqual(checks["board_contact"], "FAIL")

    def test_duplicate_node_name_fails(self) -> None:
        checks = component_checks(make_row(), box_at(10.0, -20.0, 4.2, 3.2, 1.6, 4.1), 2, 1.6, 2.5)
        self.assertEqual(checks["node_unique"], "FAIL")
        self.assertEqual(checks["verdict"], "FAIL")


if __name__ == "__main__":
    unittest.main()
