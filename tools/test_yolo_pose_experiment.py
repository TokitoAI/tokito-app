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
    load_reference_bank,
    load_reference_corners,
    prepare_pose_dataset,
    refine_quad_with_reference,
    refine_quad_with_reference_bank,
    validate_projection_quad,
    validate_reference_options,
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

    def test_accepts_provenance_verified_physical_capture(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            pose, review, intake = self.fixture(root)
            document = json.loads(pose.read_text(encoding="utf-8"))
            document["media_domain"] = "physical_capture"
            pose.write_text(json.dumps(document), encoding="utf-8")
            manifest = prepare_pose_dataset(
                pose,
                review,
                intake,
                root / "out",
                holdout_image_id=None,
                rotation_angles=[],
            )
            self.assertEqual(manifest["media_domain"], "physical_capture")
            self.assertFalse(manifest["release_eligible"])
            self.assertFalse(manifest["evaluation_valid"])

    def test_rejects_unknown_media_domain(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            pose, review, intake = self.fixture(root)
            document = json.loads(pose.read_text(encoding="utf-8"))
            document["media_domain"] = "generated"
            pose.write_text(json.dumps(document), encoding="utf-8")
            with self.assertRaisesRegex(YoloExperimentError, "media domain"):
                prepare_pose_dataset(
                    pose,
                    review,
                    intake,
                    root / "out",
                    holdout_image_id=None,
                    rotation_angles=[],
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

    def test_reference_options_are_all_or_nothing(self) -> None:
        self.assertEqual(validate_reference_options(None, None, None), "none")
        self.assertEqual(
            validate_reference_options(
                Path("reference.jpg"), Path("pose.json"), "image-1"
            ),
            "single",
        )
        self.assertEqual(
            validate_reference_options(
                None, Path("pose.json"), None, Path("intake.json")
            ),
            "bank",
        )
        with self.assertRaisesRegex(YoloExperimentError, "requires"):
            validate_reference_options(Path("reference.jpg"), None, None)

    def test_reference_bank_excludes_exact_query_bytes(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            pose, _, intake = self.fixture(root)
            bank = load_reference_bank(
                pose, intake, excluded_image_sha256="sha256:not-the-query"
            )
            self.assertEqual([item["image_id"] for item in bank], ["image-1"])
            with self.assertRaisesRegex(YoloExperimentError, "no eligible"):
                load_reference_bank(
                    pose,
                    intake,
                    excluded_image_sha256=sha256_file(root / "source.jpg"),
                )

    def test_loads_only_fully_reviewed_reference_corners(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            pose, _, _ = self.fixture(root)
            self.assertEqual(
                load_reference_corners(pose, "image-1"),
                [(20.0, 10.0), (80.0, 10.0), (80.0, 70.0), (20.0, 70.0)],
            )
            document = json.loads(pose.read_text(encoding="utf-8"))
            document["records"][0]["keypoints"][2]["visibility"] = 1
            pose.write_text(json.dumps(document), encoding="utf-8")
            with self.assertRaisesRegex(YoloExperimentError, "reviewed visible"):
                load_reference_corners(pose, "image-1")

    def test_reference_homography_recovers_unseen_rotation(self) -> None:
        try:
            import cv2
            import numpy as np
        except ImportError:
            self.skipTest("optional OpenCV dependencies are unavailable")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            reference = np.zeros((600, 800, 3), dtype=np.uint8)
            rng = np.random.default_rng(7)
            for index, (x, y) in enumerate(
                rng.integers([130, 100], [670, 500], (250, 2))
            ):
                color = tuple(int(value) for value in rng.integers(60, 255, 3))
                cv2.circle(reference, (int(x), int(y)), 3 + index % 8, color, -1)
            cv2.putText(
                reference,
                "TOKITO BOARD 236",
                (180, 310),
                cv2.FONT_HERSHEY_SIMPLEX,
                1.4,
                (255, 255, 255),
                4,
                cv2.LINE_AA,
            )
            reference_path = root / "reference.jpg"
            cv2.imwrite(str(reference_path), reference)
            corners = [
                (180.0, 130.0),
                (620.0, 130.0),
                (620.0, 470.0),
                (180.0, 470.0),
            ]
            rotation = cv2.getRotationMatrix2D((400, 300), 33, 1.0)
            query = cv2.warpAffine(reference, rotation, (800, 600))
            expected = cv2.transform(
                np.float32(corners).reshape(-1, 1, 2), rotation
            ).reshape(-1, 2)
            bbox = [
                float(expected[:, 0].min()),
                float(expected[:, 1].min()),
                float(expected[:, 0].max()),
                float(expected[:, 1].max()),
            ]
            refined, evidence = refine_quad_with_reference(
                reference_path, corners, query, bbox
            )
            self.assertEqual(evidence["status"], "accepted")
            self.assertIsNotNone(refined)
            errors = np.linalg.norm(np.float32(refined) - expected, axis=1)
            self.assertLess(float(errors.mean()), 3.0)
            bank_refined, bank_evidence = refine_quad_with_reference_bank(
                [
                    {
                        "image_id": "reference-a",
                        "image_path": reference_path,
                        "image_sha256": sha256_file(reference_path),
                        "corners": corners,
                    },
                    {
                        "image_id": "reference-b",
                        "image_path": reference_path,
                        "image_sha256": sha256_file(reference_path),
                        "corners": corners,
                    },
                ],
                query,
                bbox,
            )
            self.assertEqual(bank_evidence["status"], "accepted")
            self.assertEqual(bank_evidence["consensus_count"], 2)
            bank_errors = np.linalg.norm(np.float32(bank_refined) - expected, axis=1)
            self.assertLess(float(bank_errors.mean()), 3.0)

            blank_path = root / "blank.jpg"
            cv2.imwrite(str(blank_path), np.zeros_like(reference))
            exceptional_refined, exceptional_evidence = refine_quad_with_reference_bank(
                [
                    {
                        "image_id": "strong-reference",
                        "image_path": reference_path,
                        "image_sha256": sha256_file(reference_path),
                        "corners": corners,
                    },
                    {
                        "image_id": "blank-reference",
                        "image_path": blank_path,
                        "image_sha256": sha256_file(blank_path),
                        "corners": corners,
                    },
                ],
                query,
                bbox,
            )
            self.assertEqual(exceptional_evidence["status"], "accepted")
            self.assertEqual(
                exceptional_evidence["selection_policy"],
                "exceptional_single_reference",
            )
            exceptional_errors = np.linalg.norm(
                np.float32(exceptional_refined) - expected, axis=1
            )
            self.assertLess(float(exceptional_errors.mean()), 3.0)


if __name__ == "__main__":
    unittest.main()
