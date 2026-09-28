#!/usr/bin/env python3
"""Check a local YOLO pose bundle against the Android runtime's own load
requirements, before it is pushed to a device.

Port of `LocalPoseVisionRuntime.load()` from
`app/src/main/java/dev/tokito/physical/LocalPoseVisionRuntime.kt`: the same
required files, size ceilings, manifest fields and numeric ranges, and the
same model digest check. The tensor-shape requirements are reported as
declared expectations rather than checked, because reading a `.tflite`
graph needs the TensorFlow runtime; everything else is verified from the
bytes on disk.

This exists so a bundle handed over by the vision lane can be validated in
seconds on the host, instead of surfacing as `local vision bundle is not
installed`/`digest mismatch` in logcat after a push-install cycle.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any

MAXIMUM_MANIFEST_BYTES = 64 * 1024
MAXIMUM_MODEL_BYTES = 64 * 1024 * 1024

# field -> (inclusive low, inclusive high); mirrors the Kotlin require() calls
NUMERIC_RANGES: dict[str, tuple[float, float]] = {
    "input_size": (320, 1280),
    "class_confidence": (0.0, 1.0),
    "keypoint_confidence": (0.0, 1.0),
    "minimum_board_area_ratio": (0.001, 0.8),
    "maximum_corner_jitter_ratio": (0.001, 0.2),
    "stable_frame_count": (2, 12),
    "maximum_observation_age_ms": (100, 5000),
}
REQUIRED_FIELDS = ("schema_version", "model_sha256", "coordinates_normalized", *NUMERIC_RANGES)

# the interpreter contract the runtime asserts after loading the graph
EXPECTED_INPUT_SHAPE = "[1, 3, input_size, input_size]"
EXPECTED_OUTPUT_SHAPE = "[1, 17, 8400]"


def check_bundle(bundle_directory: Path) -> dict[str, Any]:
    problems: list[str] = []
    manifest_path = bundle_directory / "manifest.json"
    model_path = bundle_directory / "model.tflite"

    if not manifest_path.is_file() or not model_path.is_file():
        return {
            "accepted": False,
            "problems": ["bundle must contain both manifest.json and model.tflite ('local vision bundle is not installed')"],
            "bundle": str(bundle_directory),
        }
    if not 1 <= manifest_path.stat().st_size <= MAXIMUM_MANIFEST_BYTES:
        problems.append(f"manifest.json is {manifest_path.stat().st_size} bytes, budget is 1..{MAXIMUM_MANIFEST_BYTES}")
    if not 1 <= model_path.stat().st_size <= MAXIMUM_MODEL_BYTES:
        problems.append(f"model.tflite is {model_path.stat().st_size} bytes, budget is 1..{MAXIMUM_MODEL_BYTES}")

    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except Exception as error:
        problems.append(f"manifest.json is not valid JSON: {error}")
        return {"accepted": False, "problems": problems, "bundle": str(bundle_directory)}

    for field in REQUIRED_FIELDS:
        if field not in manifest:
            problems.append(f"manifest is missing required field {field!r}")

    if manifest.get("schema_version") != "1.0":
        problems.append(f"schema_version must be \"1.0\", found {manifest.get('schema_version')!r}")

    declared = str(manifest.get("model_sha256", "")).removeprefix("sha256:").lower()
    if len(declared) != 64 or any(c not in "0123456789abcdef" for c in declared):
        problems.append("model_sha256 must be 64 lowercase hex characters (an optional 'sha256:' prefix is allowed)")
    else:
        actual = hashlib.sha256(model_path.read_bytes()).hexdigest()
        if actual != declared:
            problems.append(f"model digest mismatch: manifest says {declared}, model is {actual}")

    for field, (low, high) in NUMERIC_RANGES.items():
        if field not in manifest:
            continue
        value = manifest[field]
        if not isinstance(value, (int, float)) or isinstance(value, bool):
            problems.append(f"{field} must be numeric, found {value!r}")
        elif not low <= value <= high:
            problems.append(f"{field}={value} is outside the accepted range {low}..{high}")

    if "coordinates_normalized" in manifest and not isinstance(manifest["coordinates_normalized"], bool):
        problems.append("coordinates_normalized must be a boolean")

    return {
        "accepted": not problems,
        "problems": problems,
        "bundle": str(bundle_directory),
        "model_bytes": model_path.stat().st_size,
        "declared_input_size": manifest.get("input_size"),
        "runtime_tensor_contract": {
            "input": EXPECTED_INPUT_SHAPE,
            "output": EXPECTED_OUTPUT_SHAPE,
            "note": "checked by the runtime after loading the graph; not verified here (needs a TFLite interpreter)",
        },
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Check a YOLO pose bundle against the Android runtime's load requirements.")
    parser.add_argument("--bundle-dir", required=True, type=Path, help="directory containing manifest.json and model.tflite")
    args = parser.parse_args(argv)

    report = check_bundle(args.bundle_dir)
    print(json.dumps(report, indent=2))
    return 0 if report["accepted"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
