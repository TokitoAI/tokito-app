#!/usr/bin/env python3
"""Tests for AR asset planning."""

from __future__ import annotations

from pathlib import Path
import tempfile
import unittest

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
            self.assertEqual(plan["commands"][0][:4], ["kicad-cli", "pcb", "export", "step"])
            self.assertEqual(plan["commands"][1][:4], ["kicad-cli", "pcb", "export", "vrml"])

    def test_vendor_cad_without_exports_is_blocked(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source"
            source.mkdir()
            (source / "layout.brd").write_bytes(b"vendor-board")
            (source / "schematic.dsn").write_bytes(b"vendor-schematic")

            plan = plan_ar_asset_generation(source, Path(directory) / "out")

            self.assertEqual(plan["status"], "blocked_vendor_cad_requires_export")


if __name__ == "__main__":
    unittest.main()
