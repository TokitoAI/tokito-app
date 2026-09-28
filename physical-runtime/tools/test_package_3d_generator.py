#!/usr/bin/env python3
"""Tests for the parametric package-model generator."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from package_3d_generator import generate_packages, package_model_filename, validate_record


def make_record(**overrides) -> dict:
    record = {
        "package": "MicroSMP_DO-219AD",
        "body_l_mm": 2.6,
        "body_w_mm": 1.45,
        "body_h_mm": 0.98,
        "source": "https://example.com/datasheet.pdf",
        "evidence": "package outline drawing",
        "references": ["D1", "D2"],
    }
    record.update(overrides)
    return record


class ValidateRecordTest(unittest.TestCase):
    def test_missing_source_is_rejected(self) -> None:
        with self.assertRaises(ValueError):
            validate_record(make_record(source=""))

    def test_missing_references_are_rejected(self) -> None:
        with self.assertRaises(ValueError):
            validate_record(make_record(references=[]))

    def test_non_positive_dimensions_are_rejected(self) -> None:
        with self.assertRaises(ValueError):
            validate_record(make_record(body_h_mm=0))


class GeneratePackagesTest(unittest.TestCase):
    def test_one_model_per_package_one_override_per_reference(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            result = generate_packages([make_record()], Path(directory))
            model = Path(result["models_generated"][0])
            self.assertTrue(model.is_file())
            content = model.read_text(encoding="utf-8")

        self.assertEqual(len(result["models_generated"]), 1)
        self.assertEqual(len(result["override_rows"]), 2)
        self.assertEqual({r["reference"] for r in result["override_rows"]}, {"D1", "D2"})
        for row in result["override_rows"]:
            self.assertEqual(row["status"], "manual_dimension_accurate_placeholder")
            self.assertIn("example.com", row["evidence"])
        # provenance and dimensions embedded in the geometry file itself
        self.assertIn("2.6 x 1.45 x 0.98 mm", content)
        self.assertIn("example.com", content)

    def test_filename_carries_dimensions_for_downstream_extent_validation(self) -> None:
        # verify_populated_glb reads expected extents from this pattern
        name = package_model_filename(make_record())
        self.assertEqual(name, "MicroSMP_DO-219AD_2.6x1.45x0.98mm_datasheet_box.wrl")


if __name__ == "__main__":
    unittest.main()
