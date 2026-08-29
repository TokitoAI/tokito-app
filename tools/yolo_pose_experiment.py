#!/usr/bin/env python3
"""Prepare, train, and inspect four-corner/landmark YOLO pose smoke models.

The pose annotation manifest is deliberately separate from object-detection
labels. Its first four keypoints are an ordered board quadrilateral; remaining
keypoints are semantic landmarks. Printed-reference data always remains local,
non-release, and evaluation-invalid.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import platform
import sys
from collections.abc import Sequence
from pathlib import Path
from typing import Any

from yolo_experiment import (
    YoloExperimentError,
    _rotate_point,
    canonical_digest,
    copy_training_image,
    load_json,
    resolve_repo_path,
    rotate_training_image,
    sha256_file,
    verify_digest,
)

CORNER_NAMES = [
    "board_top_left",
    "board_top_right",
    "board_bottom_right",
    "board_bottom_left",
]


def _image_size(path: Path) -> tuple[int, int]:
    try:
        from PIL import Image
    except ImportError as exc:
        raise YoloExperimentError("Pillow is required for pose preparation") from exc
    with Image.open(path) as image:
        return image.size


def _signed_area(points: list[tuple[float, float]]) -> float:
    return 0.5 * sum(
        x1 * y2 - x2 * y1 for (x1, y1), (x2, y2) in zip(points, points[1:] + points[:1])
    )


def validate_projection_quad(
    corners: list[tuple[float, float]],
    bbox_xyxy: list[float],
    image_size: tuple[int, int],
) -> tuple[bool, str]:
    """Reject confident pose geometry that is unsafe for board projection."""
    if len(corners) != 4 or len(bbox_xyxy) != 4:
        return False, "missing_four_corner_or_box_geometry"
    values = [coordinate for point in corners for coordinate in point] + bbox_xyxy
    if not all(math.isfinite(float(value)) for value in values):
        return False, "non_finite_geometry"
    width, height = image_size
    if width <= 0 or height <= 0:
        return False, "invalid_image_size"
    if any(not (0.0 <= x <= width and 0.0 <= y <= height) for x, y in corners):
        return False, "corner_outside_image"
    cross_products = []
    for index in range(4):
        a = corners[index]
        b = corners[(index + 1) % 4]
        c = corners[(index + 2) % 4]
        cross_products.append(
            (b[0] - a[0]) * (c[1] - b[1]) - (b[1] - a[1]) * (c[0] - b[0])
        )
    if _signed_area(corners) <= 0 or min(cross_products) <= 0:
        return False, "corner_order_is_not_clockwise_convex"
    x1, y1, x2, y2 = (float(value) for value in bbox_xyxy)
    box_width = x2 - x1
    box_height = y2 - y1
    if box_width <= 0 or box_height <= 0:
        return False, "degenerate_detection_box"
    margin_x = box_width * 0.2
    margin_y = box_height * 0.2
    if any(
        not (
            x1 - margin_x <= x <= x2 + margin_x and y1 - margin_y <= y <= y2 + margin_y
        )
        for x, y in corners
    ):
        return False, "corner_disagrees_with_detection_box"
    quad_area = _signed_area(corners)
    area_ratio = quad_area / (box_width * box_height)
    if not 0.15 <= area_ratio <= 1.35:
        return False, "implausible_quad_to_box_area_ratio"
    minimum_edge = 0.08 * min(box_width, box_height)
    if any(
        math.hypot(
            corners[(index + 1) % 4][0] - corners[index][0],
            corners[(index + 1) % 4][1] - corners[index][1],
        )
        < minimum_edge
        for index in range(4)
    ):
        return False, "degenerate_quad_edge"
    return True, "accepted"


def validate_pose_annotation(
    document: dict[str, Any], image_sizes: dict[str, tuple[int, int]]
) -> None:
    names = document.get("keypoint_names") or []
    if names[:4] != CORNER_NAMES:
        raise YoloExperimentError(
            "pose keypoints must begin top-left, top-right, bottom-right, bottom-left"
        )
    if len(names) != len(set(names)) or len(names) < 4:
        raise YoloExperimentError(
            "pose keypoint names must be unique and include four corners"
        )
    records = document.get("records") or []
    if not records:
        raise YoloExperimentError("pose annotation manifest has no records")
    seen: set[str] = set()
    for record in records:
        image_id = record.get("image_id")
        if image_id in seen:
            raise YoloExperimentError(f"duplicate pose image ID: {image_id}")
        seen.add(image_id)
        if image_id not in image_sizes:
            raise YoloExperimentError(
                f"pose annotation cites unknown image: {image_id}"
            )
        if not record.get("reviewer"):
            raise YoloExperimentError(f"pose record has no reviewer: {image_id}")
        width, height = image_sizes[image_id]
        bbox = record.get("bbox_xyxy_pixels") or []
        if len(bbox) != 4:
            raise YoloExperimentError(f"pose record has invalid bbox: {image_id}")
        x1, y1, x2, y2 = (float(value) for value in bbox)
        if not (0 <= x1 < x2 <= width and 0 <= y1 < y2 <= height):
            raise YoloExperimentError(f"pose bbox is outside the image: {image_id}")
        keypoints = record.get("keypoints") or []
        if [point.get("name") for point in keypoints] != names:
            raise YoloExperimentError(f"pose keypoint order drift: {image_id}")
        for point in keypoints:
            visibility = point.get("visibility")
            if visibility not in (0, 1, 2):
                raise YoloExperimentError(f"invalid keypoint visibility: {image_id}")
            x = float(point.get("x_pixels", 0.0))
            y = float(point.get("y_pixels", 0.0))
            if visibility and not (0 <= x <= width and 0 <= y <= height):
                raise YoloExperimentError(
                    f"visible keypoint is outside the image: {image_id}"
                )
        visible_corners = keypoints[:4]
        if all(point["visibility"] == 2 for point in visible_corners):
            corners = [
                (float(point["x_pixels"]), float(point["y_pixels"]))
                for point in visible_corners
            ]
            if _signed_area(corners) <= 0:
                raise YoloExperimentError(
                    f"board corners must be clockwise in image coordinates: {image_id}"
                )
            cross_products = []
            for index in range(4):
                a = corners[index]
                b = corners[(index + 1) % 4]
                c = corners[(index + 2) % 4]
                cross_products.append(
                    (b[0] - a[0]) * (c[1] - b[1]) - (b[1] - a[1]) * (c[0] - b[0])
                )
            if min(cross_products) <= 0:
                raise YoloExperimentError(
                    f"board corner polygon is not convex: {image_id}"
                )
    projected = document.get("projected_landmarks") or []
    projected_names = [item.get("name") for item in projected]
    if len(projected_names) != len(set(projected_names)):
        raise YoloExperimentError("projected landmark names must be unique")
    for landmark in projected:
        u = float(landmark.get("u", -1.0))
        v = float(landmark.get("v", -1.0))
        if not (0.0 <= u <= 1.0 and 0.0 <= v <= 1.0):
            raise YoloExperimentError(
                "projected landmark coordinates must be normalized"
            )


def _pose_row(
    record: dict[str, Any], width: int, height: int, angle_degrees: float
) -> str:
    x1, y1, x2, y2 = (float(value) for value in record["bbox_xyxy_pixels"])
    box_corners = [
        _rotate_point(x / width, y / height, angle_degrees, width, height)
        for x, y in ((x1, y1), (x2, y1), (x2, y2), (x1, y2))
    ]
    minimum_x = max(0.0, min(x for x, _ in box_corners))
    maximum_x = min(1.0, max(x for x, _ in box_corners))
    minimum_y = max(0.0, min(y for _, y in box_corners))
    maximum_y = min(1.0, max(y for _, y in box_corners))
    fields = [
        "0",
        f"{(minimum_x + maximum_x) / 2.0:.9f}",
        f"{(minimum_y + maximum_y) / 2.0:.9f}",
        f"{maximum_x - minimum_x:.9f}",
        f"{maximum_y - minimum_y:.9f}",
    ]
    for point in record["keypoints"]:
        visibility = int(point["visibility"])
        if visibility == 0:
            fields.extend(("0.000000000", "0.000000000", "0"))
            continue
        x, y = _rotate_point(
            float(point["x_pixels"]) / width,
            float(point["y_pixels"]) / height,
            angle_degrees,
            width,
            height,
        )
        if not (0.0 <= x <= 1.0 and 0.0 <= y <= 1.0):
            fields.extend(("0.000000000", "0.000000000", "0"))
        else:
            fields.extend((f"{x:.9f}", f"{y:.9f}", str(visibility)))
    return " ".join(fields) + "\n"


def _write_pose_yaml(output_dir: Path, keypoint_count: int, holdout: bool) -> Path:
    path = output_dir / "data.yaml"
    path.write_text(
        "\n".join(
            [
                f"path: {json.dumps(output_dir.as_posix())}",
                "train: images/train",
                f"val: images/{'holdout' if holdout else 'train'}",
                f"test: images/{'holdout' if holdout else 'train'}",
                f"kpt_shape: [{keypoint_count}, 3]",
                "names:",
                '  0: "NXP_FRDM-MCXN236_printed_board_pose"',
            ]
        )
        + "\n",
        encoding="utf-8",
    )
    return path


def prepare_pose_dataset(
    pose_manifest_path: Path,
    review_manifest_path: Path,
    intake_manifest_path: Path,
    output_dir: Path,
    *,
    holdout_image_id: str | None,
    rotation_angles: Sequence[float],
    rotation_grid: Sequence[float] = (),
) -> dict[str, Any]:
    pose = load_json(pose_manifest_path)
    review = load_json(review_manifest_path)
    intake = load_json(intake_manifest_path)
    if pose.get("session_id") != review.get("session_id") or pose.get(
        "session_id"
    ) != intake.get("session_id"):
        raise YoloExperimentError("pose, review, and intake session IDs differ")
    if pose.get("media_domain") != "printed_reference":
        raise YoloExperimentError(
            "this local pose experiment accepts printed-reference data only"
        )
    intake_by_id = {item["image_id"]: item for item in intake.get("images") or []}
    review_by_id = {item["image_id"]: item for item in review.get("records") or []}
    image_sizes: dict[str, tuple[int, int]] = {}
    for image_id, item in intake_by_id.items():
        path = resolve_repo_path(item["image_path"])
        verify_digest(path, item["sha256"], f"media for {image_id}")
        image_sizes[image_id] = _image_size(path)
    validate_pose_annotation(pose, image_sizes)
    records = pose["records"]
    if rotation_angles and len(rotation_angles) != len(records):
        raise YoloExperimentError(
            "rotation angle count must equal the pose record count"
        )
    if rotation_angles and rotation_grid:
        raise YoloExperimentError(
            "use either per-image rotation angles or a shared rotation grid"
        )
    if holdout_image_id and holdout_image_id not in {
        item["image_id"] for item in records
    }:
        raise YoloExperimentError(f"unknown holdout image ID: {holdout_image_id}")
    output_dir = output_dir.resolve()
    for split in ("train", "holdout"):
        (output_dir / "images" / split).mkdir(parents=True, exist_ok=True)
        (output_dir / "labels" / split).mkdir(parents=True, exist_ok=True)
    items: list[dict[str, Any]] = []
    for index, record in enumerate(records):
        image_id = record["image_id"]
        if image_id not in review_by_id:
            raise YoloExperimentError(
                f"pose record lacks reviewed detection source: {image_id}"
            )
        intake_item = intake_by_id[image_id]
        source = resolve_repo_path(intake_item["image_path"])
        split = "holdout" if image_id == holdout_image_id else "train"
        target = output_dir / "images" / split / f"{image_id}{source.suffix.lower()}"
        label = output_dir / "labels" / split / f"{image_id}.txt"
        prepared_sha = copy_training_image(source, target)
        width, height = image_sizes[image_id]
        label.write_text(_pose_row(record, width, height, 0.0), encoding="utf-8")
        items.append(
            {
                "image_id": image_id,
                "split": split,
                "source_media_sha256": intake_item["sha256"],
                "prepared_media_sha256": prepared_sha,
                "pose_label_sha256": sha256_file(label),
            }
        )
        augmentation_angles = (
            [float(rotation_angles[index])]
            if rotation_angles
            else [float(angle) for angle in rotation_grid]
        )
        for angle in augmentation_angles:
            angle %= 360.0
            if not math.isfinite(angle):
                raise YoloExperimentError("rotation angles must be finite")
            augmented_id = f"{image_id}__rotate_{angle:g}"
            augmented_image = output_dir / "images" / split / f"{augmented_id}.jpg"
            augmented_label = output_dir / "labels" / split / f"{augmented_id}.txt"
            augmented_sha = rotate_training_image(target, augmented_image, angle)
            augmented_label.write_text(
                _pose_row(record, width, height, angle), encoding="utf-8"
            )
            items.append(
                {
                    "image_id": augmented_id,
                    "parent_image_id": image_id,
                    "split": split,
                    "augmentation": {
                        "kind": "centre_rotation",
                        "angle_degrees_counter_clockwise": angle,
                        "canvas_policy": "preserve_size_clip_outside",
                    },
                    "source_media_sha256": intake_item["sha256"],
                    "prepared_media_sha256": augmented_sha,
                    "pose_label_sha256": sha256_file(augmented_label),
                }
            )
    yaml_path = _write_pose_yaml(
        output_dir, len(pose["keypoint_names"]), bool(holdout_image_id)
    )
    manifest: dict[str, Any] = {
        "schema_version": "1.0",
        "kind": "yolo_pose_local_smoke",
        "release_eligible": False,
        "evaluation_valid": False,
        "evaluation_limitation": "all originals and augmented descendants come from one printed-guide acquisition session",
        "media_domain": "printed_reference",
        "session_id": pose["session_id"],
        "keypoint_names": pose["keypoint_names"],
        "projected_landmarks": pose.get("projected_landmarks") or [],
        "projected_landmark_basis": pose.get("projected_landmark_basis"),
        "corner_contract": CORNER_NAMES,
        "holdout_image_id": holdout_image_id,
        "items": items,
        "source_pose_manifest_sha256": sha256_file(pose_manifest_path),
        "source_review_manifest_sha256": sha256_file(review_manifest_path),
        "source_intake_manifest_sha256": sha256_file(intake_manifest_path),
        "data_yaml": str(yaml_path.resolve()),
    }
    manifest["dataset_digest"] = canonical_digest(manifest)
    (output_dir / "dataset-manifest.json").write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    return manifest


def train_pose_model(
    dataset_dir: Path,
    output_dir: Path,
    *,
    base_model: str,
    epochs: int,
    image_size: int,
    batch: int,
    device: str,
) -> dict[str, Any]:
    try:
        import torch
        import ultralytics
        from ultralytics import YOLO
    except ImportError as exc:
        raise YoloExperimentError(
            "install the optional Ultralytics dependencies"
        ) from exc
    dataset_dir = dataset_dir.resolve()
    output_dir = output_dir.resolve()
    dataset = load_json(dataset_dir / "dataset-manifest.json")
    model_cache = output_dir / "model-cache"
    model_cache.mkdir(parents=True, exist_ok=True)
    previous_cwd = Path.cwd()
    os.chdir(model_cache)
    try:
        model = YOLO(base_model)
        base_path = Path(model.ckpt_path).resolve()
        result = model.train(
            data=str(dataset_dir / "data.yaml"),
            epochs=epochs,
            imgsz=image_size,
            batch=batch,
            device=device,
            workers=0,
            seed=0,
            deterministic=True,
            degrees=0.0,
            fliplr=0.0,
            flipud=0.0,
            mosaic=0.0,
            project=str(output_dir / "runs"),
            name="train",
            exist_ok=True,
            plots=True,
            verbose=True,
        )
    finally:
        os.chdir(previous_cwd)
    checkpoint = Path(result.save_dir) / "weights" / "best.pt"
    if not checkpoint.is_file():
        raise YoloExperimentError("pose training completed without best.pt")
    metrics = {
        key: float(value)
        for key, value in (result.results_dict or {}).items()
        if isinstance(value, (int, float))
    }
    manifest = {
        "schema_version": "1.0",
        "kind": "yolo_pose_local_smoke_model",
        "release_eligible": False,
        "evaluation_valid": False,
        "dataset_digest": dataset["dataset_digest"],
        "dataset_manifest_sha256": sha256_file(dataset_dir / "dataset-manifest.json"),
        "keypoint_names": dataset["keypoint_names"],
        "projected_landmarks": dataset.get("projected_landmarks") or [],
        "projected_landmark_basis": dataset.get("projected_landmark_basis"),
        "base_model": base_model,
        "base_model_sha256": sha256_file(base_path),
        "checkpoint": str(checkpoint.resolve()),
        "checkpoint_sha256": sha256_file(checkpoint),
        "epochs": epochs,
        "image_size": image_size,
        "batch": batch,
        "device": device,
        "metrics_are_training_session_smoke_only": metrics,
        "environment": {
            "python": sys.version,
            "platform": platform.platform(),
            "torch": torch.__version__,
            "ultralytics": ultralytics.__version__,
        },
    }
    (output_dir / "training-manifest.json").write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    return manifest


def run_pose_inference(
    checkpoint: Path,
    training_manifest_path: Path,
    image_path: Path,
    output_dir: Path,
    *,
    confidence: float,
    image_size: int,
    device: str,
) -> dict[str, Any]:
    try:
        import cv2
        from ultralytics import YOLO
    except ImportError as exc:
        raise YoloExperimentError(
            "install the optional Ultralytics dependencies"
        ) from exc
    training_manifest = load_json(training_manifest_path)
    names = training_manifest["keypoint_names"]
    projected_landmarks = training_manifest.get("projected_landmarks") or []
    model = YOLO(str(checkpoint.resolve()))
    result = model.predict(
        source=str(image_path.resolve()),
        conf=confidence,
        imgsz=image_size,
        device=device,
        max_det=1,
        verbose=True,
    )[0]
    annotated = cv2.imread(str(image_path.resolve()))
    if annotated is None:
        raise YoloExperimentError(f"failed to read {image_path}")
    detections: list[dict[str, Any]] = []
    image_height, image_width = annotated.shape[:2]
    for detection_index, box in enumerate(result.boxes):
        coordinates = result.keypoints.xy[detection_index].cpu().tolist()
        confidences = result.keypoints.conf[detection_index].cpu().tolist()
        points = []
        for name, coordinate, point_confidence in zip(names, coordinates, confidences):
            x, y = (float(value) for value in coordinate)
            score = float(point_confidence)
            points.append(
                {"name": name, "x_pixels": x, "y_pixels": y, "confidence": score}
            )
            if score >= confidence:
                cv2.circle(
                    annotated, (round(x), round(y)), 8, (38, 220, 166), -1, cv2.LINE_AA
                )
                if name not in CORNER_NAMES:
                    cv2.putText(
                        annotated,
                        name.replace("-", " "),
                        (round(x) + 10, round(y) - 8),
                        cv2.FONT_HERSHEY_SIMPLEX,
                        0.45,
                        (20, 245, 210),
                        1,
                        cv2.LINE_AA,
                    )
        corners = points[:4]
        projection_status = "rejected_low_corner_confidence"
        if all(point["confidence"] >= confidence for point in corners):
            polygon = [
                [round(point["x_pixels"]), round(point["y_pixels"])]
                for point in corners
            ]
            accepted, projection_status = validate_projection_quad(
                [(float(x), float(y)) for x, y in polygon],
                [float(value) for value in box.xyxy[0].tolist()],
                (image_width, image_height),
            )
            if accepted:
                import numpy as np

                cv2.polylines(
                    annotated,
                    [np.array(polygon, dtype="int32")],
                    True,
                    (255, 177, 38),
                    8,
                    cv2.LINE_AA,
                )
                if projected_landmarks:
                    transform = cv2.getPerspectiveTransform(
                        np.float32([[0, 0], [1, 0], [1, 1], [0, 1]]),
                        np.float32(polygon),
                    )
                    source_points = np.float32(
                        [[item["u"], item["v"]] for item in projected_landmarks]
                    ).reshape(-1, 1, 2)
                    mapped = cv2.perspectiveTransform(source_points, transform).reshape(
                        -1, 2
                    )
                    for item, (x, y) in zip(projected_landmarks, mapped):
                        projected = {
                            "name": item["name"],
                            "display_name": item.get("display_name", item["name"]),
                            "x_pixels": float(x),
                            "y_pixels": float(y),
                            "source": "board_homography",
                        }
                        points.append(projected)
                        cv2.circle(
                            annotated,
                            (round(float(x)), round(float(y))),
                            7,
                            (38, 220, 166),
                            -1,
                            cv2.LINE_AA,
                        )
        detections.append(
            {
                "confidence": float(box.conf.item()),
                "xyxy_pixels": [float(value) for value in box.xyxy[0].tolist()],
                "projection_status": projection_status,
                "keypoints": points,
            }
        )
    output_dir.mkdir(parents=True, exist_ok=True)
    annotated_path = output_dir / "pose-prediction.jpg"
    if not cv2.imwrite(str(annotated_path), annotated):
        raise YoloExperimentError(f"failed to write {annotated_path}")
    manifest = {
        "schema_version": "1.0",
        "kind": "yolo_pose_local_smoke_inference",
        "release_eligible": False,
        "evaluation_valid": False,
        "checkpoint_sha256": sha256_file(checkpoint),
        "image_sha256": sha256_file(image_path),
        "confidence_threshold": confidence,
        "detections": detections,
        "annotated_image": str(annotated_path.resolve()),
        "annotated_image_sha256": sha256_file(annotated_path),
    }
    (output_dir / "pose-prediction.json").write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    return manifest


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    prepare = commands.add_parser("prepare")
    prepare.add_argument("--pose-manifest", required=True, type=Path)
    prepare.add_argument("--review-manifest", required=True, type=Path)
    prepare.add_argument("--intake-manifest", required=True, type=Path)
    prepare.add_argument("--output-dir", required=True, type=Path)
    prepare.add_argument("--holdout-image-id")
    prepare.add_argument("--rotation-angles")
    prepare.add_argument("--rotation-grid")
    train = commands.add_parser("train")
    train.add_argument("--dataset-dir", required=True, type=Path)
    train.add_argument("--output-dir", required=True, type=Path)
    train.add_argument("--base-model", default="yolo11n-pose.pt")
    train.add_argument("--epochs", type=int, default=100)
    train.add_argument("--image-size", type=int, default=640)
    train.add_argument("--batch", type=int, default=4)
    train.add_argument("--device", default="cpu")
    infer = commands.add_parser("infer")
    infer.add_argument("--checkpoint", required=True, type=Path)
    infer.add_argument("--training-manifest", required=True, type=Path)
    infer.add_argument("--image", required=True, type=Path)
    infer.add_argument("--output-dir", required=True, type=Path)
    infer.add_argument("--confidence", type=float, default=0.25)
    infer.add_argument("--image-size", type=int, default=640)
    infer.add_argument("--device", default="cpu")
    args = parser.parse_args(argv)
    if args.command == "prepare":
        result = prepare_pose_dataset(
            args.pose_manifest,
            args.review_manifest,
            args.intake_manifest,
            args.output_dir,
            holdout_image_id=args.holdout_image_id,
            rotation_angles=(
                [float(value) for value in args.rotation_angles.split(",")]
                if args.rotation_angles
                else []
            ),
            rotation_grid=(
                [float(value) for value in args.rotation_grid.split(",")]
                if args.rotation_grid
                else []
            ),
        )
    elif args.command == "train":
        result = train_pose_model(
            args.dataset_dir,
            args.output_dir,
            base_model=args.base_model,
            epochs=args.epochs,
            image_size=args.image_size,
            batch=args.batch,
            device=args.device,
        )
    else:
        result = run_pose_inference(
            args.checkpoint,
            args.training_manifest,
            args.image,
            args.output_dir,
            confidence=args.confidence,
            image_size=args.image_size,
            device=args.device,
        )
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except YoloExperimentError as exc:
        print(f"error: {exc}", file=sys.stderr)
        raise SystemExit(1)
