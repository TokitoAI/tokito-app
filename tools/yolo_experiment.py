#!/usr/bin/env python3
"""Prepare, train, and inspect provenance-bound local YOLO experiments.

Release model construction rejects a review manifest that is not trainable.
An explicitly requested ``--local-smoke`` run may exercise the pipeline with
non-release data, but its generated manifest remains permanently marked as
experiment-only and evaluation-invalid.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import platform
import shutil
import sys
from collections.abc import Sequence
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]


class YoloExperimentError(RuntimeError):
    """The requested experiment violates provenance or geometry rules."""


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return "sha256:" + digest.hexdigest()


def canonical_digest(value: Any) -> str:
    payload = json.dumps(
        value,
        ensure_ascii=False,
        allow_nan=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return "sha256:" + hashlib.sha256(payload).hexdigest()


def resolve_repo_path(value: str) -> Path:
    path = Path(value.replace("\\", "/"))
    return path if path.is_absolute() else ROOT / path


def verify_digest(path: Path, expected: str, label: str) -> None:
    if not path.is_file():
        raise YoloExperimentError(f"missing {label}: {path}")
    actual = sha256_file(path)
    if actual != expected:
        raise YoloExperimentError(
            f"{label} digest mismatch: expected {expected}, got {actual}"
        )


def copy_training_image(source: Path, target: Path) -> str:
    """Copy an image and deterministically repair a missing JPEG terminator."""
    shutil.copy2(source, target)
    if target.suffix.lower() in {".jpg", ".jpeg"}:
        with target.open("rb") as handle:
            handle.seek(-2, os.SEEK_END)
            terminated = handle.read() == b"\xff\xd9"
        if not terminated:
            try:
                from PIL import Image, ImageOps
            except ImportError as exc:
                raise YoloExperimentError(
                    "Pillow is required to normalize a truncated JPEG training copy"
                ) from exc
            try:
                with Image.open(target) as image:
                    ImageOps.exif_transpose(image).save(
                        target, "JPEG", subsampling=0, quality=100
                    )
            except Exception as exc:
                raise YoloExperimentError(
                    f"invalid JPEG training image: {source}"
                ) from exc
    return sha256_file(target)


def image_dimensions(path: Path) -> tuple[int, int]:
    try:
        from PIL import Image
    except ImportError as exc:
        raise YoloExperimentError(
            "Pillow is required to inspect training images"
        ) from exc
    with Image.open(path) as image:
        return image.size


def validate_yolo_label(path: Path, class_count: int) -> int:
    rows = [
        line.strip()
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    for row_number, row in enumerate(rows, start=1):
        fields = row.split()
        if len(fields) != 5:
            raise YoloExperimentError(f"{path}:{row_number}: expected 5 YOLO fields")
        try:
            class_id = int(fields[0])
            values = [float(value) for value in fields[1:]]
        except ValueError as exc:
            raise YoloExperimentError(
                f"{path}:{row_number}: non-numeric YOLO field"
            ) from exc
        if class_id < 0 or class_id >= class_count:
            raise YoloExperimentError(f"{path}:{row_number}: class ID out of range")
        if any(value < 0.0 or value > 1.0 for value in values):
            raise YoloExperimentError(
                f"{path}:{row_number}: normalized value out of range"
            )
        if values[2] <= 0.0 or values[3] <= 0.0:
            raise YoloExperimentError(
                f"{path}:{row_number}: box width/height must be positive"
            )
    return len(rows)


def _rotate_point(
    x: float,
    y: float,
    angle_degrees: float,
    image_width: int,
    image_height: int,
) -> tuple[float, float]:
    """Map a normalized point through Pillow's pixel-space centre rotation."""
    radians = math.radians(angle_degrees)
    cosine = math.cos(radians)
    sine = math.sin(radians)
    dx = x * image_width - image_width / 2.0
    dy = y * image_height - image_height / 2.0
    return (
        (cosine * dx + sine * dy + image_width / 2.0) / image_width,
        (-sine * dx + cosine * dy + image_height / 2.0) / image_height,
    )


def rotate_yolo_label(
    source: Path,
    target: Path,
    angle_degrees: float,
    image_width: int,
    image_height: int,
) -> int:
    """Rotate YOLO boxes, clip them to the image, and write deterministic labels."""
    output_rows: list[str] = []
    for row_number, row in enumerate(
        (line.strip() for line in source.read_text(encoding="utf-8").splitlines()),
        start=1,
    ):
        if not row:
            continue
        fields = row.split()
        if len(fields) != 5:
            raise YoloExperimentError(f"{source}:{row_number}: expected 5 YOLO fields")
        class_id = int(fields[0])
        center_x, center_y, width, height = (float(value) for value in fields[1:])
        corners = (
            (center_x - width / 2.0, center_y - height / 2.0),
            (center_x + width / 2.0, center_y - height / 2.0),
            (center_x + width / 2.0, center_y + height / 2.0),
            (center_x - width / 2.0, center_y + height / 2.0),
        )
        transformed = [
            _rotate_point(x, y, angle_degrees, image_width, image_height)
            for x, y in corners
        ]
        minimum_x = max(0.0, min(x for x, _ in transformed))
        maximum_x = min(1.0, max(x for x, _ in transformed))
        minimum_y = max(0.0, min(y for _, y in transformed))
        maximum_y = min(1.0, max(y for _, y in transformed))
        if maximum_x <= minimum_x or maximum_y <= minimum_y:
            continue
        output_rows.append(
            f"{class_id} {(minimum_x + maximum_x) / 2.0:.9f} "
            f"{(minimum_y + maximum_y) / 2.0:.9f} "
            f"{maximum_x - minimum_x:.9f} {maximum_y - minimum_y:.9f}"
        )
    target.write_text("\n".join(output_rows) + "\n", encoding="utf-8")
    return len(output_rows)


def rotate_training_image(source: Path, target: Path, angle_degrees: float) -> str:
    try:
        from PIL import Image, ImageOps
    except ImportError as exc:
        raise YoloExperimentError(
            "Pillow is required for rotation augmentation"
        ) from exc
    with Image.open(source) as image:
        normalized = ImageOps.exif_transpose(image).convert("RGB")
        rotated = normalized.rotate(
            angle_degrees,
            resample=Image.Resampling.BICUBIC,
            expand=False,
            fillcolor=(114, 114, 114),
        )
        rotated.save(target, "JPEG", subsampling=0, quality=95)
    return sha256_file(target)


def _write_data_yaml(
    output_dir: Path, class_names: list[str], *, holdout: bool
) -> Path:
    yaml_path = output_dir / "data.yaml"
    lines = [
        f"path: {json.dumps(output_dir.as_posix())}",
        "train: images/train",
        f"val: images/{'holdout' if holdout else 'train'}",
        f"test: images/{'holdout' if holdout else 'train'}",
        "names:",
    ]
    lines.extend(
        f"  {index}: {json.dumps(name)}" for index, name in enumerate(class_names)
    )
    yaml_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return yaml_path


def prepare_dataset(
    review_manifest_path: Path,
    intake_manifest_path: Path,
    output_dir: Path,
    *,
    local_smoke: bool,
    holdout_image_id: str | None = None,
    rotation_angles: Sequence[float] = (),
    rotation_grid: Sequence[float] = (),
) -> dict[str, Any]:
    output_dir = output_dir.resolve()
    review = load_json(review_manifest_path)
    intake = load_json(intake_manifest_path)
    trainable = bool(review.get("trainable"))
    if not trainable and not local_smoke:
        raise YoloExperimentError(
            "review manifest is not trainable; use only an explicitly local smoke run or approve provenance first"
        )
    if review.get("session_id") != intake.get("session_id"):
        raise YoloExperimentError("review and intake session IDs differ")
    records = review.get("records") or []
    if len(records) != review.get("task_count"):
        raise YoloExperimentError("review record count does not match task_count")
    class_names = review.get("class_names") or []
    if not class_names:
        raise YoloExperimentError("review manifest has no classes")
    intake_by_id = {item["image_id"]: item for item in intake.get("images") or []}
    if len(intake_by_id) != len(intake.get("images") or []):
        raise YoloExperimentError("intake contains duplicate image IDs")

    if holdout_image_id and holdout_image_id not in {
        record["image_id"] for record in records
    }:
        raise YoloExperimentError(f"unknown holdout image ID: {holdout_image_id}")
    if rotation_angles and len(rotation_angles) != len(records):
        raise YoloExperimentError(
            "rotation angle count must equal the reviewed image count"
        )
    if rotation_angles and rotation_grid:
        raise YoloExperimentError(
            "use either per-image rotation angles or a shared rotation grid"
        )
    for split in ("train", "holdout"):
        (output_dir / "images" / split).mkdir(parents=True, exist_ok=True)
        (output_dir / "labels" / split).mkdir(parents=True, exist_ok=True)
    prepared: list[dict[str, Any]] = []
    for record_index, record in enumerate(records):
        image_id = record["image_id"]
        intake_image = intake_by_id.get(image_id)
        if intake_image is None:
            raise YoloExperimentError(f"review cites unknown intake image: {image_id}")
        image_path = resolve_repo_path(intake_image["image_path"])
        label_path = resolve_repo_path(record["label_path"])
        verify_digest(image_path, record["media_sha256"], f"media for {image_id}")
        if record["media_sha256"] != intake_image["sha256"]:
            raise YoloExperimentError(
                f"review/intake media digest drift for {image_id}"
            )
        verify_digest(label_path, record["label_sha256"], f"label for {image_id}")
        box_count = validate_yolo_label(label_path, len(class_names))
        if box_count != record["box_count"]:
            raise YoloExperimentError(f"review/label box count drift for {image_id}")
        split = "holdout" if image_id == holdout_image_id else "train"
        image_target = (
            output_dir / "images" / split / f"{image_id}{image_path.suffix.lower()}"
        )
        label_target = output_dir / "labels" / split / f"{image_id}.txt"
        prepared_media_sha256 = copy_training_image(image_path, image_target)
        shutil.copy2(label_path, label_target)
        prepared.append(
            {
                "image_id": image_id,
                "media_sha256": record["media_sha256"],
                "prepared_media_sha256": prepared_media_sha256,
                "label_sha256": record["label_sha256"],
                "box_count": box_count,
                "split": split,
            }
        )
        augmentation_angles = (
            [float(rotation_angles[record_index])]
            if rotation_angles
            else [float(angle) for angle in rotation_grid]
        )
        for angle in augmentation_angles:
            if not math.isfinite(angle):
                raise YoloExperimentError("rotation angles must be finite")
            normalized_angle = angle % 360.0
            augmented_id = f"{image_id}__rotate_{normalized_angle:g}"
            augmented_image = output_dir / "images" / split / f"{augmented_id}.jpg"
            augmented_label = output_dir / "labels" / split / f"{augmented_id}.txt"
            augmented_media_sha256 = rotate_training_image(
                image_target, augmented_image, normalized_angle
            )
            augmented_box_count = rotate_yolo_label(
                label_target,
                augmented_label,
                normalized_angle,
                *image_dimensions(image_target),
            )
            prepared.append(
                {
                    "image_id": augmented_id,
                    "parent_image_id": image_id,
                    "augmentation": {
                        "kind": "centre_rotation",
                        "angle_degrees_counter_clockwise": normalized_angle,
                        "canvas_policy": "preserve_size_clip_outside",
                        "fill_rgb": [114, 114, 114],
                    },
                    "media_sha256": record["media_sha256"],
                    "prepared_media_sha256": augmented_media_sha256,
                    "label_sha256": sha256_file(augmented_label),
                    "box_count": augmented_box_count,
                    "split": split,
                }
            )

    yaml_path = _write_data_yaml(
        output_dir, class_names, holdout=bool(holdout_image_id)
    )
    manifest: dict[str, Any] = {
        "schema_version": "1.0",
        "kind": "yolo_local_smoke" if local_smoke else "yolo_training_dataset",
        "session_id": review["session_id"],
        "class_names": class_names,
        "items": prepared,
        "item_count": len(prepared),
        "box_count": sum(item["box_count"] for item in prepared),
        "holdout_image_id": holdout_image_id,
        "rotation_angles_by_source": (
            [float(angle) % 360.0 for angle in rotation_angles]
            if rotation_angles
            else []
        ),
        "rotation_grid": [float(angle) % 360.0 for angle in rotation_grid],
        "source_trainable": trainable,
        "release_eligible": trainable and not local_smoke,
        "evaluation_valid": False,
        "evaluation_limitation": (
            "train/validation/test point to one acquisition session for pipeline smoke testing; "
            "this run cannot measure generalization"
        ),
        "source_review_manifest": str(review_manifest_path.resolve()),
        "source_review_manifest_sha256": sha256_file(review_manifest_path),
        "source_intake_manifest": str(intake_manifest_path.resolve()),
        "source_intake_manifest_sha256": sha256_file(intake_manifest_path),
        "data_yaml": str(yaml_path.resolve()),
    }
    manifest["dataset_digest"] = canonical_digest(manifest)
    (output_dir / "dataset-manifest.json").write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    return manifest


def train_model(
    dataset_dir: Path,
    output_dir: Path,
    *,
    base_model: str,
    epochs: int,
    image_size: int,
    batch: int,
    device: str,
) -> dict[str, Any]:
    dataset_dir = dataset_dir.resolve()
    output_dir = output_dir.resolve()
    data_yaml = dataset_dir / "data.yaml"
    dataset_manifest = load_json(dataset_dir / "dataset-manifest.json")
    if dataset_manifest.get("release_eligible"):
        raise YoloExperimentError(
            "this command is reserved for explicitly non-release smoke runs"
        )
    try:
        import torch
        import ultralytics
        from ultralytics import YOLO
    except ImportError as exc:
        raise YoloExperimentError(
            "install the optional ultralytics training dependencies"
        ) from exc

    model_cache = output_dir / "model-cache"
    model_cache.mkdir(parents=True, exist_ok=True)
    previous_cwd = Path.cwd()
    os.chdir(model_cache)
    try:
        model = YOLO(base_model)
        base_path = Path(model.ckpt_path).resolve()
        results = model.train(
            data=str(data_yaml),
            epochs=epochs,
            imgsz=image_size,
            batch=batch,
            device=device,
            workers=0,
            seed=0,
            deterministic=True,
            project=str(output_dir / "runs"),
            name="train",
            exist_ok=True,
            plots=True,
            verbose=True,
        )
    finally:
        os.chdir(previous_cwd)
    checkpoint = Path(results.save_dir) / "weights" / "best.pt"
    if not checkpoint.is_file():
        raise YoloExperimentError("training completed without best.pt")
    metrics = {
        key: float(value)
        for key, value in (results.results_dict or {}).items()
        if isinstance(value, (int, float))
    }
    manifest = {
        "schema_version": "1.0",
        "kind": "yolo_local_smoke_model",
        "release_eligible": False,
        "evaluation_valid": False,
        "dataset_digest": dataset_manifest["dataset_digest"],
        "dataset_manifest_sha256": sha256_file(dataset_dir / "dataset-manifest.json"),
        "base_model": base_model,
        "base_model_path": str(base_path),
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
    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / "training-manifest.json").write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    return manifest


def run_inference(
    checkpoint: Path,
    image_path: Path,
    output_dir: Path,
    *,
    confidence: float,
    iou_threshold: float,
    max_detections: int,
    image_size: int,
    device: str,
) -> dict[str, Any]:
    try:
        import cv2
        from ultralytics import YOLO
    except ImportError as exc:
        raise YoloExperimentError(
            "install the optional ultralytics inference dependencies"
        ) from exc
    output_dir.mkdir(parents=True, exist_ok=True)
    model = YOLO(str(checkpoint.resolve()))
    result = model.predict(
        source=str(image_path.resolve()),
        conf=confidence,
        iou=iou_threshold,
        max_det=max_detections,
        imgsz=image_size,
        device=device,
        verbose=True,
    )[0]
    annotated = cv2.imread(str(image_path.resolve()))
    if annotated is None:
        raise YoloExperimentError(f"failed to read {image_path}")
    detections = []
    for box in result.boxes:
        class_id = int(box.cls.item())
        detections.append(
            {
                "class_id": class_id,
                "class_name": result.names[class_id],
                "confidence": float(box.conf.item()),
                "xyxy_pixels": [float(value) for value in box.xyxy[0].tolist()],
            }
        )
        x1, y1, x2, y2 = [round(value) for value in box.xyxy[0].tolist()]
        short_name = result.names[class_id]
        short_name = short_name.replace("NXP_FRDM-MCXN236_", "FRDM-MCXN236 ").replace(
            "_", " "
        )
        label = f"{short_name} {float(box.conf.item()):.2f}"
        color = (36, 214, 138)
        cv2.rectangle(annotated, (x1, y1), (x2, y2), color, 8)
        font_scale = max(0.8, min(1.8, annotated.shape[1] / 2200.0))
        thickness = max(2, round(font_scale * 2))
        (text_width, text_height), baseline = cv2.getTextSize(
            label, cv2.FONT_HERSHEY_SIMPLEX, font_scale, thickness
        )
        text_y = max(text_height + baseline + 8, y1)
        cv2.rectangle(
            annotated,
            (x1, text_y - text_height - baseline - 12),
            (min(annotated.shape[1] - 1, x1 + text_width + 16), text_y + 4),
            color,
            -1,
        )
        cv2.putText(
            annotated,
            label,
            (x1 + 8, text_y - baseline - 4),
            cv2.FONT_HERSHEY_SIMPLEX,
            font_scale,
            (13, 24, 30),
            thickness,
            cv2.LINE_AA,
        )
    annotated_path = output_dir / "prediction.jpg"
    if not cv2.imwrite(str(annotated_path), annotated):
        raise YoloExperimentError(f"failed to write {annotated_path}")
    manifest = {
        "schema_version": "1.0",
        "kind": "yolo_local_smoke_inference",
        "release_eligible": False,
        "evaluation_valid": False,
        "checkpoint": str(checkpoint.resolve()),
        "checkpoint_sha256": sha256_file(checkpoint),
        "image": str(image_path.resolve()),
        "image_sha256": sha256_file(image_path),
        "confidence_threshold": confidence,
        "iou_threshold": iou_threshold,
        "max_detections": max_detections,
        "image_size": image_size,
        "detections": detections,
        "annotated_image": str(annotated_path.resolve()),
        "annotated_image_sha256": sha256_file(annotated_path),
    }
    (output_dir / "prediction.json").write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    return manifest


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    prepare = subparsers.add_parser("prepare")
    prepare.add_argument("--review-manifest", required=True, type=Path)
    prepare.add_argument("--intake-manifest", required=True, type=Path)
    prepare.add_argument("--output-dir", required=True, type=Path)
    prepare.add_argument("--local-smoke", action="store_true")
    prepare.add_argument("--holdout-image-id")
    prepare.add_argument(
        "--rotation-angles",
        help="comma-separated, one deterministic CCW angle per reviewed image",
    )
    prepare.add_argument(
        "--rotation-grid",
        help="comma-separated angles applied to every reviewed image",
    )

    train = subparsers.add_parser("train")
    train.add_argument("--dataset-dir", required=True, type=Path)
    train.add_argument("--output-dir", required=True, type=Path)
    train.add_argument("--base-model", default="yolo11n.pt")
    train.add_argument("--epochs", type=int, default=50)
    train.add_argument("--image-size", type=int, default=640)
    train.add_argument("--batch", type=int, default=4)
    train.add_argument("--device", default="cpu")

    infer = subparsers.add_parser("infer")
    infer.add_argument("--checkpoint", required=True, type=Path)
    infer.add_argument("--image", required=True, type=Path)
    infer.add_argument("--output-dir", required=True, type=Path)
    infer.add_argument("--confidence", type=float, default=0.25)
    infer.add_argument("--iou-threshold", type=float, default=0.45)
    infer.add_argument("--max-detections", type=int, default=100)
    infer.add_argument("--image-size", type=int, default=640)
    infer.add_argument("--device", default="cpu")

    args = parser.parse_args(argv)
    if args.command == "prepare":
        result = prepare_dataset(
            args.review_manifest,
            args.intake_manifest,
            args.output_dir,
            local_smoke=args.local_smoke,
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
        result = train_model(
            args.dataset_dir,
            args.output_dir,
            base_model=args.base_model,
            epochs=args.epochs,
            image_size=args.image_size,
            batch=args.batch,
            device=args.device,
        )
    else:
        result = run_inference(
            args.checkpoint,
            args.image,
            args.output_dir,
            confidence=args.confidence,
            iou_threshold=args.iou_threshold,
            max_detections=args.max_detections,
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
