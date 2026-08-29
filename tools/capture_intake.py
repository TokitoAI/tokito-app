#!/usr/bin/env python3
"""Prepare local capture drops for review and YOLO-style annotation.

This tool writes local datasets under ignored paths. It does not create
component labels automatically because guessed boxes are bad training data.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
from dataclasses import dataclass
from datetime import date
from pathlib import Path
from typing import Any, Sequence


IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png"}
CAD_EXTENSIONS = {".pdf", ".zip", ".brd", ".dsn", ".opj", ".bom", ".xlsx", ".dat"}
GERBER_EXTENSIONS = {".gbr", ".ger", ".gtl", ".gbl", ".gts", ".gbs", ".gto", ".gbo", ".drl", ".xln"}
MODEL_EXTENSIONS = {".step", ".stp", ".wrl"}


@dataclass(frozen=True)
class ImageRecord:
    image_id: str
    original_name: str
    image_path: str
    label_path: str
    sha256: str
    byte_count: int
    width: int | None
    height: int | None
    annotation_status: str

    def to_dict(self) -> dict[str, Any]:
        return {
            "image_id": self.image_id,
            "original_name": self.original_name,
            "image_path": self.image_path,
            "label_path": self.label_path,
            "sha256": self.sha256,
            "byte_count": self.byte_count,
            "width": self.width,
            "height": self.height,
            "annotation_status": self.annotation_status,
        }


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return "sha256:" + digest.hexdigest()


def image_dimensions(path: Path) -> tuple[int | None, int | None]:
    try:
        from PIL import Image

        with Image.open(path) as image:
            return int(image.width), int(image.height)
    except Exception:
        return None, None


def stable_slug(value: str) -> str:
    normalized = []
    previous_separator = False
    for character in value.lower():
        if character.isalnum():
            normalized.append(character)
            previous_separator = False
        elif not previous_separator:
            normalized.append("-")
            previous_separator = True
    return "".join(normalized).strip("-")


def prepare_dataset(
    source: Path,
    output_root: Path,
    board_name: str,
    session_id: str,
    device_profile: str,
    captured_at: str,
    class_names: Sequence[str],
) -> dict[str, Any]:
    if not source.exists():
        raise FileNotFoundError(f"source does not exist: {source}")
    if not class_names:
        raise ValueError("at least one class name is required")

    board_slug = stable_slug(board_name)
    session_root = output_root / board_slug / "sessions" / session_id
    images_dir = session_root / "images" / "unreviewed"
    labels_dir = session_root / "labels" / "unreviewed"
    artifacts_dir = session_root / "source_artifacts"
    for directory in (images_dir, labels_dir, artifacts_dir):
        directory.mkdir(parents=True, exist_ok=True)

    image_records = copy_images(source, images_dir, labels_dir, board_slug, session_id)
    cad_inventory = copy_cad_artifacts(source, artifacts_dir)
    write_dataset_yaml(session_root / "data.yaml", class_names)
    write_annotation_tasks(session_root / "annotation_tasks.jsonl", image_records, class_names)
    write_label_readme(labels_dir / "README.md", class_names)

    manifest = {
        "schema_version": "1.0",
        "board_name": board_name,
        "session_id": session_id,
        "device_profile": device_profile,
        "captured_at": captured_at,
        "source_root": str(source),
        "dataset_root": str(session_root),
        "annotation_policy": {
            "component_boxes_are_manual_review_required": True,
            "empty_label_files_are_not_negative_examples": True,
            "internet_images_are_not_physical_captures": True,
        },
        "class_names": list(class_names),
        "images": [record.to_dict() for record in image_records],
        "cad_inventory": cad_inventory,
    }
    (session_root / "intake_manifest.json").write_text(
        json.dumps(manifest, indent=2, sort_keys=True),
        encoding="utf-8",
    )
    return manifest


def copy_images(source: Path, images_dir: Path, labels_dir: Path, board_slug: str, session_id: str) -> list[ImageRecord]:
    records: list[ImageRecord] = []
    images = sorted(path for path in source.rglob("*") if path.is_file() and path.suffix.lower() in IMAGE_EXTENSIONS)
    seen_digests: set[str] = set()
    for index, image_path in enumerate(images, start=1):
        digest = sha256_file(image_path)
        if digest in seen_digests:
            continue
        seen_digests.add(digest)
        image_id = f"{board_slug}_{session_id}_img_{index:04d}"
        extension = image_path.suffix.lower()
        destination = images_dir / f"{image_id}{extension}"
        label_path = labels_dir / f"{image_id}.txt"
        shutil.copy2(image_path, destination)
        label_path.write_text("", encoding="utf-8")
        width, height = image_dimensions(destination)
        records.append(
            ImageRecord(
                image_id=image_id,
                original_name=image_path.name,
                image_path=str(destination),
                label_path=str(label_path),
                sha256=digest,
                byte_count=destination.stat().st_size,
                width=width,
                height=height,
                annotation_status="needs_human_bbox_review",
            )
        )
    return records


def copy_cad_artifacts(source: Path, artifacts_dir: Path) -> dict[str, Any]:
    allowed_extensions = CAD_EXTENSIONS | GERBER_EXTENSIONS | MODEL_EXTENSIONS
    files = sorted(path for path in source.rglob("*") if path.is_file() and path.suffix.lower() in allowed_extensions)
    records: list[dict[str, Any]] = []
    has_gerber = False
    has_drill = False
    has_model = False
    for path in files:
        relative = path.relative_to(source)
        destination = artifacts_dir / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, destination)
        suffix = path.suffix.lower()
        has_gerber = has_gerber or suffix in GERBER_EXTENSIONS - {".drl", ".xln"}
        has_drill = has_drill or suffix in {".drl", ".xln"}
        has_model = has_model or suffix in MODEL_EXTENSIONS
        records.append(
            {
                "original_relative_path": str(relative),
                "local_path": str(destination),
                "extension": suffix,
                "sha256": sha256_file(destination),
                "byte_count": destination.stat().st_size,
            }
        )

    if has_model:
        ar_status = "ready_for_ar_asset_review"
    elif has_gerber and has_drill:
        ar_status = "ready_for_board_mesh_conversion_tool"
    else:
        ar_status = "blocked_missing_gerber_drill_or_step_exports"

    return {
        "artifact_count": len(records),
        "artifacts": records,
        "has_gerber": has_gerber,
        "has_drill": has_drill,
        "has_step_or_wrl": has_model,
        "ar_3d_status": ar_status,
    }


def write_dataset_yaml(path: Path, class_names: Sequence[str]) -> None:
    lines = [
        "path: .",
        "train: images/train",
        "val: images/val",
        "test: images/test",
        "names:",
    ]
    for index, name in enumerate(class_names):
        lines.append(f"  {index}: {name}")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def write_annotation_tasks(path: Path, records: Sequence[ImageRecord], class_names: Sequence[str]) -> None:
    with path.open("w", encoding="utf-8") as handle:
        for record in records:
            task = {
                "image_id": record.image_id,
                "image_path": record.image_path,
                "label_path": record.label_path,
                "allowed_labels": list(class_names),
                "status": "needs_human_bbox_review",
                "instructions": "Draw reviewed YOLO boxes only for visible, identifiable objects.",
            }
            handle.write(json.dumps(task, sort_keys=True) + "\n")


def write_label_readme(path: Path, class_names: Sequence[str]) -> None:
    text = [
        "# Labels",
        "",
        "Empty `.txt` files in this folder are annotation tasks, not confirmed negative examples.",
        "Fill them only after visual review in Roboflow, CVAT, Label Studio, or another annotation tool.",
        "",
        "Class names:",
    ]
    for index, name in enumerate(class_names):
        text.append(f"- {index}: `{name}`")
    path.write_text("\n".join(text) + "\n", encoding="utf-8")


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Prepare local capture drops for annotation review.")
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--output-root", default=Path("datasets/local"), type=Path)
    parser.add_argument("--board-name", required=True)
    parser.add_argument("--session-id", required=True)
    parser.add_argument("--device-profile", required=True)
    parser.add_argument("--captured-at", default=date.today().isoformat())
    parser.add_argument("--class-name", action="append", dest="class_names", required=True)
    args = parser.parse_args(argv)

    manifest = prepare_dataset(
        source=args.source,
        output_root=args.output_root,
        board_name=args.board_name,
        session_id=args.session_id,
        device_profile=args.device_profile,
        captured_at=args.captured_at,
        class_names=args.class_names,
    )
    print(json.dumps(manifest, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
