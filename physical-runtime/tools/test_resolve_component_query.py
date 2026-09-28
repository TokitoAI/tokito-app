#!/usr/bin/env python3
"""Tests for human-request -> canonical component identity resolution."""

from __future__ import annotations

import unittest

from resolve_component_query import resolve_query


def component(reference: str, cls: str, description: str, part_number: str | None = None, renderable: bool = True) -> dict:
    return {
        "component_id": f"nxp-frdm-mcxn236:C:{reference}",
        "reference": reference,
        "component_class": cls,
        "bom_part_number": part_number,
        "bom_description": description,
        "board_position_mm": {"x": 1.0, "y": -2.0},
        "side": "top",
        "glb_selector": f"ref:{reference}" if renderable else None,
    }


COMPONENT_MAP = {
    "components": [
        component("U1", "integrated_circuit", "IC MCU LOW POWER 150MHz ARM", "MCXN236VDFT"),
        component("U8", "integrated_circuit", "IC MCU ARM FLASH 640KB SRAM", "LPC55S69JEV98"),
        component("U10", "integrated_circuit", "IC ACCELEROMETER 3-AXIS 12BIT", "FXLS8974CFR3"),
        component("R87", "resistor", "RES 10K 0603", "RC0603"),
        component("R88", "resistor", "RES 1K 0402", "RC0402"),
        component("D13", "diode_or_led", "LED RED/GRN/BL TRI 20MA SMT", "MHPA2525RGBDT-S"),
        component("TP5", "test_point", "TEST POINT PAD, NO PART TO ORDER", None, renderable=False),
    ]
}

BOARD_REFERENCE = {
    "landmarks": [
        {"landmark_id": "mcxn236vdft", "category": "processor", "display_name": "MCXN236VDFT", "identifiers": {"reference_designator": "U1"}},
        {"landmark_id": "mcu-link-debugger", "category": "debugger", "display_name": "MCU-Link Debugger", "identifiers": {"guide_label": "MCU-Link Debugger"}},
        {"landmark_id": "mikrobus-headers", "category": "header", "display_name": "mikroBUS headers", "identifiers": {"reference_designators": "J5,J6"}},
        {"landmark_id": "external-flash", "category": "memory", "display_name": "External Flash", "identifiers": {"part_number": "FXLS8974CFR3"}},
    ]
}


class ResolveQueryTest(unittest.TestCase):
    def test_explicit_reference_designator_wins(self) -> None:
        result = resolve_query("find R87 please", COMPONENT_MAP, BOARD_REFERENCE)
        self.assertEqual(result["status"], "resolved")
        self.assertEqual(result["reference"], "R87")
        self.assertEqual(result["glb_selector"], "ref:R87")
        self.assertEqual(result["match_method"], "reference_designator")

    def test_main_chip_disambiguated_by_reviewed_landmark_category(self) -> None:
        # two MCUs match the role; only the reviewed map designates a processor
        result = resolve_query("where is the main chip", COMPONENT_MAP, BOARD_REFERENCE)
        self.assertEqual(result["status"], "resolved")
        self.assertEqual(result["reference"], "U1")
        self.assertEqual(result["match_method"], "role_narrowed_by_landmark_category")
        self.assertIn("processor", result["evidence"])

    def test_main_chip_stays_ambiguous_without_the_reviewed_map(self) -> None:
        # no board reference means no authority to choose between two MCUs
        result = resolve_query("where is the main chip", COMPONENT_MAP, None)
        self.assertEqual(result["status"], "ambiguous")
        self.assertEqual({c["reference"] for c in result["candidates"]}, {"U1", "U8"})

    def test_landmark_name_match(self) -> None:
        result = resolve_query("show me the mcxn236vdft", COMPONENT_MAP, BOARD_REFERENCE)
        self.assertEqual(result["status"], "resolved")
        self.assertEqual(result["reference"], "U1")

    def test_landmark_naming_multiple_components_is_ambiguous(self) -> None:
        board = {"landmarks": [{"landmark_id": "pair", "category": "header", "display_name": "pair headers", "identifiers": {"reference_designators": "R87,R88"}}]}
        result = resolve_query("the pair headers", COMPONENT_MAP, board)
        self.assertEqual(result["status"], "ambiguous")
        self.assertEqual(len(result["candidates"]), 2)

    def test_part_number_in_request(self) -> None:
        # LPC55S69JEV98 is not named by any landmark, so this exercises the
        # BOM part-number route rather than the higher-priority landmark one
        result = resolve_query("what is LPC55S69JEV98", COMPONENT_MAP, BOARD_REFERENCE)
        self.assertEqual(result["status"], "resolved")
        self.assertEqual(result["reference"], "U8")
        self.assertEqual(result["match_method"], "bom_part_number")

    def test_landmark_name_outranks_part_number(self) -> None:
        # the reviewed map is the naming authority, so a phrase that is both
        # a landmark display name and a part number resolves via the landmark
        result = resolve_query("what is MCXN236VDFT", COMPONENT_MAP, BOARD_REFERENCE)
        self.assertEqual(result["reference"], "U1")
        self.assertEqual(result["match_method"], "board_reference_landmark")

    def test_unique_role_resolves(self) -> None:
        result = resolve_query("show me the accelerometer", COMPONENT_MAP, BOARD_REFERENCE)
        self.assertEqual(result["status"], "resolved")
        self.assertEqual(result["reference"], "U10")

    def test_many_matches_are_ambiguous_not_guessed(self) -> None:
        result = resolve_query("where is a resistor", COMPONENT_MAP, BOARD_REFERENCE)
        self.assertEqual(result["status"], "ambiguous")
        self.assertEqual(result["candidate_count"], 2)

    def test_unknown_request_is_unresolved(self) -> None:
        result = resolve_query("where is the antenna tuner", COMPONENT_MAP, BOARD_REFERENCE)
        self.assertEqual(result["status"], "unresolved")

    def test_non_renderable_component_never_offered_by_role(self) -> None:
        # a bare test point has no body, so it must not be a role candidate
        result = resolve_query("show me a test point", COMPONENT_MAP, BOARD_REFERENCE)
        self.assertNotEqual(result.get("reference"), "TP5")

    def test_unknown_designator_falls_through_to_role(self) -> None:
        result = resolve_query("find R999 resistor", COMPONENT_MAP, BOARD_REFERENCE)
        self.assertEqual(result["status"], "ambiguous")


if __name__ == "__main__":
    unittest.main()
