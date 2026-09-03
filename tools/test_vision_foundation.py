#!/usr/bin/env python3
"""Tests for the reference physical-vision foundation."""

from __future__ import annotations

import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from vision_foundation.geometry import Transform2D
from vision_foundation.pipeline import run_pipeline
from vision_foundation.types import Point


FIXTURE = Path(__file__).parent / "vision_foundation" / "fixtures" / "synthetic_board_frame.valid.json"
ROOT = Path(__file__).resolve().parents[1]


def load_fixture() -> dict:
    return json.loads(FIXTURE.read_text(encoding="utf-8"))


class VisionFoundationTest(unittest.TestCase):
    def test_pipeline_detects_and_registers_synthetic_board(self) -> None:
        result = run_pipeline(load_fixture())

        self.assertEqual(result["outcome"], "observation")
        self.assertTrue(result["detection"]["detected"])
        self.assertTrue(result["registration"]["registered"])
        self.assertEqual(result["registration"]["transform"]["source_frame"], "upright-image-pixels")
        self.assertEqual(result["registration"]["transform"]["target_frame"], "synthetic-board-mm")
        self.assertEqual(result["regions"][0]["geometry"]["coordinate_frame"], "synthetic-board-mm")

    def test_coordinate_transform_known_points_and_inverse(self) -> None:
        result = run_pipeline(load_fixture())
        matrix = tuple(tuple(row) for row in result["registration"]["transform"]["matrix"])
        transform = Transform2D("upright-image-pixels", "synthetic-board-mm", matrix)

        board_point = transform.apply(Point(2.0, 1.0))
        self.assertAlmostEqual(board_point.x, 0.0)
        self.assertAlmostEqual(board_point.y, 0.0)
        image_point = transform.inverse().apply(Point(80.0, 50.0))
        self.assertAlmostEqual(image_point.x, 8.0)
        self.assertAlmostEqual(image_point.y, 7.0)

    def test_low_signal_abstains_without_positive_claim(self) -> None:
        payload = load_fixture()
        payload["frame"]["pixels"] = [[0 for _ in range(payload["frame"]["width"])] for _ in range(payload["frame"]["height"])]

        result = run_pipeline(payload)

        self.assertEqual(result["outcome"], "abstained")
        self.assertFalse(result["detection"]["detected"])
        self.assertEqual(result["regions"], [])

    def test_invalid_frame_geometry_returns_error(self) -> None:
        payload = load_fixture()
        payload["frame"]["width"] = 9

        result = run_pipeline(payload)

        self.assertEqual(result["outcome"], "error")
        self.assertEqual(result["messages"][0]["code"], "invalid_geometry")

    def test_unsupported_board_units_fail_closed(self) -> None:
        payload = load_fixture()
        payload["board_profile"]["units"] = "inch"

        result = run_pipeline(payload)

        self.assertEqual(result["outcome"], "error")
        self.assertEqual(result["messages"][0]["code"], "unsupported_capability")

    def test_missing_detector_config_fails_before_detection(self) -> None:
        payload = load_fixture()
        del payload["detector"]

        result = run_pipeline(payload)

        self.assertEqual(result["outcome"], "error")
        self.assertEqual(result["messages"][0]["code"], "invalid_argument")

    def test_invalid_detector_config_returns_error(self) -> None:
        payload = load_fixture()
        payload["detector"]["threshold"] = 999

        result = run_pipeline(payload)

        self.assertEqual(result["outcome"], "error")
        self.assertEqual(result["messages"][0]["code"], "invalid_argument")

    def test_non_numeric_detector_config_returns_error(self) -> None:
        payload = load_fixture()
        payload["detector"]["threshold"] = "bright"

        result = run_pipeline(payload)

        self.assertEqual(result["outcome"], "error")
        self.assertEqual(result["messages"][0]["code"], "invalid_argument")

    def test_cli_end_to_end_writes_structured_result(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "vision-result.json"
            completed = subprocess.run(
                [
                    sys.executable,
                    "-m",
                    "tools.vision_foundation.cli",
                    "--input",
                    str(FIXTURE),
                    "--output",
                    str(output),
                ],
                cwd=ROOT,
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertEqual(completed.returncode, 0, completed.stderr + completed.stdout)
            result = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(result["outcome"], "observation")

    def test_no_board_specific_control_flow_required(self) -> None:
        payload = copy.deepcopy(load_fixture())
        payload["board_profile"]["profile_id"] = "arbitrary-board"
        payload["board_profile"]["board_frame_id"] = "arbitrary-board-mm"

        result = run_pipeline(payload)

        self.assertEqual(result["outcome"], "observation")
        self.assertEqual(result["registration"]["transform"]["target_frame"], "arbitrary-board-mm")


if __name__ == "__main__":
    unittest.main()
