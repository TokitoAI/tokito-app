#!/usr/bin/env python3
"""Check a digital-twin package against the Android runtime's own acceptance
rules, before it ever reaches a phone.

This is a faithful port of `DigitalTwinValidator.load()`/`validate()` and
`isGlbV2()` from `android/ar-runtime/.../DigitalTwinAsset.kt`: same size
ceilings, same identity/digest shape checks, same geometry rules
(including the inclusive `contains` test between overall bounds and the
board plane), and the same strict GLB container walk. Each rejection here
carries the runtime's own code, so a failure names exactly how the device
would refuse the package.

Porting the rules is deliberate duplication: the Kotlin is the authority,
this exists so a packaging mistake is caught in seconds on the host
instead of as a silent "nothing renders" on the device.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import struct
from pathlib import Path
from typing import Any

MAXIMUM_GLB_BYTES = 64 * 1024 * 1024
MAXIMUM_MANIFEST_BYTES = 64 * 1024
SCHEMA_VERSION_PATTERN = re.compile(r"^1\.[0-9]+$")
DIGEST_PATTERN = re.compile(r"^(sha256:)?[a-fA-F0-9]{64}$")
CHUNK_JSON = 0x4E4F534A
GLB_MAGIC = 0x46546C67

IDENTITY_LIMITS = {
    "assetId": 256,
    "boardIdentity": 512,
    "boardRevision": 128,
    "assemblyContextRevision": 256,
    "componentNodePrefix": 32,
}


def _finite(vector: dict[str, Any]) -> bool:
    return all(isinstance(vector.get(axis), (int, float)) and float(vector[axis]) == float(vector[axis]) and abs(float(vector[axis])) != float("inf") for axis in "xyz")


def _bounds_valid(bounds: dict[str, Any]) -> bool:
    lo, hi = bounds.get("minimum", {}), bounds.get("maximum", {})
    return _finite(lo) and _finite(hi) and all(hi[axis] > lo[axis] for axis in "xyz")


def _contains(outer: dict[str, Any], inner: dict[str, Any]) -> bool:
    """Inclusive containment, matching the Kotlin `contains` (>= / <=)."""
    return all(inner["minimum"][axis] >= outer["minimum"][axis] for axis in "xyz") and all(
        inner["maximum"][axis] <= outer["maximum"][axis] for axis in "xyz"
    )


def is_glb_v2(data: bytes) -> bool:
    """Port of isGlbV2: header, exact declared length, one JSON chunk first,
    every chunk length positive and 4-byte aligned, ending exactly at EOF."""
    if len(data) < 20:
        return False
    magic, version, declared = struct.unpack_from("<III", data, 0)
    if magic != GLB_MAGIC or version != 2 or declared != len(data):
        return False
    offset = 12
    saw_json = False
    while offset < len(data):
        if offset + 8 > len(data):
            return False
        length, chunk_type = struct.unpack_from("<II", data, offset)
        if length <= 0 or length % 4 != 0 or offset + 8 + length > len(data):
            return False
        if offset == 12 and chunk_type != CHUNK_JSON:
            return False
        if chunk_type == CHUNK_JSON:
            if saw_json:
                return False
            saw_json = True
        offset += 8 + length
    return saw_json and offset == len(data)


def check_package(
    manifest_path: Path, glb_path: Path, expected_context_revision: str | None
) -> list[dict[str, str]]:
    """Returns a list of rejections; empty means the runtime would accept."""
    rejections: list[dict[str, str]] = []

    def reject(code: str, detail: str) -> None:
        rejections.append({"code": code, "detail": detail})

    if not manifest_path.is_file() or not glb_path.is_file():
        reject("IO_FAILURE", "asset package files are unavailable")
        return rejections
    if not 1 <= manifest_path.stat().st_size <= MAXIMUM_MANIFEST_BYTES:
        reject("MANIFEST_TOO_LARGE", f"manifest is {manifest_path.stat().st_size} bytes")
    if not 20 <= glb_path.stat().st_size <= MAXIMUM_GLB_BYTES:
        reject("ASSET_TOO_LARGE", f"GLB is {glb_path.stat().st_size} bytes")
    if rejections:
        return rejections

    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except Exception as error:
        reject("INVALID_MANIFEST", f"manifest cannot be decoded: {error}")
        return rejections
    glb = glb_path.read_bytes()

    if not SCHEMA_VERSION_PATTERN.match(str(manifest.get("schemaVersion", ""))):
        reject("INCOMPATIBLE_SCHEMA", f"schemaVersion={manifest.get('schemaVersion')!r}")

    for field, limit in IDENTITY_LIMITS.items():
        value = manifest.get(field)
        if not isinstance(value, str) or not value.strip():
            reject("INVALID_MANIFEST", f"{field} is blank or missing")
        elif len(value) > limit:
            reject("INVALID_MANIFEST", f"{field} exceeds {limit} characters")
    if not DIGEST_PATTERN.match(str(manifest.get("glbSha256", ""))):
        reject("INVALID_MANIFEST", "glbSha256 shape is invalid")

    if expected_context_revision is not None and manifest.get("assemblyContextRevision") != expected_context_revision:
        reject(
            "CONTEXT_MISMATCH",
            f"manifest {manifest.get('assemblyContextRevision')!r} != expected {expected_context_revision!r}",
        )

    if len(glb) != manifest.get("glbBytes") or len(glb) > MAXIMUM_GLB_BYTES:
        reject("ASSET_TOO_LARGE", f"GLB is {len(glb)} bytes, manifest declares {manifest.get('glbBytes')}")

    origin = manifest.get("boardOriginMetres", {})
    bounds = manifest.get("boundsMetres", {})
    plane = manifest.get("boardPlaneBoundsMetres")
    geometry_problems = []
    if not _finite(origin) or any(abs(float(origin.get(axis, 0))) > 10.0 for axis in "xyz"):
        geometry_problems.append("boardOriginMetres non-finite or beyond +-10 m")
    if not _bounds_valid(bounds):
        geometry_problems.append("boundsMetres is not strictly increasing on every axis")
    else:
        for key in ("minimum", "maximum"):
            if any(abs(float(bounds[key][axis])) > 10.0 for axis in "xyz"):
                geometry_problems.append(f"boundsMetres.{key} beyond +-10 m")
    if plane is not None:
        if not _bounds_valid(plane):
            geometry_problems.append("boardPlaneBoundsMetres is not strictly increasing on every axis")
        elif _bounds_valid(bounds) and not _contains(bounds, plane):
            geometry_problems.append("boundsMetres does not contain boardPlaneBoundsMetres")
    opacity = manifest.get("defaultOpacity")
    if not isinstance(opacity, (int, float)) or not 0.05 <= float(opacity) <= 0.85:
        geometry_problems.append(f"defaultOpacity={opacity} outside 0.05..0.85")
    for problem in geometry_problems:
        reject("INVALID_GEOMETRY", problem)

    if not is_glb_v2(glb):
        reject("INVALID_GLB", "asset is not a complete GLB 2 container")

    declared_digest = str(manifest.get("glbSha256", "")).removeprefix("sha256:").lower()
    actual_digest = hashlib.sha256(glb).hexdigest()
    if len(declared_digest) != 64 or declared_digest != actual_digest:
        reject("DIGEST_MISMATCH", f"actual sha256:{actual_digest}")

    return rejections


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Check a digital-twin package against the Android runtime's acceptance rules.")
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--glb", required=True, type=Path)
    parser.add_argument("--expected-context-revision", help="if given, also check the CONTEXT_MISMATCH rule")
    args = parser.parse_args(argv)

    rejections = check_package(args.manifest, args.glb, args.expected_context_revision)
    if rejections:
        print(json.dumps({"accepted": False, "rejections": rejections}, indent=2))
        return 1
    print(json.dumps({"accepted": True, "detail": "package satisfies every DigitalTwinValidator rule"}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
