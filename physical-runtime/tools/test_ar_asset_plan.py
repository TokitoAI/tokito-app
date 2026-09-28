#!/usr/bin/env python3
"""Tests for AR asset planning."""

from __future__ import annotations

from pathlib import Path
import tempfile
import unittest
import zipfile

from ar_asset_plan import plan_ar_asset_generation


class ArAssetPlanTest(unittest.TestCase):
    def test_existing_model_is_ready_for_review(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source"
            source.mkdir()
            (source / "board.step").write_text("ISO-10303-21", encoding="utf-8")

            plan = plan_ar_asset_generation(source, Path(directory) / "out")

            self.assertEqual(plan["status"], "ready_existing_model_review")

    def test_kicad_board_produces_export_commands(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source"
            source.mkdir()
            (source / "board.kicad_pcb").write_text("(kicad_pcb)", encoding="utf-8")

            plan = plan_ar_asset_generation(source, Path(directory) / "out")

            self.assertIn(plan["status"], {"ready_kicad_cli_export", "blocked_missing_kicad_cli"})
            self.assertEqual(plan["commands"][0][:4], ["kicad-cli", "pcb", "export", "glb"])
            self.assertEqual(plan["commands"][1][:4], ["kicad-cli", "pcb", "export", "step"])
            self.assertEqual(plan["commands"][2][:4], ["kicad-cli", "pcb", "export", "vrml"])

    def test_allegro_board_without_kicad_is_blocked_with_import_plan(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source"
            source.mkdir()
            (source / "layout.brd").write_bytes(b"vendor-board")
            (source / "schematic.dsn").write_bytes(b"vendor-schematic")

            plan = plan_ar_asset_generation(source, Path(directory) / "out")

            self.assertIn(
                plan["status"],
                {"blocked_missing_kicad_10_for_allegro_import", "ready_kicad_allegro_import"},
            )
            self.assertEqual(plan["commands"][0][:3], ["kicad-cli", "pcb", "import"])

    def test_archive_geometry_requires_extraction_before_conversion(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source"
            source.mkdir()
            with zipfile.ZipFile(source / "design.zip", "w") as archive:
                archive.writestr("board/top.gtl", "copper")
                archive.writestr("board/drill.drl", "drill")

            plan = plan_ar_asset_generation(source, Path(directory) / "out")

            self.assertEqual(plan["status"], "blocked_archive_requires_extraction")
            self.assertIn("design.zip!/board/top.gtl", plan["files"]["gerbers"])
            self.assertIn("design.zip!/board/drill.drl", plan["files"]["drills"])

    def test_nxp_style_archive_reports_vendor_board_not_gerber_ready(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source"
            source.mkdir()
            with zipfile.ZipFile(source / "FRDM-MCXN236-DESIGN-FILES.zip", "w") as archive:
                archive.writestr("Design Files/LAY-90828_C.brd", "allegro-board")
                archive.writestr("Design Files/SCH-90828_C.DSN", "schematic")

            plan = plan_ar_asset_generation(source, Path(directory) / "out")

            self.assertEqual(plan["status"], "blocked_archive_requires_extraction")
            self.assertIn("FRDM-MCXN236-DESIGN-FILES.zip!/Design Files/LAY-90828_C.brd", plan["files"]["vendor_cad"])
            self.assertEqual(plan["files"]["gerbers"], [])


if __name__ == "__main__":
    unittest.main()
