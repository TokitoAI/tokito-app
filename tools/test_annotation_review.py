#!/usr/bin/env python3
"""Tests for reviewed Label Studio to YOLO conversion."""

from __future__ import annotations

import json
from pathlib import Path
import tempfile
import unittest

from annotation_review import (
    import_reviewed_export,
    prepare_label_studio_tasks,
    sha256_file,
)

CLASS_NAMES = ["NXP_FRDM-MCXN236_full_board", "NXP_FRDM-MCXN236_connector"]
PRINTED_CLASS_NAMES = ["NXP_FRDM-MCXN236_printed_board_depiction"]


def reviewed_task(
    *,
    license_status: str = "approved",
    session_id: str = "session-a",
    rotation: float = 0.0,
):
    return {
        "id": 10,
        "data": {
            "image_id": "frdm_image_0001",
            "session_id": session_id,
            "media_sha256": "sha256:" + "1" * 64,
            "source_uri": "https://example.invalid/reference",
            "license_status": license_status,
            "allowed_labels": CLASS_NAMES,
            "media_domain": "physical_capture",
        },
        "annotations": [
            {
                "completed_by": {"email": "reviewer@example.invalid"},
                "was_cancelled": False,
                "result": [
                    {
                        "type": "rectanglelabels",
                        "value": {
                            "x": 10.0,
                            "y": 20.0,
                            "width": 40.0,
                            "height": 30.0,
                            "rotation": rotation,
                            "rectanglelabels": [CLASS_NAMES[0]],
                        },
                    }
                ],
            }
        ],
    }


class AnnotationReviewTest(unittest.TestCase):
    def test_prepare_binds_image_digest_and_safe_local_url(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            image = root / "images" / "board view.jpg"
            image.parent.mkdir()
            image.write_bytes(b"image-bytes")
            intake = {
                "board_name": "NXP FRDM-MCXN236",
                "session_id": "session-a",
                "class_names": CLASS_NAMES,
                "images": [
                    {
                        "image_id": "board-1",
                        "image_path": str(image),
                        "sha256": sha256_file(image),
                    }
                ],
            }
            tasks = prepare_label_studio_tasks(
                intake,
                root,
                "pending",
                "https://example.invalid/source",
                "physical_capture",
            )
            self.assertEqual(
                tasks[0]["data"]["image"],
                "/data/local-files/?d=images/board%20view.jpg",
            )
            self.assertEqual(tasks[0]["data"]["license_status"], "pending")

    def test_prepare_rejects_digest_drift(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            image = root / "board.jpg"
            image.write_bytes(b"image-bytes")
            intake = {
                "board_name": "board",
                "session_id": "session-a",
                "class_names": CLASS_NAMES,
                "images": [
                    {
                        "image_id": "board-1",
                        "image_path": str(image),
                        "sha256": "sha256:" + "0" * 64,
                    }
                ],
            }
            with self.assertRaisesRegex(ValueError, "digest mismatch"):
                prepare_label_studio_tasks(
                    intake,
                    root,
                    "pending",
                    "https://example.invalid",
                    "physical_capture",
                )

    def test_import_writes_normalized_yolo_and_review_manifest(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "review"
            manifest = import_reviewed_export(
                [reviewed_task()],
                output,
                "session-a",
                "approved",
                "physical_capture",
            )
            label = output / "labels" / "reviewed" / "frdm_image_0001.txt"
            self.assertEqual(
                label.read_text(encoding="utf-8"),
                "0 0.30000000 0.35000000 0.40000000 0.30000000\n",
            )
            self.assertTrue(manifest["trainable"])
            self.assertEqual(manifest["box_count"], 1)
            self.assertEqual(
                manifest["records"][0]["source_uri"],
                "https://example.invalid/reference",
            )
            self.assertEqual(
                json.loads((output / "review_manifest.json").read_text())["task_count"],
                1,
            )

    def test_pending_license_is_never_trainable(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            manifest = import_reviewed_export(
                [reviewed_task(license_status="pending")],
                Path(directory) / "review",
                "session-a",
                "pending",
                "physical_capture",
            )
            self.assertFalse(manifest["trainable"])

    def test_rejects_rotated_or_out_of_bounds_boxes(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "rotated boxes"):
                import_reviewed_export(
                    [reviewed_task(rotation=5.0)],
                    Path(directory) / "review",
                    "session-a",
                    "approved",
                    "physical_capture",
                )

    def test_accepts_label_studio_edge_rounding_noise(self) -> None:
        task = reviewed_task()
        value = task["annotations"][0]["result"][0]["value"]
        value["x"] = 91.14583333333334
        value["width"] = 8.854166666666668
        with tempfile.TemporaryDirectory() as directory:
            manifest = import_reviewed_export(
                [task],
                Path(directory) / "review",
                "session-a",
                "approved",
                "physical_capture",
            )
            self.assertEqual(manifest["box_count"], 1)

    def test_refuses_to_overwrite_reviewed_labels(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "review"
            import_reviewed_export(
                [reviewed_task()], output, "session-a", "approved", "physical_capture"
            )
            with self.assertRaisesRegex(ValueError, "refusing to overwrite"):
                import_reviewed_export(
                    [reviewed_task()],
                    output,
                    "session-a",
                    "approved",
                    "physical_capture",
                )

    def test_invalid_later_task_does_not_leave_partial_labels(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "review"
            second = reviewed_task(session_id="wrong-session")
            second["data"]["image_id"] = "frdm_image_0002"
            with self.assertRaisesRegex(ValueError, "expected image/session"):
                import_reviewed_export(
                    [reviewed_task(), second],
                    output,
                    "session-a",
                    "approved",
                    "physical_capture",
                )
            self.assertFalse((output / "labels").exists())

    def test_printed_reference_cannot_expose_physical_labels(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            image = root / "guide.jpg"
            image.write_bytes(b"guide-image")
            intake = {
                "board_name": "NXP FRDM-MCXN236",
                "session_id": "guide-session",
                "class_names": CLASS_NAMES,
                "images": [
                    {
                        "image_id": "guide-1",
                        "image_path": str(image),
                        "sha256": sha256_file(image),
                    }
                ],
            }
            with self.assertRaisesRegex(ValueError, "printed-reference intake"):
                prepare_label_studio_tasks(
                    intake,
                    root,
                    "pending",
                    "user-supplied://quick-start-guide",
                    "printed_reference",
                )

    def test_printed_reference_round_trip_preserves_domain(self) -> None:
        task = reviewed_task(license_status="pending")
        task["data"]["media_domain"] = "printed_reference"
        task["data"]["allowed_labels"] = PRINTED_CLASS_NAMES
        task["annotations"][0]["result"][0]["value"]["rectanglelabels"] = (
            PRINTED_CLASS_NAMES
        )
        with tempfile.TemporaryDirectory() as directory:
            manifest = import_reviewed_export(
                [task],
                Path(directory) / "review",
                "session-a",
                "pending",
                "printed_reference",
            )
            self.assertEqual(manifest["media_domain"], "printed_reference")
            self.assertEqual(
                manifest["records"][0]["media_domain"], "printed_reference"
            )
            self.assertFalse(manifest["trainable"])


if __name__ == "__main__":
    unittest.main()
