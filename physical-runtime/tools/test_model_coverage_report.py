#!/usr/bin/env python3
"""Tests for the 3D coverage classification report."""

from __future__ import annotations

import unittest

from model_coverage_report import build_coverage_report, classify_reference, model_quality


def row(reference: str, description: str | None, model_path: str | None = None, model_kind: str | None = None) -> dict:
    return {
        "reference": reference,
        "bom_description": description,
        "bom_manufacturer": "M",
        "bom_part_number": "PN",
        "model_path": model_path,
        "model_kind": model_kind,
        "model_registration": None,
        "status": "s",
        "evidence": "e",
    }


class ClassifyReferenceTest(unittest.TestCase):
    def test_no_part_to_order_subtypes_from_bom_text(self) -> None:
        self.assertEqual(
            classify_reference(row("TP5", "TEST POINT PAD 40MIL DIA SMT, NO PART TO ORDER"))["subtype"],
            "test_point_pad",
        )
        self.assertEqual(
            classify_reference(row("SJ1", "SOLDER BLOB JUMPER 3PIN 1-2 SHORT 0402 - NO PART TO ORDER"))["subtype"],
            "solder_jumper_pads",
        )
        self.assertEqual(
            classify_reference(row("BH1", "NON-PLATED MOUNTING HOLE 125 DRILL NO PART TO ORDER"))["subtype"],
            "mounting_hole",
        )
        self.assertFalse(classify_reference(row("TP5", "TEST POINT, NO PART TO ORDER"))["needs_3d_body"])

    def test_real_part_needs_a_body(self) -> None:
        result = classify_reference(row("U12", "IC MEM SERIAL FLASH SOIC8"))
        self.assertEqual(result["category"], "physical_component")
        self.assertTrue(result["needs_3d_body"])

    def test_missing_bom_entry_is_its_own_category(self) -> None:
        self.assertEqual(classify_reference(row("X1", None))["category"], "not_in_bom")


class ModelQualityTest(unittest.TestCase):
    def test_quality_mapping(self) -> None:
        self.assertEqual(model_quality(row("R1", "RES", "/m/R.step", "exact_family")), "exact_package_geometry")
        self.assertEqual(model_quality(row("J9", "SKT", "/m/S.step", "generic_family_approximate")), "verified_package_approximation")
        self.assertEqual(
            model_quality(row("U1", "MCU", "/m/box.wrl", "dimension_accurate_placeholder_not_true_package_shape")),
            "dimension_accurate_placeholder",
        )
        self.assertEqual(model_quality(row("U12", "FLASH", None)), "missing")


class BuildCoverageReportTest(unittest.TestCase):
    def test_coverage_counts_only_physical_components(self) -> None:
        resolution = {
            "components": [
                row("R1", "RES 10K", "/m/R.step", "exact_family"),
                row("U12", "IC FLASH SOIC8"),  # physical, missing model
                row("TP5", "TEST POINT NO PART TO ORDER"),
            ]
        }
        report = build_coverage_report(resolution)
        self.assertEqual(report["total_references"], 3)
        self.assertEqual(report["physical_components"], 2)
        self.assertEqual(report["physical_with_model"], 1)
        self.assertEqual(report["physical_missing_model"], 1)
        self.assertEqual(report["coverage_pct_of_physical"], 50.0)
        self.assertEqual(report["missing_models"][0]["reference"], "U12")
        # the bare test point must not inflate or deflate coverage
        self.assertNotIn("TP5", [m["reference"] for m in report["missing_models"]])


if __name__ == "__main__":
    unittest.main()
