#!/usr/bin/env python3
"""Bridge capture-intake records and reviewed Label Studio bounding boxes.

Raw images and generated annotations stay below the ignored dataset root.  This
module commits only the deterministic conversion and validation policy.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Sequence
from urllib.parse import quote

RECTANGLE_TYPE = "rectanglelabels"
PERCENT_EPSILON = 1e-9
SUPPORTED_LICENSE_STATES = {"approved", "pending", "rejected"}
SUPPORTED_MEDIA_DOMAINS = {
    "physical_capture",
    "printed_reference",
    "public_web_reference",
}
PRINTED_DEPICTION_LABEL = "NXP_FRDM-MCXN236_printed_board_depiction"


@dataclass(frozen=True)
class ReviewedBox:
    class_id: int
    class_name: str
    x_center: float
    y_center: float
    width: float
    height: float

    def yolo_line(self) -> str:
        return (
            f"{self.class_id} {self.x_center:.8f} {self.y_center:.8f} "
            f"{self.width:.8f} {self.height:.8f}"
        )


def load_json(path: Path) -> Any:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return "sha256:" + digest.hexdigest()


def _relative_image_path(image_path: Path, document_root: Path) -> str:
    resolved_root = document_root.resolve(strict=True)
    resolved_image = image_path.resolve(strict=True)
    if not resolved_image.is_relative_to(resolved_root):
        raise ValueError(f"image escapes Label Studio document root: {image_path}")
    return resolved_image.relative_to(resolved_root).as_posix()


def prepare_label_studio_tasks(
    intake_manifest: dict[str, Any],
    document_root: Path,
    license_status: str,
    source_uri: str,
    media_domain: str,
) -> list[dict[str, Any]]:
    if license_status not in SUPPORTED_LICENSE_STATES:
        raise ValueError(f"unsupported license status: {license_status}")
    if media_domain not in SUPPORTED_MEDIA_DOMAINS:
        raise ValueError(f"unsupported media domain: {media_domain}")
    class_names = intake_manifest.get("class_names", [])
    if not class_names:
        raise ValueError("intake manifest has no class names")
    if media_domain == "printed_reference" and class_names != [PRINTED_DEPICTION_LABEL]:
        raise ValueError(
            "printed-reference intake must expose only the printed-board-depiction label"
        )
    if media_domain == "physical_capture" and PRINTED_DEPICTION_LABEL in class_names:
        raise ValueError(
            "physical-capture intake cannot expose the printed-board-depiction label"
        )

    tasks: list[dict[str, Any]] = []
    for image in intake_manifest.get("images", []):
        local_path = Path(image["image_path"])
        if not local_path.is_file():
            raise FileNotFoundError(f"intake image is missing: {local_path}")
        if image.get("sha256") != sha256_file(local_path):
            raise ValueError(f"intake image digest mismatch: {local_path}")
        relative_path = _relative_image_path(local_path, document_root)
        tasks.append(
            {
                "data": {
                    "image": "/data/local-files/?d=" + quote(relative_path, safe="/"),
                    "image_id": image["image_id"],
                    "media_sha256": image["sha256"],
                    "board_name": intake_manifest["board_name"],
                    "session_id": intake_manifest["session_id"],
                    "license_status": license_status,
                    "source_uri": source_uri,
                    "media_domain": media_domain,
                    "allowed_labels": class_names,
                }
            }
        )
    return tasks


def _single_completed_annotation(task: dict[str, Any]) -> dict[str, Any]:
    completed = [
        annotation
        for annotation in task.get("annotations", [])
        if not annotation.get("was_cancelled", False)
    ]
    if len(completed) != 1:
        raise ValueError(
            f"task {task.get('id', '?')} must have exactly one non-cancelled annotation; "
            f"found {len(completed)}"
        )
    annotation = completed[0]
    if not annotation.get("completed_by"):
        raise ValueError(f"task {task.get('id', '?')} has no accountable reviewer")
    return annotation


def _finite_percent(value: Any, field: str) -> float:
    number = float(value)
    if not math.isfinite(number) or number < 0.0 or number > 100.0:
        raise ValueError(f"{field} must be a finite percentage in [0, 100]")
    return number


def reviewed_boxes(
    task: dict[str, Any], class_names: Sequence[str]
) -> tuple[list[ReviewedBox], Any]:
    annotation = _single_completed_annotation(task)
    boxes: list[ReviewedBox] = []
    for result in annotation.get("result", []):
        if result.get("type") != RECTANGLE_TYPE:
            continue
        value = result.get("value", {})
        labels = value.get("rectanglelabels", [])
        if len(labels) != 1 or labels[0] not in class_names:
            raise ValueError(
                f"task {task.get('id', '?')} has an unknown or ambiguous class label"
            )
        if float(value.get("rotation", 0.0)) != 0.0:
            raise ValueError(
                "rotated boxes are unsupported by the YOLO interchange contract"
            )
        x = _finite_percent(value.get("x"), "x")
        y = _finite_percent(value.get("y"), "y")
        width = _finite_percent(value.get("width"), "width")
        height = _finite_percent(value.get("height"), "height")
        if (
            width <= 0.0
            or height <= 0.0
            or x + width > 100.0 + PERCENT_EPSILON
            or y + height > 100.0 + PERCENT_EPSILON
        ):
            raise ValueError(
                "bounding box must have positive area and remain inside the image"
            )
        boxes.append(
            ReviewedBox(
                class_id=class_names.index(labels[0]),
                class_name=labels[0],
                x_center=(x + width / 2.0) / 100.0,
                y_center=(y + height / 2.0) / 100.0,
                width=width / 100.0,
                height=height / 100.0,
            )
        )
    return boxes, annotation["completed_by"]


def import_reviewed_export(
    tasks: Sequence[dict[str, Any]],
    output_dir: Path,
    expected_session_id: str,
    license_status: str,
    media_domain: str,
    allow_empty: bool = False,
) -> dict[str, Any]:
    if license_status not in SUPPORTED_LICENSE_STATES:
        raise ValueError(f"unsupported license status: {license_status}")
    if media_domain not in SUPPORTED_MEDIA_DOMAINS:
        raise ValueError(f"unsupported media domain: {media_domain}")
    labels_dir = output_dir / "labels" / "reviewed"
    if labels_dir.exists() and any(labels_dir.iterdir()):
        raise ValueError(
            f"refusing to overwrite non-empty reviewed-label directory: {labels_dir}"
        )

    validated: list[tuple[str, str | None, str | None, Any, list[ReviewedBox]]] = []
    class_names_seen: list[str] | None = None
    image_ids_seen: set[str] = set()
    for task in tasks:
        data = task.get("data", {})
        image_id = data.get("image_id")
        if not image_id or data.get("session_id") != expected_session_id:
            raise ValueError(
                "export task is missing its expected image/session identity"
            )
        if image_id in image_ids_seen:
            raise ValueError(f"duplicate exported image identity: {image_id}")
        image_ids_seen.add(image_id)
        if data.get("license_status") != license_status:
            raise ValueError(f"license state drift for {image_id}")
        if data.get("media_domain") != media_domain:
            raise ValueError(f"media domain drift for {image_id}")
        class_names = data.get("allowed_labels", [])
        if not class_names:
            raise ValueError(f"task {image_id} has no allowed labels")
        if class_names_seen is None:
            class_names_seen = list(class_names)
        elif class_names_seen != list(class_names):
            raise ValueError("class ordering differs between exported tasks")
        boxes, reviewer = reviewed_boxes(task, class_names)
        box_classes = {box.class_name for box in boxes}
        if media_domain == "printed_reference" and box_classes - {
            PRINTED_DEPICTION_LABEL
        }:
            raise ValueError(
                f"printed reference {image_id} contains a physical-object label"
            )
        if media_domain == "physical_capture" and PRINTED_DEPICTION_LABEL in box_classes:
            raise ValueError(
                f"physical capture {image_id} contains a printed-depiction label"
            )
        if not boxes and not allow_empty:
            raise ValueError(
                f"task {image_id} has no reviewed boxes; use --allow-empty only for true negatives"
            )
        validated.append(
            (
                image_id,
                data.get("media_sha256"),
                data.get("source_uri"),
                reviewer,
                boxes,
            )
        )

    # No output is created until every exported task has passed validation.
    labels_dir.mkdir(parents=True, exist_ok=True)
    records: list[dict[str, Any]] = []
    for image_id, media_sha256, source_uri, reviewer, boxes in validated:
        label_path = labels_dir / f"{image_id}.txt"
        label_path.write_text(
            "\n".join(box.yolo_line() for box in boxes) + ("\n" if boxes else ""),
            encoding="utf-8",
        )
        records.append(
            {
                "image_id": image_id,
                "media_sha256": media_sha256,
                "source_uri": source_uri,
                "media_domain": media_domain,
                "reviewer": reviewer,
                "box_count": len(boxes),
                "classes": sorted({box.class_name for box in boxes}),
                "label_path": str(label_path),
                "label_sha256": sha256_file(label_path),
            }
        )

    manifest = {
        "schema_version": "1.0",
        "session_id": expected_session_id,
        "license_status": license_status,
        "media_domain": media_domain,
        "trainable": license_status == "approved" and bool(records),
        "trainability_reason": (
            "reviewed annotations and approved source license"
            if license_status == "approved" and records
            else "source license is not approved for model training"
        ),
        "class_names": class_names_seen or [],
        "task_count": len(records),
        "box_count": sum(record["box_count"] for record in records),
        "reviewed_at": datetime.now(timezone.utc).isoformat(),
        "records": records,
    }
    write_json(output_dir / "review_manifest.json", manifest)
    return manifest


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Prepare and validate FRDM Label Studio annotation work."
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    prepare = subparsers.add_parser(
        "prepare", help="Create Label Studio import tasks from an intake manifest."
    )
    prepare.add_argument("--intake-manifest", required=True, type=Path)
    prepare.add_argument("--document-root", required=True, type=Path)
    prepare.add_argument(
        "--license-status", required=True, choices=sorted(SUPPORTED_LICENSE_STATES)
    )
    prepare.add_argument("--source-uri", required=True)
    prepare.add_argument(
        "--media-domain", required=True, choices=sorted(SUPPORTED_MEDIA_DOMAINS)
    )
    prepare.add_argument("--output", required=True, type=Path)

    convert = subparsers.add_parser(
        "import", help="Convert one reviewed Label Studio export to YOLO labels."
    )
    convert.add_argument("--export", required=True, type=Path)
    convert.add_argument("--output-dir", required=True, type=Path)
    convert.add_argument("--session-id", required=True)
    convert.add_argument(
        "--license-status", required=True, choices=sorted(SUPPORTED_LICENSE_STATES)
    )
    convert.add_argument(
        "--media-domain", required=True, choices=sorted(SUPPORTED_MEDIA_DOMAINS)
    )
    convert.add_argument("--allow-empty", action="store_true")

    args = parser.parse_args(argv)
    if args.command == "prepare":
        tasks = prepare_label_studio_tasks(
            load_json(args.intake_manifest),
            args.document_root,
            args.license_status,
            args.source_uri,
            args.media_domain,
        )
        write_json(args.output, tasks)
        print(
            json.dumps(
                {"task_count": len(tasks), "output": str(args.output)}, sort_keys=True
            )
        )
    else:
        manifest = import_reviewed_export(
            load_json(args.export),
            args.output_dir,
            args.session_id,
            args.license_status,
            args.media_domain,
            args.allow_empty,
        )
        print(json.dumps(manifest, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
