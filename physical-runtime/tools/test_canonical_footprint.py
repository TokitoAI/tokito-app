#!/usr/bin/env python3
"""Tests for canonical-footprint model registration.

The pad layouts here mirror the real registration cases found on the
FRDM-MCXN236 Allegro import: pin-1-at-origin sockets whose pad fields
extend in different directions per footprint (rows along +X, -X, +Y),
plus mirrored pin numbering that no z-rotation can reproduce and must be
registered geometrically with body-side disambiguation.
"""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from canonical_footprint import (
    body_overhang_vector,
    canonical_footprint_path,
    load_board_footprint_geometry,
    parse_canonical_footprint,
    register_model,
    register_pad_geometry,
    register_pads,
)
from kicad_pcb_placements import parse, tokenize


def canonical_2x3_socket() -> dict[str, tuple[float, float]]:
    """KiCad PinSocket convention: pin1 at origin, pin2 at (-2.54, 0),
    rows extending +Y."""
    return {
        "1": (0.0, 0.0),
        "2": (-2.54, 0.0),
        "3": (0.0, 2.54),
        "4": (-2.54, 2.54),
        "5": (0.0, 5.08),
        "6": (-2.54, 5.08),
    }


def rotate_layout(pads: dict[str, tuple[float, float]], degrees: int) -> dict[str, tuple[float, float]]:
    import math

    r = math.radians(degrees)
    return {
        n: (
            round(p[0] * math.cos(r) - p[1] * math.sin(r), 6),
            round(p[0] * math.sin(r) + p[1] * math.cos(r), 6),
        )
        for n, p in pads.items()
    }


class RegisterPadsTest(unittest.TestCase):
    def test_identity_layout_registers_at_zero(self) -> None:
        canon = canonical_2x3_socket()
        result = register_pads(dict(canon), canon)
        self.assertEqual(result["rotate_z_deg"], 0)
        self.assertAlmostEqual(result["offset_x_mm"], 0.0)
        self.assertAlmostEqual(result["offset_y_mm"], 0.0)
        self.assertEqual(result["method"], "numbered_pads")

    def test_rows_along_plus_x_needs_270_not_90(self) -> None:
        # the J1/J3 bug: rows extending +X from pin 1 register at 270; the
        # old bbox heuristic said 90, which swings a pin-1-origin model's
        # body to the wrong side of the footprint entirely.
        canon = canonical_2x3_socket()
        ours = rotate_layout(canon, 270)
        result = register_pads(ours, canon)
        self.assertEqual(result["rotate_z_deg"], 270)
        self.assertAlmostEqual(result["offset_x_mm"], 0.0)
        self.assertLess(result["max_residual_mm"], 1e-6)

    def test_rows_along_minus_x_registers_at_90(self) -> None:
        canon = canonical_2x3_socket()
        ours = rotate_layout(canon, 90)
        result = register_pads(ours, canon)
        self.assertEqual(result["rotate_z_deg"], 90)

    def test_translation_is_recovered(self) -> None:
        canon = canonical_2x3_socket()
        ours = {n: (p[0] + 1.5, p[1] - 0.5) for n, p in canon.items()}
        result = register_pads(ours, canon)
        self.assertEqual(result["rotate_z_deg"], 0)
        self.assertAlmostEqual(result["offset_x_mm"], 1.5)
        self.assertAlmostEqual(result["offset_y_mm"], -0.5)

    def test_mirrored_numbering_fails_honestly(self) -> None:
        canon = canonical_2x3_socket()
        ours = {n: (-p[0], p[1]) for n, p in canon.items()}  # mirror about Y
        self.assertIsNone(register_pads(ours, canon))

    def test_single_pad_registers_translation_only(self) -> None:
        result = register_pads({"1": (0.0, 0.0)}, {"1": (0.5, -0.25)})
        self.assertEqual(result["method"], "single_pad")
        self.assertEqual(result["rotate_z_deg"], 0)
        self.assertAlmostEqual(result["offset_x_mm"], -0.5)
        self.assertAlmostEqual(result["offset_y_mm"], 0.25)

    def test_disjoint_pad_numbers_fail(self) -> None:
        self.assertIsNone(register_pads({"A1": (0, 0), "A2": (1, 0)}, {"1": (0, 0), "2": (1, 0)}))


class RegisterPadGeometryTest(unittest.TestCase):
    def test_mirrored_grid_disambiguated_by_body_side(self) -> None:
        # the J7 Pmod bug: mirrored numbering, and the 2xN grid maps onto
        # itself under both 0 and 180 -- only the body-overhang direction
        # (ours +X per our fab outline, canonical -X per KiCad's) decides.
        canon = canonical_2x3_socket()
        ours = {n: (-p[0], p[1]) for n, p in canon.items()}
        result = register_pad_geometry(ours, canon, our_body_overhang=(5.0, 0.0), canonical_body_overhang=(-5.0, 0.0))
        self.assertIsNotNone(result)
        self.assertEqual(result["rotate_z_deg"], 180)
        self.assertEqual(result["method"], "pad_geometry_with_body_side")
        self.assertAlmostEqual(result["offset_x_mm"], 0.0)
        self.assertAlmostEqual(result["offset_y_mm"], 5.08)

    def test_unique_rotation_needs_no_body_evidence(self) -> None:
        # the Q1 SOT case: an asymmetric 3-pad triangle admits exactly one
        # rotation, so mirrored numbering is survivable without a body side.
        canon = {"1": (-0.8875, -0.65), "2": (-0.8875, 0.65), "3": (0.8875, 0.0)}
        ours = {"1": (0.65, 1.06), "2": (-0.65, 1.06), "3": (0.0, -1.06)}
        result = register_pad_geometry(ours, canon, None, None)
        self.assertIsNotNone(result)
        self.assertEqual(result["rotate_z_deg"], 270)
        self.assertEqual(result["method"], "pad_geometry_unique")

    def test_ambiguous_without_body_evidence_returns_none(self) -> None:
        canon = canonical_2x3_socket()
        ours = {n: (-p[0], p[1]) for n, p in canon.items()}
        self.assertIsNone(register_pad_geometry(ours, canon, None, None))

    def test_symmetric_body_takes_smallest_rotation(self) -> None:
        canon = canonical_2x3_socket()
        ours = {n: (-p[0], p[1]) for n, p in canon.items()}
        result = register_pad_geometry(ours, canon, our_body_overhang=(0.1, 0.0), canonical_body_overhang=(0.0, 0.1))
        self.assertIsNotNone(result)
        self.assertEqual(result["method"], "pad_geometry_symmetric_body")

    def test_incompatible_geometry_returns_none(self) -> None:
        canon = canonical_2x3_socket()
        ours = {n: (p[0] * 2.0, p[1] * 2.0) for n, p in canon.items()}  # wrong pitch
        self.assertIsNone(register_pad_geometry(ours, canon, None, None))


FOOTPRINT_FIXTURE = """
(footprint "PinSocket_2x03_P2.54mm_Vertical"
  (layer "F.Cu")
  (fp_line (start -5.0 -1.27) (end 1.27 -1.27) (layer "F.Fab") (width 0.1))
  (fp_line (start -5.0 6.35) (end 1.27 6.35) (layer "F.Fab") (width 0.1))
  (pad "1" thru_hole rect (at 0 0) (size 1.7 1.7) (drill 1) (layers "*.Cu"))
  (pad "2" thru_hole oval (at -2.54 0) (size 1.7 1.7) (drill 1) (layers "*.Cu"))
  (pad "3" thru_hole oval (at 0 2.54) (size 1.7 1.7) (drill 1) (layers "*.Cu"))
)
"""


class CanonicalFootprintParsingTest(unittest.TestCase):
    def test_parse_canonical_footprint_pads_and_overhang(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "PinSocket_2x03_P2.54mm_Vertical.kicad_mod"
            path.write_text(FOOTPRINT_FIXTURE, encoding="utf-8")
            parsed = parse_canonical_footprint(path)
        self.assertEqual(parsed["pads"]["2"], (-2.54, 0.0))
        # fab bbox center x is (-5.0 + 1.27)/2, pad centroid x is -0.846...
        self.assertLess(parsed["body_overhang"][0], -0.9)

    def test_body_overhang_none_without_graphics(self) -> None:
        tree = parse(tokenize('(footprint "X" (pad "1" smd rect (at 0 0) (size 1 1) (layers "F.Cu")))'))
        self.assertIsNone(body_overhang_vector(tree, {"1": (0.0, 0.0)}))


class CanonicalFootprintPathTest(unittest.TestCase):
    def test_maps_3dshapes_model_to_pretty_footprint(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model_dir = root / "3dmodels" / "Connector_PinSocket_2.54mm.3dshapes"
            footprint_dir = root / "footprints" / "Connector_PinSocket_2.54mm.pretty"
            footprint_dir.mkdir(parents=True)
            model_dir.mkdir(parents=True)
            footprint = footprint_dir / "PinSocket_2x03_P2.54mm_Vertical.kicad_mod"
            footprint.write_text(FOOTPRINT_FIXTURE, encoding="utf-8")
            resolved = canonical_footprint_path(str(model_dir / "PinSocket_2x03_P2.54mm_Vertical.step"))
            self.assertEqual(resolved, footprint)

    def test_non_library_model_maps_to_none(self) -> None:
        self.assertIsNone(canonical_footprint_path(r"F:\build\models\some_placeholder_box.wrl"))


class RegisterModelTest(unittest.TestCase):
    def test_end_to_end_against_authored_footprint_file(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model_dir = root / "3dmodels" / "Lib.3dshapes"
            footprint_dir = root / "footprints" / "Lib.pretty"
            footprint_dir.mkdir(parents=True)
            model_dir.mkdir(parents=True)
            (footprint_dir / "PinSocket_2x03_P2.54mm_Vertical.kicad_mod").write_text(FOOTPRINT_FIXTURE, encoding="utf-8")
            ours = rotate_layout({"1": (0.0, 0.0), "2": (-2.54, 0.0), "3": (0.0, 2.54)}, 270)
            result = register_model(ours, None, str(model_dir / "PinSocket_2x03_P2.54mm_Vertical.step"))
        self.assertIsNotNone(result)
        self.assertEqual(result["rotate_z_deg"], 270)
        self.assertEqual(result["method"], "numbered_pads")
        self.assertIn("canonical_footprint", result)


BOARD_FIXTURE = """
(kicad_pcb
  (version 20250114)
  (footprint ""
    (layer "F.Cu")
    (at 10 20 90)
    (property "Reference" "J1" (at 0 0 0) (layer "F.SilkS"))
    (fp_line (start -1 -1) (end 8 3) (layer "F.Fab") (width 0.1))
    (pad "1" thru_hole rect (at 0 0) (size 1.7 1.7) (drill 1) (layers "*.Cu"))
    (pad "2" thru_hole oval (at 2.54 0) (size 1.7 1.7) (drill 1) (layers "*.Cu"))
  )
)
"""


class LoadBoardFootprintGeometryTest(unittest.TestCase):
    def test_pads_overhang_and_field_center(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "board.kicad_pcb"
            path.write_text(BOARD_FIXTURE, encoding="utf-8")
            geometry = load_board_footprint_geometry(path)
        self.assertIn("J1", geometry)
        self.assertEqual(geometry["J1"]["pads"]["2"], (2.54, 0.0))
        self.assertEqual(geometry["J1"]["pad_field_center"], (1.27, 0.0))
        self.assertIsNotNone(geometry["J1"]["body_overhang"])


if __name__ == "__main__":
    unittest.main()
