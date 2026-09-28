#!/usr/bin/env python3
"""Install the app-private runtime assets onto a connected device, verifying
every digest before and after the copy.

The Android runtime loads its board reference, digital-twin package and
vision bundle from app-private storage; model binaries and board assets are
deliberately never committed to Git or embedded by the build (see
docs/RELEASE.md), so they are provisioned by an operator. Doing that by hand
is where silent failures come from: a file copied to the wrong path, a
manifest bound to the wrong context revision, or a truncated push shows up
much later as an invisible 3D layer or a `DIGEST_MISMATCH` in logcat.

This tool pushes each asset, then reads back the on-device SHA-256 and
compares it with the local file, so a bad copy is caught here rather than on
the bench. It validates the packages with the same rules the runtime applies
before it pushes anything.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import sys
from pathlib import Path
from typing import Any

PACKAGE = "dev.tokito.physical"
# paths are relative to the app's filesDir, matching MainActivity/
# BoardReferenceContext/LocalPoseVisionRuntime
DEVICE_LAYOUT = {
    "board_reference": "contexts/active.board-reference.json",
    "twin_manifest": "contexts/active.digital-twin.json",
    "twin_glb": "contexts/active.digital-twin.glb",
    "vision_manifest": "models/vision/manifest.json",
    "vision_model": "models/vision/model.tflite",
}
STAGING = "/data/local/tmp/tokito-provision"


def run(adb: str, *args: str, check: bool = True) -> str:
    completed = subprocess.run([adb, *args], capture_output=True, text=True)
    if check and completed.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed: {completed.stderr.strip() or completed.stdout.strip()}")
    return completed.stdout.strip()


def local_digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def device_digest(adb: str, package: str, relative: str) -> str | None:
    """SHA-256 as computed on the device, so a truncated or mangled push is
    detected rather than assumed away."""
    output = run(adb, "shell", f"run-as {package} sha256sum files/{relative}", check=False)
    parts = output.split()
    return parts[0] if parts and len(parts[0]) == 64 else None


def context_revision(board_reference: Path) -> str:
    """The value the app derives from the active board reference; the
    digital-twin manifest must match it or the package is rejected."""
    document = json.loads(board_reference.read_text(encoding="utf-8"))
    return f"{document['manifest_id']}@{document['integrity']['manifest_digest']}"


def preflight(assets: dict[str, Path]) -> list[str]:
    """Runtime acceptance checks, run before anything touches the device."""
    from verify_digital_twin_acceptance import check_package
    from verify_vision_bundle import check_bundle

    problems: list[str] = []
    for name, path in assets.items():
        if not path.is_file():
            problems.append(f"{name}: {path} does not exist")
    if problems:
        return problems

    expected = context_revision(assets["board_reference"])
    for rejection in check_package(assets["twin_manifest"], assets["twin_glb"], expected):
        problems.append(f"digital twin: {rejection['code']} - {rejection['detail']}")

    if "vision_model" in assets:
        report = check_bundle(assets["vision_model"].parent)
        problems.extend(f"vision bundle: {problem}" for problem in report["problems"])
    return problems


def provision(adb: str, package: str, assets: dict[str, Path]) -> dict[str, Any]:
    run(adb, "shell", f"mkdir -p {STAGING}")
    run(adb, "shell", f"run-as {package} mkdir -p files/contexts files/models/vision")

    results = []
    for name, path in assets.items():
        relative = DEVICE_LAYOUT[name]
        staged = f"{STAGING}/{Path(relative).name}"
        run(adb, "push", str(path), staged)
        run(adb, "shell", f"run-as {package} cp {staged} files/{relative}")
        expected = local_digest(path)
        actual = device_digest(adb, package, relative)
        results.append(
            {
                "asset": name,
                "device_path": f"files/{relative}",
                "bytes": path.stat().st_size,
                "sha256": expected,
                "verified_on_device": actual == expected,
                "device_sha256": actual,
            }
        )
    run(adb, "shell", f"rm -rf {STAGING}", check=False)
    return {
        "package": package,
        "context_revision": context_revision(assets["board_reference"]),
        "assets": results,
        "all_verified": all(entry["verified_on_device"] for entry in results),
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Provision app-private runtime assets onto a connected device.")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--package", default=PACKAGE)
    parser.add_argument("--board-reference", required=True, type=Path)
    parser.add_argument("--twin-manifest", required=True, type=Path)
    parser.add_argument("--twin-glb", required=True, type=Path)
    parser.add_argument("--vision-manifest", type=Path, help="models/vision/manifest.json (operator-provided)")
    parser.add_argument("--vision-model", type=Path, help="models/vision/model.tflite (operator-provided)")
    parser.add_argument("--dry-run", action="store_true", help="run the acceptance checks without touching the device")
    args = parser.parse_args(argv)

    assets = {
        "board_reference": args.board_reference,
        "twin_manifest": args.twin_manifest,
        "twin_glb": args.twin_glb,
    }
    if args.vision_manifest and args.vision_model:
        assets["vision_manifest"] = args.vision_manifest
        assets["vision_model"] = args.vision_model
    elif args.vision_manifest or args.vision_model:
        print(json.dumps({"error": "the vision bundle needs both --vision-manifest and --vision-model"}, indent=2))
        return 2

    problems = preflight(assets)
    if problems:
        print(json.dumps({"provisioned": False, "preflight_problems": problems}, indent=2))
        return 1
    if args.dry_run:
        print(json.dumps({"provisioned": False, "dry_run": True, "preflight": "all checks passed"}, indent=2))
        return 0

    result = provision(args.adb, args.package, assets)
    print(json.dumps(result, indent=2))
    return 0 if result["all_verified"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
