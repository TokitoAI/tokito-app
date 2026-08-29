#!/usr/bin/env python3
"""Tests for local capture intake tooling."""

from __future__ import annotations

from pathlib import Path
import tempfile
import unittest

from capture_intake import prepare_dataset, stable_slug


class CaptureIntakeTest(unittest.TestCase):
    def test_stable_slug_preserves_meaningful_tokens(self) -> None:
        self.assertEqual(stable_slug("FRDM-MCXN236"), "frdm-mcxn236")

    def test_prepare_dataset_renames_images_and_marks_review_required(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source"
            output = Path(directory) / "datasets"
            source.mkdir()
            (source / "IMG_0001.jpg").write_bytes(b"not-a-real-jpeg-but-copied")
            (source / "SPF-90828.pdf").write_bytes(b"%PDF")

            manifest = prepare_dataset(
                source=source,
                output_root=output,
                board_name="Reference Board",
                session_id="session01",
                device_profile="iqoo-local-profile",
                captured_at="2026-08-29",
                class_names=["Reference_Board_full_board"],
            )

            self.assertEqual(len(manifest["images"]), 1)
            image = manifest["images"][0]
            self.assertTrue(Path(image["image_path"]).name.startswith("reference-board_session01_img_0001"))
            self.assertEqual(image["annotation_status"], "needs_human_bbox_review")
            self.assertTrue(Path(image["label_path"]).exists())
            self.assertEqual(Path(image["label_path"]).read_text(encoding="utf-8"), "")
            self.assertEqual(manifest["cad_inventory"]["artifact_count"], 1)
            self.assertEqual(manifest["cad_inventory"]["ar_3d_status"], "blocked_missing_gerber_drill_or_step_exports")

    def test_rejects_empty_class_list(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source"
            source.mkdir()

            with self.assertRaises(ValueError):
                prepare_dataset(
                    source=source,
                    output_root=Path(directory) / "datasets",
                    board_name="Reference Board",
                    session_id="session01",
                    device_profile="iqoo-local-profile",
                    captured_at="2026-08-29",
                    class_names=[],
                )


if __name__ == "__main__":
    unittest.main()
