#!/usr/bin/env python3
"""Tests for the dimension-accurate placeholder box generator."""

from __future__ import annotations

import unittest

from make_box_model import MM_PER_VRML_UNIT, box_vrml


class BoxVrmlTest(unittest.TestCase):
    def test_converts_mm_to_kicad_vrml_units(self) -> None:
        text = box_vrml(9.0, 9.0, 0.86, "test-provenance")
        # 9mm / 2.54 = 3.543307 units; 0.86mm / 2.54 = 0.338583 units
        self.assertIn("size 3.543307 3.543307 0.338583", text)

    def test_box_sits_on_board_surface(self) -> None:
        text = box_vrml(9.0, 9.0, 0.86, "test-provenance")
        half_height = 0.86 / MM_PER_VRML_UNIT / 2
        self.assertIn(f"translation 0 0 {half_height:.6f}", text)

    def test_embeds_provenance_and_placeholder_warning(self) -> None:
        text = box_vrml(2.0, 3.0, 1.0, "https://example.com/package-page")
        self.assertIn("provenance: https://example.com/package-page", text)
        self.assertIn("not a true package shape", text)
        self.assertIn("2.0 x 3.0 x 1.0 mm", text)

    def test_rejects_non_positive_dimensions(self) -> None:
        with self.assertRaises(ValueError):
            box_vrml(0.0, 9.0, 0.86, "x")
        with self.assertRaises(ValueError):
            box_vrml(9.0, -1.0, 0.86, "x")


if __name__ == "__main__":
    unittest.main()
