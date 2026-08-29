#!/usr/bin/env python3
"""Tests for ordered, rotation-aware YOLO pose preparation."""

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from PIL import Image
from yolo_experiment import YoloExperimentError, sha256_file
from yolo_pose_experiment import (
    CORNER_NAMES,
    prepare_pose_dataset,
    validate_projection_quad,
)


class YoloPoseExperimentTest(unittest.TestCase):
    def fixture(self, root: Path) -> tuple[Path, Path, Path]:
        image = root / "source.jpg"
        Image.new("RGB", (100, 80), color=(30, 40, 50)).save(image, "JPEG")
        image_id = "image-1"
        intake = {
            "session_id": "session-1",
            "images": [
                {
                    "image_id": image_id,
                    "image_path": str(image),
                    "sha256": sha256_file(image),
                }
            ],
        }
        review = {
            "session_id": "session-1",
            "records": [{"image_id": image_id}],
        }
        names = CORNER_NAMES + ["j1-header"]
        points = [
            ("board_top_left", 20, 10, 2),
            ("board_top_right", 80, 10, 2),
            ("board_bottom_right", 80, 70, 2),
            ("board_bottom_left", 20, 70, 2),
            ("j1-header", 70, 40, 2),
        ]
        pose = {
            "session_id": "session-1",
            "media_domain": "printed_reference",
            "keypoint_names": names,
            "records": [
                {
                    "image_id": image_id,
                    "bbox_xyxy_pixels": [20, 10, 80, 70],
                    "reviewer": "reviewer@example.test",
                    "keypoints": [
                        {
                            "name": name,
                            "x_pixels": x,
                            "y_pixels": y,
                            "visibility": visibility,
                        }
                        for name, x, y, visibility in points
                    ],
                }
            ],
        }
        paths = (root / "pose.json", root / "review.json", root / "intake.json")
        for path, document in zip(paths, (pose, review, intake)):
            path.write_text(json.dumps(document), encoding="utf-8")
        return paths

    def test_rotates_corners_and_landmark_with_parent_split(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            pose, review, intake = self.fixture(root)
            output = root / "out"
            manifest = prepare_pose_dataset(
                pose,
                review,
                intake,
                output,
                holdout_image_id="image-1",
                rotation_angles=[90],
            )
            self.assertEqual(len(manifest["items"]), 2)
            self.assertEqual(manifest["items"][1]["parent_image_id"], "image-1")
            self.assertEqual(manifest["items"][1]["split"], "holdout")
            fields = (
                (output / "labels" / "holdout" / "image-1__rotate_90.txt")
                .read_text(encoding="utf-8")
                .split()
            )
            keypoints = [float(value) for value in fields[5:]]
            self.assertAlmostEqual(keypoints[0], 0.2)
            self.assertAlmostEqual(keypoints[1], 0.875)
            self.assertEqual(keypoints[2], 2.0)

    def test_rejects_wrong_corner_order(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            pose, review, intake = self.fixture(root)
            document = json.loads(pose.read_text(encoding="utf-8"))
            document["keypoint_names"][0:2] = document["keypoint_names"][1::-1]
            pose.write_text(json.dumps(document), encoding="utf-8")
            with self.assertRaisesRegex(YoloExperimentError, "top-left"):
                prepare_pose_dataset(
                    pose,
                    review,
                    intake,
                    root / "out",
                    holdout_image_id=None,
                    rotation_angles=[],
                )

    def test_rotation_grid_creates_multiple_descendants(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            pose, review, intake = self.fixture(root)
            manifest = prepare_pose_dataset(
                pose,
                review,
                intake,
                root / "out",
                holdout_image_id=None,
                rotation_angles=[],
                rotation_grid=[45, 90, 135],
            )
            self.assertEqual(len(manifest["items"]), 4)
            self.assertEqual(
                [
                    item["augmentation"]["angle_degrees_counter_clockwise"]
                    for item in manifest["items"][1:]
                ],
                [45.0, 90.0, 135.0],
            )

    def test_rejects_counter_clockwise_corner_geometry(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            pose, review, intake = self.fixture(root)
            document = json.loads(pose.read_text(encoding="utf-8"))
            points = document["records"][0]["keypoints"]
            points[1], points[3] = points[3], points[1]
            points[1]["name"], points[3]["name"] = points[3]["name"], points[1]["name"]
            pose.write_text(json.dumps(document), encoding="utf-8")
            with self.assertRaisesRegex(YoloExperimentError, "clockwise"):
                prepare_pose_dataset(
                    pose,
                    review,
                    intake,
                    root / "out",
                    holdout_image_id=None,
                    rotation_angles=[],
                )

    def test_projection_gate_accepts_perspective_quad_inside_box(self) -> None:
        accepted, reason = validate_projection_quad(
            [(22, 15), (78, 18), (82, 70), (18, 67)],
            [15, 10, 85, 75],
            (100, 80),
        )
        self.assertTrue(accepted)
        self.assertEqual(reason, "accepted")

    def test_projection_gate_rejects_confident_page_edge_corners(self) -> None:
        accepted, reason = validate_projection_quad(
            [(0, 0), (99, 0), (99, 79), (0, 79)],
            [25, 20, 75, 65],
            (100, 80),
        )
        self.assertFalse(accepted)
        self.assertEqual(reason, "corner_disagrees_with_detection_box")


if __name__ == "__main__":
    unittest.main()
