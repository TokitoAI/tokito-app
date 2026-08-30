#!/usr/bin/env python3
"""Tests for the component 3D model resolver."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from component_model_resolver import (
    apply_manual_overrides,
    registered_model_transform,
    resolve_all,
    resolve_bare_header_model,
    resolve_chip_passive_model,
    resolve_led_chip_model,
    resolve_sot23_model,
    resolve_sot363_model,
    resolve_tht_socket_model,
)
from xlsx_bom_parser import (
    extract_bare_header_spec,
    extract_chip_package_code,
    extract_sot23_variant,
    extract_tht_socket_spec,
    has_sod523_package,
    has_sot363_package,
    is_led_description,
)


def make_component(reference: str, x: float = 0.0, y: float = 0.0, rotation: float = 0.0, side: str = "top") -> dict:
    return {"reference_designator": reference, "centroid": {"x": x, "y": y}, "rotation_degrees": rotation, "side": side, "footprint_bbox_mm": {"width": 1.5, "height": 0.6}}


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


class ExtractThtSocketSpecTest(unittest.TestCase):
    def test_extracts_shrouded_vertical_socket(self) -> None:
        self.assertEqual(
            extract_tht_socket_spec("CON 2X8 SKT SHRD TH 100MIL CTR 345H AU 126L"),
            (2, 8, 2.54, False),
        )

    def test_extracts_unshrouded_single_row_socket(self) -> None:
        self.assertEqual(
            extract_tht_socket_spec("CON 1X8 SKT TH 100MIL SP 344H AU 118L"),
            (1, 8, 2.54, False),
        )

    def test_extracts_right_angle_socket(self) -> None:
        self.assertEqual(
            extract_tht_socket_spec("CON 2X6 SKT RA TH 100MIL CTR 206H AU 124L"),
            (2, 6, 2.54, True),
        )

    def test_usb_connector_does_not_match(self) -> None:
        self.assertIsNone(extract_tht_socket_spec("CON 2X8 USB2.0_TYPE_C RCPT 0.85MM 144H AU 63L"))

    def test_audio_jack_does_not_match(self) -> None:
        self.assertIsNone(extract_tht_socket_spec("CON 5 MONO AUDIO JACK 3.5MM SKT TH RA 256H AG 79L"))


class ResolveThtSocketModelTest(unittest.TestCase):
    def test_resolves_vertical_socket_when_file_exists(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            library_root = Path(directory)
            folder = library_root / "Connector_PinSocket_2.54mm.3dshapes"
            folder.mkdir()
            (folder / "PinSocket_2x08_P2.54mm_Vertical.step").write_text("x", encoding="utf-8")
            model_path, reason = resolve_tht_socket_model(2, 8, 2.54, False, library_root)
            self.assertIsNotNone(model_path)
            self.assertIn("unshrouded approximation", reason)

    def test_right_angle_uses_horizontal_model(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            library_root = Path(directory)
            folder = library_root / "Connector_PinSocket_2.54mm.3dshapes"
            folder.mkdir()
            (folder / "PinSocket_2x06_P2.54mm_Horizontal.step").write_text("x", encoding="utf-8")
            model_path, _ = resolve_tht_socket_model(2, 6, 2.54, True, library_root)
            self.assertIsNotNone(model_path)
            self.assertTrue(model_path.endswith("PinSocket_2x06_P2.54mm_Horizontal.step"))

    def test_missing_file_reported_not_fabricated(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            model_path, reason = resolve_tht_socket_model(2, 16, 2.54, False, Path(directory))
            self.assertIsNone(model_path)
            self.assertIn("does not exist", reason)


class ModelRotateZTest(unittest.TestCase):
    def test_socket_with_local_x_pads_needs_90(self) -> None:
        from component_model_resolver import model_rotate_z_deg
        # J1-style: pad rows along local X, but PinSocket canonical axis is Y
        self.assertEqual(model_rotate_z_deg({"width": 19.43, "height": 4.19}, "Y"), 90.0)

    def test_socket_with_local_y_pads_is_aligned(self) -> None:
        from component_model_resolver import model_rotate_z_deg
        self.assertEqual(model_rotate_z_deg({"width": 4.06, "height": 39.62}, "Y"), 0.0)

    def test_passive_with_local_y_pads_needs_90(self) -> None:
        from component_model_resolver import model_rotate_z_deg
        self.assertEqual(model_rotate_z_deg({"width": 0.6, "height": 1.7}, "X"), 90.0)

    def test_near_square_is_exempt(self) -> None:
        from component_model_resolver import model_rotate_z_deg
        self.assertEqual(model_rotate_z_deg({"width": 4.22, "height": 4.22}, "Y"), 0.0)

    def test_invalid_axis_raises(self) -> None:
        from component_model_resolver import model_rotate_z_deg
        with self.assertRaises(ValueError):
            model_rotate_z_deg({"width": 1.0, "height": 2.0}, "Z")


class ResolveSot323ModelTest(unittest.TestCase):
    def test_resolves_when_file_exists(self) -> None:
        from component_model_resolver import resolve_sot323_model
        from xlsx_bom_parser import has_sot323_package

        self.assertTrue(has_sot323_package("TRANS MOSFET N-CH 30V .1A SOT-323"))
        self.assertFalse(has_sot323_package("DIODE TVS UNIDIR 3.3VWM 12VC SOT363"))
        with tempfile.TemporaryDirectory() as directory:
            library_root = Path(directory)
            folder = library_root / "Package_TO_SOT_SMD.3dshapes"
            folder.mkdir()
            (folder / "SOT-323_SC-70.step").write_text("x", encoding="utf-8")
            model_path, _ = resolve_sot323_model(library_root)
            self.assertIsNotNone(model_path)


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
            # without footprint geometry, a resolved model's orientation must
            # be flagged as heuristic, never presented as registered
            self.assertEqual(by_ref["R1"]["model_registration"]["method"], "bbox_axis_heuristic_fallback")
            self.assertEqual(by_ref["R1"]["model_offset_x_mm"], 0.0)


class RegisteredModelTransformTest(unittest.TestCase):
    def _library(self, directory: str) -> Path:
        root = Path(directory)
        model_dir = root / "3dmodels" / "Connector_PinSocket_2.54mm.3dshapes"
        footprint_dir = root / "footprints" / "Connector_PinSocket_2.54mm.pretty"
        model_dir.mkdir(parents=True)
        footprint_dir.mkdir(parents=True)
        (footprint_dir / "PinSocket_2x02_P2.54mm_Vertical.kicad_mod").write_text(
            '(footprint "PinSocket_2x02_P2.54mm_Vertical"\n'
            '  (pad "1" thru_hole rect (at 0 0) (size 1.7 1.7) (layers "*.Cu"))\n'
            '  (pad "2" thru_hole oval (at -2.54 0) (size 1.7 1.7) (layers "*.Cu"))\n'
            '  (pad "3" thru_hole oval (at 0 2.54) (size 1.7 1.7) (layers "*.Cu"))\n'
            '  (pad "4" thru_hole oval (at -2.54 2.54) (size 1.7 1.7) (layers "*.Cu"))\n'
            ")",
            encoding="utf-8",
        )
        return model_dir / "PinSocket_2x02_P2.54mm_Vertical.step"

    def test_registers_exact_rotation_from_geometry(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            model = self._library(directory)
            # rows extend +X from pin 1: the J1 layout, which needs 270
            geometry = {
                "J1": {
                    "pads": {"1": (0.0, 0.0), "2": (0.0, 2.54), "3": (2.54, 0.0), "4": (2.54, 2.54)},
                    "body_overhang": None,
                    "pad_field_center": (1.27, 1.27),
                }
            }
            transform = registered_model_transform("J1", str(model), {"width": 4.0, "height": 2.5}, "Y", geometry)
        self.assertEqual(transform["model_rotate_z_deg"], 270.0)
        self.assertEqual(transform["model_registration"]["method"], "numbered_pads")

    def test_falls_back_flagged_without_geometry(self) -> None:
        transform = registered_model_transform("J1", "/nowhere/model.step", {"width": 4.0, "height": 1.0}, "Y", None)
        self.assertEqual(transform["model_registration"]["method"], "bbox_axis_heuristic_fallback")
        self.assertIn("--kicad-pcb", transform["model_registration"]["reason"])
        self.assertEqual(transform["model_rotate_z_deg"], 90.0)


class ApplyManualOverridesTest(unittest.TestCase):
    def test_centered_placeholder_box_is_anchored_to_pad_field_center(self) -> None:
        rows = [
            {
                "reference": "SW1",
                "model_path": None,
                "model_kind": None,
                "model_rotate_z_deg": 0.0,
                "model_offset_x_mm": 0.0,
                "model_offset_y_mm": 0.0,
                "model_registration": None,
                "status": "no_recognized_package_code",
            }
        ]
        overrides = [{"reference": "SW1", "model_path": "/build/box.wrl", "status": "manual_dimension_accurate_placeholder"}]
        geometry = {"SW1": {"pads": {"1": (0.0, 0.0), "2": (3.0, 1.0)}, "body_overhang": None, "pad_field_center": (1.5, 0.5)}}

        merged = apply_manual_overrides(rows, overrides, geometry)[0]

        self.assertEqual(merged["model_path"], "/build/box.wrl")
        self.assertEqual(merged["model_offset_x_mm"], 1.5)
        self.assertEqual(merged["model_offset_y_mm"], 0.5)
        self.assertEqual(merged["model_registration"]["method"], "centered_model_at_body_outline")

    def test_body_outline_anchor_preferred_over_pad_field_center(self) -> None:
        # a right-angle jack's body extends far beyond its pins: the anchor
        # must be the footprint's own fab outline centre, and the elongated
        # box must be rotated to align with the elongated body outline
        rows = [
            {
                "reference": "J14",
                "model_path": None,
                "model_kind": None,
                "model_rotate_z_deg": 0.0,
                "model_offset_x_mm": 0.0,
                "model_offset_y_mm": 0.0,
                "model_registration": None,
                "status": "no_recognized_package_code",
            }
        ]
        overrides = [{"reference": "J14", "model_path": "/build/Jack_12.4x7x6mm_datasheet_box.wrl", "status": "manual_dimension_accurate_placeholder"}]
        geometry = {
            "J14": {
                # near-square pad field: the body outline must decide the axis
                "pads": {"1": (0.0, 0.0), "2": (2.0, 0.0), "3": (0.0, 2.0), "4": (2.0, 2.0)},
                "body_overhang": (0.0, 5.0),
                "pad_field_center": (1.0, 1.0),
                # body outline elongated along Y while the box's long axis is X
                "body_bbox": {"center": (1.0, 5.0), "width": 7.0, "height": 12.4, "layer": "F.Fab"},
            }
        }

        merged = apply_manual_overrides(rows, overrides, geometry)[0]

        self.assertEqual(merged["model_offset_x_mm"], 1.0)
        self.assertEqual(merged["model_offset_y_mm"], 5.0)
        self.assertEqual(merged["model_rotate_z_deg"], 90.0)

    def test_pad_axis_outranks_body_outline_for_box_rotation(self) -> None:
        # a 2-pad diode's electrical axis is its pad axis, even when band
        # markings make the fab outline bbox slightly taller than wide
        from component_model_resolver import centered_box_rotation

        geometry = {
            "pads": {"A": (-0.95, 0.0), "C": (0.95, 0.0)},
            "body_bbox": {"center": (0.0, 0.9), "width": 2.7, "height": 3.16, "layer": "F.Fab"},
        }
        rotation = centered_box_rotation("/m/MicroSMP_2.2x1.3x0.68mm_datasheet_box.wrl", geometry)
        self.assertEqual(rotation, 0.0)

    def test_override_for_unknown_reference_raises(self) -> None:
        with self.assertRaises(ValueError):
            apply_manual_overrides([], [{"reference": "ZZ9", "model_path": "/m.step"}], None)


if __name__ == "__main__":
    unittest.main()
