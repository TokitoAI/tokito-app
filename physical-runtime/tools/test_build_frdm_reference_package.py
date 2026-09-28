#!/usr/bin/env python3
"""Tests for the reference-package builder (issue #45)."""

from __future__ import annotations

from pathlib import Path
import tempfile
import unittest

from build_frdm_reference_package import (
    build_outline_polygon,
    build_reference_package,
    classify_unresolved_footprint,
    footprint_bounding_box_mm,
    parse_bom,
)


def _seg(x1, y1, x2, y2, kind="gr_line", mid=None):
    segment = {"type": kind, "start": {"x": x1, "y": y1}, "end": {"x": x2, "y": y2}}
    if mid is not None:
        segment["mid"] = {"x": mid[0], "y": mid[1]}
    return segment


class BuildOutlinePolygonTest(unittest.TestCase):
    def test_chains_out_of_order_and_flipped_segments(self) -> None:
        # square given out of order, one segment reversed
        segments = [
            _seg(0, 0, 10, 0),
            _seg(0, 10, 0, 0),
            _seg(10, 10, 0, 10),
            _seg(10, 10, 10, 0),  # reversed relative to the loop direction
        ]
        polygon = build_outline_polygon(segments)
        self.assertEqual(len(polygon), 4)
        xs = sorted(p["x"] for p in polygon)
        self.assertEqual(xs, [0, 0, 10, 10])

    def test_arc_contributes_points_on_its_true_circle(self) -> None:
        import math
        # semicircle of radius 5 around (5,0) from (0,0) through (5,5) to (10,0), closed by a line
        segments = [
            _seg(0, 0, 10, 0, kind="gr_arc", mid=(5, 5)),
            _seg(10, 0, 0, 0),
        ]
        polygon = build_outline_polygon(segments, arc_samples=6)
        self.assertGreaterEqual(len(polygon), 8)
        for p in polygon:
            if p["y"] > 0.01:  # a sampled arc point
                self.assertAlmostEqual(math.hypot(p["x"] - 5, p["y"]), 5.0, places=6)

    def test_open_chain_raises(self) -> None:
        with self.assertRaises(ValueError):
            build_outline_polygon([_seg(0, 0, 10, 0), _seg(10, 0, 10, 10)])

    def test_zero_area_raises(self) -> None:
        with self.assertRaises(ValueError):
            build_outline_polygon([_seg(0, 0, 10, 0), _seg(10, 0, 5, 0), _seg(5, 0, 0, 0)])


PCB_FIXTURE = """
(kicad_pcb
  (version 20250114)
  (gr_line
    (start 0.0 0.0)
    (end 10.0 0.0)
    (layer "Edge.Cuts")
    (uuid "line1")
  )
  (gr_line
    (start 10.0 0.0)
    (end 10.0 10.0)
    (layer "Edge.Cuts")
    (uuid "line2")
  )
  (gr_line
    (start 10.0 10.0)
    (end 0.0 10.0)
    (layer "Edge.Cuts")
    (uuid "line3")
  )
  (gr_line
    (start 0.0 10.0)
    (end 0.0 0.0)
    (layer "Edge.Cuts")
    (uuid "line4")
  )
  (footprint ""
    (layer "F.Cu")
    (uuid "aaa")
    (at 5.0 5.0 0)
    (property "Reference" "R1"
      (at 0 0 0)
      (layer "F.SilkS")
    )
    (property "Value" "10k"
      (at 0 0 0)
      (layer "F.Fab")
    )
    (pad "1" smd rect
      (at -0.5 0 0)
      (size 0.5 0.6)
      (layers "F.Cu")
      (net 1 "N1")
    )
    (pad "2" smd rect
      (at 0.5 0 0)
      (size 0.5 0.6)
      (layers "F.Cu")
      (net 2 "N2")
    )
  )
  (footprint ""
    (layer "F.Cu")
    (uuid "bbb")
    (at 8.0 2.0 0)
    (pad "1" thru_hole circle
      (at 0 0 0)
      (size 2.5 2.5)
      (layers "*.Cu")
      (net 0 "")
    )
  )
  (footprint ""
    (layer "F.Cu")
    (uuid "ccc")
    (at 1.0 1.0 0)
    (pad "1" smd circle
      (at 0 0 0)
      (size 0.5 0.5)
      (layers "F.Cu")
      (net 0 "")
    )
  )
  (footprint ""
    (layer "F.Cu")
    (uuid "eee")
    (at 9.0 9.0 0)
    (pad "" smd circle
      (at 0 0 0)
      (size 1.524 1.524)
      (layers "F.Cu")
    )
  )
)
"""

BOM_FIXTURE = "1\t1\t123-45678\t\t\tR1\n"


class ClassifyUnresolvedFootprintTest(unittest.TestCase):
    def test_unnamed_no_net_smd_circle_is_fiducial_regardless_of_diameter(self) -> None:
        pads = [{"number": "", "pad_type": "smd", "shape": "circle", "size_w_mm": 1.524, "size_h_mm": 1.524, "net": None}]
        category, evidence = classify_unresolved_footprint(pads)
        self.assertEqual(category, "fiducial_candidate")
        self.assertIn("1.52mm", evidence)

    def test_named_smd_circle_with_net_at_same_diameter_is_not_a_fiducial(self) -> None:
        pads = [{"number": "1", "pad_type": "smd", "shape": "circle", "size_w_mm": 1.524, "size_h_mm": 1.524, "net": "N1"}]
        category, _ = classify_unresolved_footprint(pads)
        self.assertNotEqual(category, "fiducial_candidate")
        self.assertEqual(category, "single_pad_unclassified")

    def test_thru_hole_circle_is_mounting_hole(self) -> None:
        pads = [{"number": "1", "pad_type": "thru_hole", "shape": "circle", "size_w_mm": 2.5, "size_h_mm": 2.5, "net": None}]
        category, _ = classify_unresolved_footprint(pads)
        self.assertEqual(category, "mounting_hole_candidate")


class FootprintBoundingBoxTest(unittest.TestCase):
    def test_bbox_is_union_of_pad_extents(self) -> None:
        pads = [
            {"at_x_mm": -0.5, "at_y_mm": 0.0, "size_w_mm": 0.5, "size_h_mm": 0.6},
            {"at_x_mm": 0.5, "at_y_mm": 0.0, "size_w_mm": 0.5, "size_h_mm": 0.6},
        ]
        bbox = footprint_bounding_box_mm(pads)
        # left pad spans [-0.75, -0.25], right pad spans [0.25, 0.75] -> total width 1.5
        self.assertAlmostEqual(bbox["width"], 1.5)
        self.assertAlmostEqual(bbox["height"], 0.6)

    def test_no_pads_gives_zero_bbox(self) -> None:
        self.assertEqual(footprint_bounding_box_mm([]), {"width": 0.0, "height": 0.0})


class BuildFrdmReferencePackageTest(unittest.TestCase):
    def test_parse_bom_maps_refdes_to_part_number(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            bom_path = Path(directory) / "bom.txt"
            bom_path.write_text(
                "1\t4\t510-75297\t\t\t\tBH1,BH2,BH3,BH4\n"
                "2\t6\t150-80128\t\t\t\tC1,C5,C32,C36,C40,C41\n",
                encoding="utf-8",
            )

            result = parse_bom(bom_path)

            self.assertEqual(result["BH1"], "510-75297")
            self.assertEqual(result["C41"], "150-80128")
            self.assertEqual(len(result), 10)

    def test_build_reference_package_from_real_shaped_inputs(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            pcb_path = Path(directory) / "board.kicad_pcb"
            pcb_path.write_text(PCB_FIXTURE, encoding="utf-8")
            bom_path = Path(directory) / "bom.txt"
            bom_path.write_text(BOM_FIXTURE, encoding="utf-8")
            brd_path = Path(directory) / "board.brd"
            brd_path.write_bytes(b"fake-allegro-board-bytes")

            bundle = build_reference_package(pcb_path, bom_path, brd_path, "test-board", "Rev X", "left", "test-evidence")

            self.assertEqual(bundle["schema_version"], {"major": 1, "minor": 0})
            self.assertEqual(bundle["board"]["board_id"], "test-board")
            self.assertEqual(bundle["board"]["revision"], "Rev X")
            self.assertEqual(len(bundle["board"]["outline"]), 4)
            self.assertEqual(bundle["board"]["handedness"], "left")
            self.assertEqual(len(bundle["board"]["outline_segments"]), 4)
            for point in bundle["board"]["outline"]:
                self.assertEqual(sorted(point), ["x", "y"])

            self.assertEqual(len(bundle["components"]), 1)
            component = bundle["components"][0]
            self.assertEqual(component["reference_designator"], "R1")
            self.assertEqual(component["centroid"], {"x": 5.0, "y": 5.0})
            self.assertAlmostEqual(component["footprint_bbox_mm"]["width"], 1.5)
            self.assertAlmostEqual(component["footprint_bbox_mm"]["height"], 0.6)
            self.assertTrue(component["bom_cross_check"])
            self.assertEqual(component["bom_part_number"], "123-45678")

            counts = bundle["footprint_classification_report"]["counts_by_category"]
            self.assertEqual(counts.get("mounting_hole_candidate"), 1)
            self.assertEqual(counts.get("fiducial_or_test_point_candidate"), 1)
            self.assertEqual(counts.get("fiducial_candidate"), 1)

            landmark_categories = {landmark["category"] for landmark in bundle["landmarks"]}
            self.assertIn("mounting_hole_candidate", landmark_categories)
            self.assertIn("fiducial_or_test_point_candidate", landmark_categories)
            self.assertIn("fiducial_candidate", landmark_categories)

            self.assertTrue(bundle["integrity"]["payload_sha256"].startswith("sha256:"))
            self.assertEqual(bundle["status"], "candidate_pending_importer_and_registration_validation")

    def test_unresolved_component_like_footprint_is_not_a_landmark(self) -> None:
        pcb_with_two_pad_unresolved = """
(kicad_pcb
  (version 20250114)
  (gr_line (start 0.0 0.0) (end 6.0 0.0) (layer "Edge.Cuts") (uuid "l1"))
  (gr_line (start 6.0 0.0) (end 6.0 6.0) (layer "Edge.Cuts") (uuid "l2"))
  (gr_line (start 6.0 6.0) (end 0.0 0.0) (layer "Edge.Cuts") (uuid "l3"))
  (footprint ""
    (layer "F.Cu")
    (uuid "ddd")
    (at 3.0 3.0 0)
    (pad "1" smd rect
      (at -0.3 0 0)
      (size 0.4 0.4)
      (layers "F.Cu")
      (net 0 "")
    )
    (pad "2" smd rect
      (at 0.3 0 0)
      (size 0.4 0.4)
      (layers "F.Cu")
      (net 0 "")
    )
  )
)
"""
        with tempfile.TemporaryDirectory() as directory:
            pcb_path = Path(directory) / "board.kicad_pcb"
            pcb_path.write_text(pcb_with_two_pad_unresolved, encoding="utf-8")
            bom_path = Path(directory) / "bom.txt"
            bom_path.write_text(BOM_FIXTURE, encoding="utf-8")
            brd_path = Path(directory) / "board.brd"
            brd_path.write_bytes(b"fake-allegro-board-bytes")

            bundle = build_reference_package(pcb_path, bom_path, brd_path, "test-board", "Rev X", "left", "test-evidence")

            counts = bundle["footprint_classification_report"]["counts_by_category"]
            self.assertEqual(counts.get("unresolved_component_like"), 1)
            landmark_categories = {landmark["category"] for landmark in bundle["landmarks"]}
            self.assertNotIn("unresolved_component_like", landmark_categories)
            self.assertEqual(bundle["components"], [])


if __name__ == "__main__":
    unittest.main()
