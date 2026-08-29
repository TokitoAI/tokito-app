#!/usr/bin/env python3
"""Tests for provenance-bound YOLO experiment preparation."""

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from PIL import Image
from yolo_experiment import YoloExperimentError, prepare_dataset, sha256_file


class YoloExperimentTest(unittest.TestCase):
    def fixture(self, root: Path, *, trainable: bool = False) -> tuple[Path, Path]:
        image = root / "source.jpg"
        Image.new("RGB", (32, 32), color=(20, 30, 40)).save(image, "JPEG")
        label = root / "source.txt"
        label.write_text("0 0.5 0.5 0.25 0.25\n", encoding="utf-8")
        intake = {
            "session_id": "session-1",
            "images": [
                {
                    "image_id": "image-1",
                    "image_path": str(image),
                    "sha256": sha256_file(image),
                }
            ],
        }
        review = {
            "session_id": "session-1",
            "task_count": 1,
            "trainable": trainable,
            "class_names": ["board"],
            "records": [
                {
                    "image_id": "image-1",
                    "media_sha256": sha256_file(image),
                    "label_path": str(label),
                    "label_sha256": sha256_file(label),
                    "box_count": 1,
                }
            ],
        }
        intake_path = root / "intake.json"
        review_path = root / "review.json"
        intake_path.write_text(json.dumps(intake), encoding="utf-8")
        review_path.write_text(json.dumps(review), encoding="utf-8")
        return review_path, intake_path

    def test_rejects_non_trainable_release_preparation(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            review, intake = self.fixture(root)
            with self.assertRaisesRegex(YoloExperimentError, "not trainable"):
                prepare_dataset(review, intake, root / "out", local_smoke=False)

    def test_local_smoke_is_permanently_non_release(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            review, intake = self.fixture(root)
            manifest = prepare_dataset(review, intake, root / "out", local_smoke=True)
            self.assertFalse(manifest["release_eligible"])
            self.assertFalse(manifest["evaluation_valid"])
            self.assertEqual(manifest["item_count"], 1)
            self.assertEqual(manifest["box_count"], 1)

    def test_explicit_holdout_is_not_copied_into_training(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            review, intake = self.fixture(root)
            output = root / "out"
            manifest = prepare_dataset(
                review,
                intake,
                output,
                local_smoke=True,
                holdout_image_id="image-1",
            )
            self.assertEqual(manifest["items"][0]["split"], "holdout")
            self.assertFalse(any((output / "images" / "train").iterdir()))
            self.assertEqual(len(list((output / "images" / "holdout").iterdir())), 1)

    def test_rejects_media_digest_drift(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            review, intake = self.fixture(root, trainable=True)
            document = json.loads(review.read_text(encoding="utf-8"))
            document["records"][0]["media_sha256"] = "sha256:" + "0" * 64
            review.write_text(json.dumps(document), encoding="utf-8")
            with self.assertRaisesRegex(YoloExperimentError, "digest mismatch"):
                prepare_dataset(review, intake, root / "out", local_smoke=False)

    def test_rejects_invalid_geometry(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            review, intake = self.fixture(root, trainable=True)
            document = json.loads(review.read_text(encoding="utf-8"))
            label_path = Path(document["records"][0]["label_path"])
            label_path.write_text("0 0.5 0.5 1.2 0.25\n", encoding="utf-8")
            document["records"][0]["label_sha256"] = sha256_file(label_path)
            review.write_text(json.dumps(document), encoding="utf-8")
            with self.assertRaisesRegex(YoloExperimentError, "out of range"):
                prepare_dataset(review, intake, root / "out", local_smoke=False)


if __name__ == "__main__":
    unittest.main()
