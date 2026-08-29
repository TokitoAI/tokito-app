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


def load_reference_corners(
    pose_manifest_path: Path, image_id: str
) -> list[tuple[float, float]]:
    """Load one explicitly reviewed four-corner reference from a pose manifest."""
    document = load_json(pose_manifest_path)
    if (document.get("keypoint_names") or [])[:4] != CORNER_NAMES:
        raise YoloExperimentError(
            "reference pose manifest has an invalid corner contract"
        )
    record = next(
        (
            item
            for item in document.get("records") or []
            if item.get("image_id") == image_id
        ),
        None,
    )
    if record is None:
        raise YoloExperimentError(f"reference pose image ID was not found: {image_id}")
    corners = record.get("keypoints") or []
    if len(corners) < 4 or any(
        int(point.get("visibility", 0)) != 2 for point in corners[:4]
    ):
        raise YoloExperimentError(
            "reference pose requires four reviewed visible corners"
        )
    return [
        (float(point["x_pixels"]), float(point["y_pixels"])) for point in corners[:4]
    ]


def validate_reference_options(
    reference_image: Path | None,
    reference_pose_manifest: Path | None,
    reference_image_id: str | None,
    reference_intake_manifest: Path | None = None,
) -> str:
    """Select no reference, one explicit reference, or a reviewed reference bank."""
    single = [
        reference_image is not None,
        reference_pose_manifest is not None,
        bool(reference_image_id),
    ]
    if reference_intake_manifest is not None:
        if (
            reference_pose_manifest is None
            or reference_image is not None
            or reference_image_id
        ):
            raise YoloExperimentError(
                "reference-bank refinement requires only --reference-pose-manifest "
                "and --reference-intake-manifest"
            )
        return "bank"
    if any(single) and not all(single):
        raise YoloExperimentError(
            "reference refinement requires --reference-image, "
            "--reference-pose-manifest, and --reference-image-id together"
        )
    return "single" if all(single) else "none"


def load_reference_bank(
    pose_manifest_path: Path,
    intake_manifest_path: Path,
    *,
    excluded_image_sha256: str,
) -> list[dict[str, Any]]:
    """Load every fully reviewed view while excluding the query's exact bytes."""
    pose = load_json(pose_manifest_path)
    intake = load_json(intake_manifest_path)
    if pose.get("session_id") != intake.get("session_id"):
        raise YoloExperimentError("reference pose and intake sessions do not match")
    intake_by_id = {item["image_id"]: item for item in intake.get("images") or []}
    references = []
    for record in pose.get("records") or []:
        points = record.get("keypoints") or []
        if len(points) < 4 or any(
            int(point.get("visibility", 0)) != 2 for point in points[:4]
        ):
            continue
        image_id = record.get("image_id")
        item = intake_by_id.get(image_id)
        if item is None:
            raise YoloExperimentError(f"reference intake is missing image: {image_id}")
        path = resolve_repo_path(item["image_path"])
        verify_digest(path, item["sha256"], f"reference image {image_id}")
        if item["sha256"] == excluded_image_sha256:
            continue
        references.append(
            {
                "image_id": image_id,
                "image_path": path,
                "image_sha256": item["sha256"],
                "corners": [
                    (float(point["x_pixels"]), float(point["y_pixels"]))
                    for point in points[:4]
                ],
            }
        )
    if not references:
        raise YoloExperimentError("reference bank has no eligible reviewed views")
    return references


def refine_quad_with_reference(
    reference_image_path: Path,
    reference_corners: list[tuple[float, float]],
    query_image: Any,
    detection_bbox: list[float],
) -> tuple[list[tuple[float, float]] | None, dict[str, Any]]:
    """Estimate a reviewed-reference-to-query homography with bounded ORB/RANSAC."""
    import cv2
    import numpy as np

    reference = cv2.imread(str(reference_image_path.resolve()), cv2.IMREAD_GRAYSCALE)
    if reference is None:
        raise YoloExperimentError(
            f"failed to read reference image: {reference_image_path}"
        )
    reference_height, reference_width = reference.shape
    reference_bbox = [
        min(x for x, _ in reference_corners),
        min(y for _, y in reference_corners),
        max(x for x, _ in reference_corners),
        max(y for _, y in reference_corners),
    ]
    accepted_reference, reference_reason = validate_projection_quad(
        reference_corners,
        reference_bbox,
        (reference_width, reference_height),
    )
    if not accepted_reference:
        raise YoloExperimentError(
            f"unsafe reviewed reference geometry: {reference_reason}"
        )
    query_gray = cv2.cvtColor(query_image, cv2.COLOR_BGR2GRAY)
    reference_mask = np.zeros(reference.shape, dtype=np.uint8)
    cv2.fillConvexPoly(reference_mask, np.int32(reference_corners), 255)
    query_mask = np.zeros(query_gray.shape, dtype=np.uint8)
    x1, y1, x2, y2 = detection_bbox
    box_width = max(1.0, x2 - x1)
    box_height = max(1.0, y2 - y1)
    height, width = query_gray.shape
    roi = (
        max(0, round(x1 - 0.1 * box_width)),
        max(0, round(y1 - 0.1 * box_height)),
        min(width, round(x2 + 0.1 * box_width)),
        min(height, round(y2 + 0.1 * box_height)),
    )
    cv2.rectangle(query_mask, roi[:2], roi[2:], 255, -1)
    detector = cv2.ORB_create(
        nfeatures=5000,
        scaleFactor=1.2,
        nlevels=8,
        edgeThreshold=19,
        fastThreshold=10,
    )
    reference_keypoints, reference_descriptors = detector.detectAndCompute(
        reference, reference_mask
    )
    query_keypoints, query_descriptors = detector.detectAndCompute(
        query_gray, query_mask
    )
    evidence: dict[str, Any] = {
        "method": "ORB_HAMMING_KNN_RANSAC",
        "reference_keypoints": len(reference_keypoints),
        "query_keypoints": len(query_keypoints),
        "ratio_threshold": 0.75,
        "ransac_reprojection_threshold_pixels": 5.0,
    }
    if reference_descriptors is None or query_descriptors is None:
        evidence["status"] = "rejected_missing_descriptors"
        return None, evidence
    pairs = cv2.BFMatcher(cv2.NORM_HAMMING).knnMatch(
        reference_descriptors, query_descriptors, k=2
    )
    matches = [
        pair[0]
        for pair in pairs
        if len(pair) == 2 and pair[0].distance < 0.75 * pair[1].distance
    ]
    evidence["ratio_test_matches"] = len(matches)
    if len(matches) < 16:
        evidence["status"] = "rejected_insufficient_ratio_matches"
        return None, evidence
    source = np.float32(
        [reference_keypoints[match.queryIdx].pt for match in matches]
    ).reshape(-1, 1, 2)
    target = np.float32(
        [query_keypoints[match.trainIdx].pt for match in matches]
    ).reshape(-1, 1, 2)
    transform, inlier_mask = cv2.findHomography(
        source, target, cv2.RANSAC, 5.0, maxIters=5000, confidence=0.999
    )
    if transform is None or inlier_mask is None:
        evidence["status"] = "rejected_homography_not_found"
        return None, evidence
    inliers = inlier_mask.ravel().astype(bool)
    inlier_count = int(inliers.sum())
    inlier_ratio = inlier_count / len(matches)
    evidence["inliers"] = inlier_count
    evidence["inlier_ratio"] = inlier_ratio
    if inlier_count < 12 or inlier_ratio < 0.35:
        evidence["status"] = "rejected_weak_ransac_consensus"
        return None, evidence
    projected_matches = cv2.perspectiveTransform(source, transform)
    errors = np.linalg.norm(projected_matches - target, axis=2).ravel()[inliers]
    median_error = float(np.median(errors))
    p95_error = float(np.percentile(errors, 95))
    evidence["median_inlier_reprojection_error_pixels"] = median_error
    evidence["p95_inlier_reprojection_error_pixels"] = p95_error
    if median_error > 4.0 or p95_error > 9.0:
        evidence["status"] = "rejected_excessive_reprojection_error"
        return None, evidence
    transformed = cv2.perspectiveTransform(
        np.float32(reference_corners).reshape(-1, 1, 2), transform
    ).reshape(-1, 2)
    refined = [(float(x), float(y)) for x, y in transformed]
    accepted, reason = validate_projection_quad(
        refined, detection_bbox, (width, height)
    )
    if not accepted:
        evidence["status"] = f"rejected_{reason}"
        return None, evidence
    evidence["status"] = "accepted"
    return refined, evidence


def refine_quad_with_reference_bank(
    references: list[dict[str, Any]],
    query_image: Any,
    detection_bbox: list[float],
) -> tuple[list[tuple[float, float]] | None, dict[str, Any]]:
    """Choose a medoid pose from independently verified, agreeing references."""
    attempts = []
    accepted = []
    for reference in references:
        corners, evidence = refine_quad_with_reference(
            reference["image_path"],
            reference["corners"],
            query_image,
            detection_bbox,
        )
        attempt = {
            "image_id": reference["image_id"],
            "image_sha256": reference["image_sha256"],
            **evidence,
        }
        attempts.append(attempt)
        if corners is not None:
            accepted.append((reference, corners, attempt))
    result: dict[str, Any] = {
        "method": "reviewed_reference_bank_medoid",
        "attempted_references": len(references),
        "accepted_references": len(accepted),
        "attempts": attempts,
    }
    maximum_inliers = max(
        (int(attempt["inliers"]) for _, _, attempt in accepted), default=0
    )
    support_floor = max(12, math.ceil(maximum_inliers * 0.1))
    qualified = [item for item in accepted if int(item[2]["inliers"]) >= support_floor]
    result["relative_inlier_support_floor"] = support_floor
    result["qualified_references"] = len(qualified)
    required = 2 if len(references) > 1 else 1
    if len(qualified) < required:
        exceptional = max(
            accepted,
            key=lambda item: int(item[2]["inliers"]),
            default=None,
        )
        if exceptional is not None:
            attempt = exceptional[2]
            exceptionally_supported = (
                int(attempt["inliers"]) >= 250
                and float(attempt["inlier_ratio"]) >= 0.8
                and float(attempt["median_inlier_reprojection_error_pixels"]) <= 1.5
                and float(attempt["p95_inlier_reprojection_error_pixels"]) <= 3.0
            )
            if exceptionally_supported:
                result.update(
                    {
                        "status": "accepted",
                        "selection_policy": "exceptional_single_reference",
                        "selected_image_id": exceptional[0]["image_id"],
                        "selected_image_sha256": exceptional[0]["image_sha256"],
                        "consensus_count": 1,
                    }
                )
                return exceptional[1], result
        result["status"] = "rejected_insufficient_reference_consensus"
        return None, result
    x1, y1, x2, y2 = detection_bbox
    diagonal = max(1.0, math.hypot(x2 - x1, y2 - y1))

    def disagreement(
        first: list[tuple[float, float]], second: list[tuple[float, float]]
    ) -> float:
        return sum(
            math.hypot(ax - bx, ay - by) for (ax, ay), (bx, by) in zip(first, second)
        ) / (4.0 * diagonal)

    ranked = []
    for reference, corners, attempt in qualified:
        agreements = [
            disagreement(corners, other_corners) for _, other_corners, _ in qualified
        ]
        consensus = sum(value <= 0.02 for value in agreements)
        ranked.append(
            (
                consensus,
                -sum(agreements) / len(agreements),
                int(attempt["inliers"]),
                -float(attempt["median_inlier_reprojection_error_pixels"]),
                reference,
                corners,
                attempt,
            )
        )
    selected = max(ranked, key=lambda item: item[:4])
    consensus_count = selected[0]
    if consensus_count < required:
        result["status"] = "rejected_reference_pose_disagreement"
        result["consensus_threshold_fraction_detection_diagonal"] = 0.02
        return None, result
    result.update(
        {
            "status": "accepted",
            "selected_image_id": selected[4]["image_id"],
            "selected_image_sha256": selected[4]["image_sha256"],
            "consensus_count": consensus_count,
            "consensus_threshold_fraction_detection_diagonal": 0.02,
        }
    )
    return selected[5], result


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
    media_domain = pose.get("media_domain")
    if media_domain not in {"printed_reference", "physical_capture"}:
        raise YoloExperimentError(
            "pose media domain must be printed_reference or physical_capture"
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
        "evaluation_limitation": (
            "all originals and augmented descendants come from one acquisition "
            f"session in the {media_domain} domain"
        ),
        "media_domain": media_domain,
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
    final_validation_only: bool = False,
    workers: int = 0,
    cache: str | bool = False,
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
            workers=workers,
            cache=cache,
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
            val=not final_validation_only,
        )
    finally:
        os.chdir(previous_cwd)
    checkpoint_name = "last.pt" if final_validation_only else "best.pt"
    checkpoint = Path(result.save_dir) / "weights" / checkpoint_name
    if not checkpoint.is_file():
        raise YoloExperimentError(f"pose training completed without {checkpoint_name}")
    metric_result = result
    if final_validation_only:
        metric_result = YOLO(str(checkpoint)).val(
            data=str(dataset_dir / "data.yaml"),
            imgsz=image_size,
            batch=batch,
            device=device,
            workers=workers,
            cache=cache,
            plots=True,
            verbose=True,
        )
    metrics = {
        key: float(value)
        for key, value in (metric_result.results_dict or {}).items()
        if isinstance(value, (int, float))
    }
    manifest = {
        "schema_version": "1.0",
        "kind": "yolo_pose_local_smoke_model",
        "release_eligible": False,
        "evaluation_valid": False,
        "dataset_digest": dataset["dataset_digest"],
        "media_domain": dataset.get("media_domain"),
        "source_dataset_digests": dataset.get("source_dataset_digests")
        or [dataset["dataset_digest"]],
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
        "validation_policy": ("final_only" if final_validation_only else "each_epoch"),
        "workers": workers,
        "cache": cache,
        "metrics_are_training_session_smoke_only": metrics,
        "environment": {
            "python": sys.version,
            "platform": platform.platform(),
            "torch": torch.__version__,
            "cuda": torch.version.cuda,
            "cuda_available": torch.cuda.is_available(),
            "accelerator": (
                torch.cuda.get_device_name(0) if torch.cuda.is_available() else None
            ),
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
    reference_image: Path | None = None,
    reference_pose_manifest: Path | None = None,
    reference_image_id: str | None = None,
    reference_intake_manifest: Path | None = None,
) -> dict[str, Any]:
    try:
        import cv2
        from ultralytics import YOLO
    except ImportError as exc:
        raise YoloExperimentError(
            "install the optional Ultralytics dependencies"
        ) from exc
    training_manifest = load_json(training_manifest_path)
    reference_mode = validate_reference_options(
        reference_image,
        reference_pose_manifest,
        reference_image_id,
        reference_intake_manifest,
    )
    reference_corners = (
        load_reference_corners(reference_pose_manifest, reference_image_id)
        if reference_mode == "single" and reference_pose_manifest and reference_image_id
        else None
    )
    reference_bank = (
        load_reference_bank(
            reference_pose_manifest,
            reference_intake_manifest,
            excluded_image_sha256=sha256_file(image_path),
        )
        if reference_mode == "bank"
        and reference_pose_manifest
        and reference_intake_manifest
        else []
    )
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
            if score >= confidence and name not in CORNER_NAMES:
                cv2.circle(
                    annotated, (round(x), round(y)), 8, (38, 220, 166), -1, cv2.LINE_AA
                )
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
        pose_source = "yolo_pose"
        reference_refinement = None
        polygon_float: list[tuple[float, float]] = []
        polygon: list[list[int]] = []
        detection_bbox = [float(value) for value in box.xyxy[0].tolist()]
        if all(point["confidence"] >= confidence for point in corners):
            polygon_float = [
                (float(point["x_pixels"]), float(point["y_pixels"]))
                for point in corners
            ]
            if reference_mode == "bank":
                refined, reference_refinement = refine_quad_with_reference_bank(
                    reference_bank,
                    annotated,
                    detection_bbox,
                )
                if refined is None:
                    projection_status = reference_refinement["status"]
                    polygon_float = []
                else:
                    polygon_float = refined
                    pose_source = "reviewed_reference_bank_homography"
                    for point, (x, y) in zip(corners, refined):
                        point["x_pixels"] = x
                        point["y_pixels"] = y
                        point["source"] = pose_source
            elif reference_mode == "single" and reference_image and reference_corners:
                refined, reference_refinement = refine_quad_with_reference(
                    reference_image,
                    reference_corners,
                    annotated,
                    detection_bbox,
                )
                if refined is None:
                    projection_status = reference_refinement["status"]
                    polygon_float = []
                else:
                    polygon_float = refined
                    pose_source = "reviewed_reference_homography"
                    for point, (x, y) in zip(corners, refined):
                        point["x_pixels"] = x
                        point["y_pixels"] = y
                        point["source"] = pose_source
            polygon = [[round(x), round(y)] for x, y in polygon_float]
        if len(polygon_float) == 4:
            accepted, projection_status = validate_projection_quad(
                [(float(x), float(y)) for x, y in polygon],
                detection_bbox,
                (image_width, image_height),
            )
            if accepted:
                import numpy as np

                for x, y in polygon:
                    cv2.circle(annotated, (x, y), 9, (38, 220, 166), -1, cv2.LINE_AA)
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
                "pose_source": pose_source,
                "reference_refinement": reference_refinement,
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
        "reference": (
            {
                "image_id": reference_image_id,
                "image_sha256": sha256_file(reference_image),
                "pose_manifest_sha256": sha256_file(reference_pose_manifest),
            }
            if reference_mode == "single"
            and reference_image
            and reference_pose_manifest
            else None
        ),
        "reference_bank": (
            {
                "pose_manifest_sha256": sha256_file(reference_pose_manifest),
                "intake_manifest_sha256": sha256_file(reference_intake_manifest),
                "eligible_reference_count": len(reference_bank),
            }
            if reference_mode == "bank"
            and reference_pose_manifest
            and reference_intake_manifest
            else None
        ),
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
    train.add_argument("--final-validation-only", action="store_true")
    train.add_argument("--workers", type=int, default=0)
    train.add_argument("--cache", choices=("none", "ram", "disk"), default="none")
    infer = commands.add_parser("infer")
    infer.add_argument("--checkpoint", required=True, type=Path)
    infer.add_argument("--training-manifest", required=True, type=Path)
    infer.add_argument("--image", required=True, type=Path)
    infer.add_argument("--output-dir", required=True, type=Path)
    infer.add_argument("--confidence", type=float, default=0.25)
    infer.add_argument("--image-size", type=int, default=640)
    infer.add_argument("--device", default="cpu")
    infer.add_argument("--reference-image", type=Path)
    infer.add_argument("--reference-pose-manifest", type=Path)
    infer.add_argument("--reference-image-id")
    infer.add_argument("--reference-intake-manifest", type=Path)
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
            final_validation_only=args.final_validation_only,
            workers=args.workers,
            cache=False if args.cache == "none" else args.cache,
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
            reference_image=args.reference_image,
            reference_pose_manifest=args.reference_pose_manifest,
            reference_image_id=args.reference_image_id,
            reference_intake_manifest=args.reference_intake_manifest,
        )
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except YoloExperimentError as exc:
        print(f"error: {exc}", file=sys.stderr)
        raise SystemExit(1)
