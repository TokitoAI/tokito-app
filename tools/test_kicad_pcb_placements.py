#!/usr/bin/env python3
"""Tests for KiCad PCB placement/reference-designator extraction."""

from __future__ import annotations

from pathlib import Path
import tempfile
import unittest

from kicad_pcb_placements import extract_placements


FIXTURE = """
(kicad_pcb
  (version 20250114)
  (footprint ""
    (layer "F.Cu")
    (uuid "aaa")
    (at 35.797007 -12.689002 180)
    (property "Reference" "R87"
      (at 0.635 1.93675 0)
      (layer "F.SilkS")
    )
    (property "Value" "10k"
      (at 0 0 180)
      (layer "F.Fab")
    )
  )
  (footprint ""
    (layer "B.Cu")
    (uuid "bbb")
    (at 56.752007 27.548002 90)
    (property "Reference" "U7"
      (at -1.651 0.03175 0)
      (layer "B.SilkS")
    )
    (property "Reference" "${REFERENCE}"
      (at -0.4064 0.00635 180)
      (layer "B.Fab")
      (hide yes)
    )
    (property "Value" ""
      (at 0 0 180)
      (layer "B.Fab")
    )
  )
  (footprint ""
    (layer "F.Cu")
    (uuid "ccc")
    (at 10.0 20.0 0)
  )
)
"""


class KicadPcbPlacementsTest(unittest.TestCase):
    def test_extracts_real_reference_and_skips_placeholder(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "board.kicad_pcb"
            path.write_text(FIXTURE, encoding="utf-8")

            placements = extract_placements(path)

            self.assertEqual(len(placements), 3)

            first = placements[0]
            self.assertEqual(first["reference"], "R87")
            self.assertFalse(first["is_placeholder_reference"])
            self.assertEqual(first["value"], "10k")
            self.assertEqual(first["side"], "top")
            self.assertAlmostEqual(first["x_mm"], 35.797007)
            self.assertAlmostEqual(first["y_mm"], -12.689002)
            self.assertAlmostEqual(first["rotation_deg"], 180)

            second = placements[1]
            self.assertEqual(second["reference"], "U7")
            self.assertFalse(second["is_placeholder_reference"])
            self.assertEqual(second["side"], "bottom")

    def test_missing_reference_is_marked_placeholder(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "board.kicad_pcb"
            path.write_text(FIXTURE, encoding="utf-8")

            placements = extract_placements(path)

            third = placements[2]
            self.assertEqual(third["reference"], "")
            self.assertTrue(third["is_placeholder_reference"])
            self.assertEqual(third["x_mm"], 10.0)
            self.assertEqual(third["y_mm"], 20.0)


if __name__ == "__main__":
    unittest.main()
