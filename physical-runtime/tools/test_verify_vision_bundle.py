#!/usr/bin/env python3
"""Tests for the vision-bundle acceptance port."""

from __future__ import annotations

import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from verify_vision_bundle import check_bundle

MODEL_BYTES = b"TFL3" + b"\x00" * 128


def write_bundle(directory: Path, **overrides) -> Path:
    directory.mkdir(parents=True, exist_ok=True)
    model = directory / "model.tflite"
    model.write_bytes(MODEL_BYTES)
    manifest = {
        "schema_version": "1.0",
        "model_sha256": "sha256:" + hashlib.sha256(MODEL_BYTES).hexdigest(),
        "input_size": 640,
        "class_confidence": 0.4,
        "keypoint_confidence": 0.3,
        "minimum_board_area_ratio": 0.02,
        "maximum_corner_jitter_ratio": 0.05,
        "stable_frame_count": 4,
        "maximum_observation_age_ms": 800,
        "coordinates_normalized": True,
    }
    manifest.update(overrides)
    (directory / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
    return directory


class CheckBundleTest(unittest.TestCase):
    def test_valid_bundle_accepted(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            report = check_bundle(write_bundle(Path(directory) / "vision"))
        self.assertTrue(report["accepted"], report["problems"])
        self.assertEqual(report["declared_input_size"], 640)

    def test_missing_files_reported_like_the_runtime(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            report = check_bundle(Path(directory))
        self.assertFalse(report["accepted"])
        self.assertIn("not installed", report["problems"][0])

    def test_digest_mismatch_detected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            report = check_bundle(write_bundle(Path(directory) / "v", model_sha256="sha256:" + "b" * 64))
        self.assertFalse(report["accepted"])
        self.assertTrue(any("digest mismatch" in p for p in report["problems"]))

    def test_wrong_schema_version_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            report = check_bundle(write_bundle(Path(directory) / "v", schema_version="2.0"))
        self.assertFalse(report["accepted"])

    def test_out_of_range_values_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            report = check_bundle(write_bundle(Path(directory) / "v", input_size=128, stable_frame_count=99))
        self.assertFalse(report["accepted"])
        self.assertTrue(any("input_size" in p for p in report["problems"]))
        self.assertTrue(any("stable_frame_count" in p for p in report["problems"]))

    def test_missing_field_reported(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            bundle = write_bundle(Path(directory) / "v")
            manifest = json.loads((bundle / "manifest.json").read_text())
            del manifest["keypoint_confidence"]
            (bundle / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
            report = check_bundle(bundle)
        self.assertFalse(report["accepted"])
        self.assertTrue(any("keypoint_confidence" in p for p in report["problems"]))


if __name__ == "__main__":
    unittest.main()
