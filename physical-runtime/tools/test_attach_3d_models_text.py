#!/usr/bin/env python3
"""Tests for the text-splice 3D model attacher."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from attach_3d_models_text import attach_models, build_model_sexpr, find_footprint_spans
from kicad_pcb_placements import parse_pcb_tree, find_all


FIXTURE = """
(kicad_pcb
  (version 20250114)
  (footprint ""
    (layer "F.Cu")
    (uuid "aaa")
    (at 5.0 5.0 0)
    (property "Reference" "R1"
      (at 0 0 0)
      (layer "F.SilkS")
    )
  )
  (footprint ""
    (layer "F.Cu")
    (uuid "bbb")
    (at 8.0 2.0 0)
    (property "Reference" "U1"
      (at 0 0 0)
      (layer "F.SilkS")
    )
    (property "Reference" "${REFERENCE}"
      (at 0 0 0)
      (layer "B.Fab")
      (hide yes)
    )
  )
)
"""


class FindFootprintSpansTest(unittest.TestCase):
    def test_finds_both_footprint_spans(self) -> None:
        spans = find_footprint_spans(FIXTURE)
        self.assertEqual(len(spans), 2)
        for start, end in spans:
            self.assertEqual(FIXTURE[start : start + 11], "(footprint ")
            self.assertEqual(FIXTURE[end], ")")

    def test_ignores_parens_inside_quoted_strings(self) -> None:
        text = '(kicad_pcb (footprint "weird(name)" (uuid "x")))'
        spans = find_footprint_spans(text)
        self.assertEqual(len(spans), 1)
        start, end = spans[0]
        # the span's closing paren must be the footprint's own -- the
        # parenthesis inside "weird(name)" must not have confused the
        # depth tracking into closing early.
        self.assertEqual(text[end], ")")
        self.assertEqual(text[end + 1], ")")  # the outer kicad_pcb's closing paren follows immediately


class BuildModelSexprTest(unittest.TestCase):
    def test_escapes_backslashes_in_windows_paths(self) -> None:
        result = build_model_sexpr(r"C:\models\R_0603.step")
        self.assertIn(r'"C:\\models\\R_0603.step"', result)

    def test_contains_offset_scale_rotate(self) -> None:
        result = build_model_sexpr("model.step")
        self.assertIn("(offset (xyz 0 0 0))", result)
        self.assertIn("(scale (xyz 1 1 1))", result)
        self.assertIn("(rotate (xyz 0 0 0))", result)

    def test_rotation_is_written_when_supplied(self) -> None:
        result = build_model_sexpr("model.step", rotate_z_deg=90.0)
        self.assertIn("(rotate (xyz 0 0 90))", result)

    def test_offset_is_written_when_supplied(self) -> None:
        result = build_model_sexpr("model.step", offset_x_mm=2.975, offset_y_mm=-12.7)
        self.assertIn("(offset (xyz 2.975 -12.7 0))", result)


class AttachModelsTest(unittest.TestCase):
    def test_placeholder_reference_rewritten_to_real_designator(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            input_path = Path(directory) / "board.kicad_pcb"
            input_path.write_text(FIXTURE, encoding="utf-8")
            output_path = Path(directory) / "populated.kicad_pcb"

            result = attach_models(input_path, [], output_path)

            self.assertEqual(result["placeholder_references_repaired"], 1)
            output_text = output_path.read_text(encoding="utf-8")
            self.assertNotIn("${REFERENCE}", output_text)
            # U1's placeholder became a second real "U1" reference property
            self.assertEqual(output_text.count('(property "Reference" "U1"'), 2)

    def test_model_rotation_flows_from_resolution_row(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            input_path = Path(directory) / "board.kicad_pcb"
            input_path.write_text(FIXTURE, encoding="utf-8")
            output_path = Path(directory) / "populated.kicad_pcb"

            rows = [{"reference": "R1", "model_path": "/m/R.step", "model_rotate_z_deg": 90.0}]
            attach_models(input_path, rows, output_path)

            output_text = output_path.read_text(encoding="utf-8")
            self.assertIn("(rotate (xyz 0 0 90))", output_text)

    def test_offset_y_sign_flips_at_the_file_boundary(self) -> None:
        # resolution rows carry the offset in footprint-local FILE
        # coordinates (pad-frame, y down); KiCad's stored model offset y is
        # up-positive, so the writer must negate exactly once.
        with tempfile.TemporaryDirectory() as directory:
            input_path = Path(directory) / "board.kicad_pcb"
            input_path.write_text(FIXTURE, encoding="utf-8")
            output_path = Path(directory) / "populated.kicad_pcb"

            rows = [
                {
                    "reference": "R1",
                    "model_path": "/m/R.step",
                    "model_rotate_z_deg": 180.0,
                    "model_offset_x_mm": 2.54,
                    "model_offset_y_mm": 12.7,
                }
            ]
            attach_models(input_path, rows, output_path)

            output_text = output_path.read_text(encoding="utf-8")
            self.assertIn("(offset (xyz 2.54 -12.7 0))", output_text)
            self.assertIn("(rotate (xyz 0 0 180))", output_text)

    def test_splices_model_only_into_matched_footprint(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            input_path = Path(directory) / "board.kicad_pcb"
            input_path.write_text(FIXTURE, encoding="utf-8")
            output_path = Path(directory) / "populated.kicad_pcb"

            resolution_rows = [
                {"reference": "R1", "model_path": "/models/R_0603.step"},
                {"reference": "U1", "model_path": None},
            ]

            result = attach_models(input_path, resolution_rows, output_path)

            self.assertEqual(result["footprint_count"], 2)
            self.assertEqual(result["models_attached"], 1)

            output_text = output_path.read_text(encoding="utf-8")
            self.assertIn('(model "/models/R_0603.step"', output_text)
            self.assertEqual(output_text.count("(model "), 1)

    def test_output_is_still_valid_parseable_kicad_pcb(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            input_path = Path(directory) / "board.kicad_pcb"
            input_path.write_text(FIXTURE, encoding="utf-8")
            output_path = Path(directory) / "populated.kicad_pcb"

            resolution_rows = [
                {"reference": "R1", "model_path": "/models/R_0603.step"},
                {"reference": "U1", "model_path": "/models/SOT-23.step"},
            ]
            attach_models(input_path, resolution_rows, output_path)

            tree = parse_pcb_tree(output_path)
            footprints = find_all(tree, "footprint")
            self.assertEqual(len(footprints), 2)
            models = find_all(tree, "model")
            self.assertEqual(len(models), 2)

    def test_untouched_bytes_outside_insertions_are_preserved(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            input_path = Path(directory) / "board.kicad_pcb"
            input_path.write_text(FIXTURE, encoding="utf-8")
            output_path = Path(directory) / "populated.kicad_pcb"

            resolution_rows = [{"reference": "U1", "model_path": "/models/SOT-23.step"}]
            attach_models(input_path, resolution_rows, output_path)

            output_text = output_path.read_text(encoding="utf-8")
            # everything before the U1 footprint's closing paren must be
            # byte-identical to the original (R1's block is untouched)
            r1_end_in_original = FIXTURE.index('"R1"')
            self.assertEqual(output_text[:r1_end_in_original], FIXTURE[:r1_end_in_original])


if __name__ == "__main__":
    unittest.main()
