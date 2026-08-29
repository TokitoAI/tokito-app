#!/usr/bin/env python3
"""Tests for the component 3D model resolver."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from component_model_resolver import (
    resolve_all,
    resolve_bare_header_model,
    resolve_chip_passive_model,
    resolve_led_chip_model,
    resolve_sot23_model,
    resolve_sot363_model,
)
from xlsx_bom_parser import (
    extract_bare_header_spec,
    extract_chip_package_code,
    extract_sot23_variant,
    has_sod523_package,
    has_sot363_package,
    is_led_description,
)


def make_component(reference: str, x: float = 0.0, y: float = 0.0, rotation: float = 0.0, side: str = "top") -> dict:
    return {"reference_designator": reference, "centroid": {"x": x, "y": y}, "rotation_degrees": rotation, "side": side}


class ExtractChipPackageCodeTest(unittest.TestCase):
    def test_matches_standalone_package_code(self) -> None:
        self.assertEqual(extract_chip_package_code("CAP CER 4.7uF 16V 10% X5R 0603"), "0603")

    def test_matches_package_code_glued_to_letter_prefix(self) -> None:
        self.assertEqual(extract_chip_package_code("RES 510 OHMS 1/16W 5% RC0402 ROHS"), "0402")

    def test_no_match_returns_none(self) -> None:
        self.assertIsNone(extract_chip_package_code("IC MCU LOW POWER 150MHz ARM Nirvana Nx5x -- VFBGA184"))

    def test_does_not_match_inside_longer_digit_run(self) -> None:
        self.assertIsNone(extract_chip_package_code("PART NUMBER 104020 SPEC"))


class ExtractSot23VariantTest(unittest.TestCase):
    def test_bare_sot23_returns_empty_string(self) -> None:
        self.assertEqual(extract_sot23_variant("IC LIN VREG LDO 1.8V 250MA 1.8-8V SOT23"), "")

    def test_sot23_with_pin_count_suffix(self) -> None:
        self.assertEqual(extract_sot23_variant("SOME PART SOT-23-5 PACKAGE"), "5")

    def test_no_sot23_mentioned_returns_none(self) -> None:
        self.assertIsNone(extract_sot23_variant("CAP CER 4.7uF 16V 10% X5R 0603"))


class HasSod523PackageTest(unittest.TestCase):
    def test_detects_sod523(self) -> None:
        self.assertTrue(has_sod523_package("DIODE TVS UNIDIR 3.3VWM 20VC SOD523"))

    def test_no_match(self) -> None:
        self.assertFalse(has_sod523_package("CAP CER 4.7uF 16V 10% X5R 0603"))


class IsLedDescriptionTest(unittest.TestCase):
    def test_detects_led(self) -> None:
        self.assertTrue(is_led_description("LED RED SGL 20MA 0603"))

    def test_diode_is_not_led(self) -> None:
        self.assertFalse(is_led_description("DIODE SCH 1A 30V MICROSMP SMT"))


class HasSot363PackageTest(unittest.TestCase):
    def test_detects_sot363(self) -> None:
        self.assertTrue(has_sot363_package("DIODE TVS UNIDIR 3.3VWM 12VC SOT363"))

    def test_no_match(self) -> None:
        self.assertFalse(has_sot363_package("CAP CER 4.7uF 16V 10% X5R 0603"))


class ExtractBareHeaderSpecTest(unittest.TestCase):
    def test_extracts_multi_pin_header_with_explicit_pitch(self) -> None:
        self.assertEqual(extract_bare_header_spec("HDR 2X5 TH 50MIL CTR 167H AU 91L"), (2, 5, 1.27))

    def test_single_pin_header_defaults_to_2_54mm(self) -> None:
        self.assertEqual(extract_bare_header_spec("HDR 1X1 TH -- 330H AU 100L"), (1, 1, 2.54))

    def test_shrouded_connector_does_not_match(self) -> None:
        self.assertIsNone(extract_bare_header_spec("CON 2X8 SKT SHRD TH 100MIL CTR 345H AU 126L"))

    def test_multi_pin_header_without_recognized_pitch_returns_none(self) -> None:
        self.assertIsNone(extract_bare_header_spec("HDR 2X5 TH -- 167H AU 91L"))


class ResolveLedChipModelTest(unittest.TestCase):
    def test_resolves_when_file_exists(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            library_root = Path(directory)
            folder = library_root / "LED_SMD.3dshapes"
            folder.mkdir()
            (folder / "LED_0603_1608Metric.step").write_text("x", encoding="utf-8")
            model_path, _ = resolve_led_chip_model("0603", library_root)
            self.assertIsNotNone(model_path)


class ResolveSot363ModelTest(unittest.TestCase):
    def test_resolves_when_file_exists(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            library_root = Path(directory)
            folder = library_root / "Package_TO_SOT_SMD.3dshapes"
            folder.mkdir()
            (folder / "SOT-363_SC-70-6.step").write_text("x", encoding="utf-8")
            model_path, _ = resolve_sot363_model(library_root)
            self.assertIsNotNone(model_path)


class ResolveBareHeaderModelTest(unittest.TestCase):
    def test_resolves_when_file_exists(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            library_root = Path(directory)
            folder = library_root / "Connector_PinHeader_2.54mm.3dshapes"
            folder.mkdir()
            (folder / "PinHeader_2x05_P2.54mm_Vertical.step").write_text("x", encoding="utf-8")
            model_path, reason = resolve_bare_header_model(2, 5, 2.54, library_root)
            self.assertIsNotNone(model_path)
            self.assertIn("generic", reason.lower())

    def test_missing_file_reported_not_fabricated(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            model_path, reason = resolve_bare_header_model(2, 5, 2.54, Path(directory))
            self.assertIsNone(model_path)
            self.assertIn("does not exist", reason)


class ResolveChipPassiveModelTest(unittest.TestCase):
    def test_unsupported_prefix_reports_reason(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            model_path, reason = resolve_chip_passive_model("D1", "0603", Path(directory))
            self.assertIsNone(model_path)
            self.assertIn("prefix", reason)

    def test_resolves_when_file_exists(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            library_root = Path(directory)
            folder = library_root / "Resistor_SMD.3dshapes"
            folder.mkdir()
            (folder / "R_0603_1608Metric.step").write_text("fake step", encoding="utf-8")

            model_path, reason = resolve_chip_passive_model("R87", "0603", library_root)

            self.assertIsNotNone(model_path)
            self.assertTrue(model_path.endswith("R_0603_1608Metric.step"))

    def test_missing_file_is_reported_not_fabricated(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            model_path, reason = resolve_chip_passive_model("R87", "0603", Path(directory))
            self.assertIsNone(model_path)
            self.assertIn("does not exist", reason)


class ResolveSot23ModelTest(unittest.TestCase):
    def test_bare_variant_uses_bare_filename(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            library_root = Path(directory)
            folder = library_root / "Package_TO_SOT_SMD.3dshapes"
            folder.mkdir()
            (folder / "SOT-23.step").write_text("fake step", encoding="utf-8")

            model_path, _ = resolve_sot23_model("", library_root)
            self.assertIsNotNone(model_path)
            self.assertTrue(model_path.endswith("SOT-23.step"))

    def test_pin_count_variant_uses_suffixed_filename(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            library_root = Path(directory)
            folder = library_root / "Package_TO_SOT_SMD.3dshapes"
            folder.mkdir()
            (folder / "SOT-23-5.step").write_text("fake step", encoding="utf-8")

            model_path, _ = resolve_sot23_model("5", library_root)
            self.assertIsNotNone(model_path)
            self.assertTrue(model_path.endswith("SOT-23-5.step"))


class ResolveAllTest(unittest.TestCase):
    def test_end_to_end_resolution_status_per_component(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            library_root = Path(directory)
            (library_root / "Resistor_SMD.3dshapes").mkdir()
            (library_root / "Resistor_SMD.3dshapes" / "R_0603_1608Metric.step").write_text("x", encoding="utf-8")

            components = [make_component("R1"), make_component("U1"), make_component("BH1")]
            bom_by_reference = {
                "R1": {"Description": "RES 10K 0603", "MFG": "VISHAY", "MFG_PN": "X"},
                "U1": {"Description": "IC MCU VFBGA184", "MFG": "NXP", "MFG_PN": "Y"},
            }

            results = resolve_all(components, bom_by_reference, library_root)
            by_ref = {row["reference"]: row for row in results}

            self.assertEqual(by_ref["R1"]["status"], "matched_official_kicad_model")
            self.assertEqual(by_ref["U1"]["status"], "no_recognized_package_code")
            self.assertEqual(by_ref["BH1"]["status"], "no_recognized_package_code")
            self.assertIn("not found in BOM", by_ref["BH1"]["evidence"])


if __name__ == "__main__":
    unittest.main()
