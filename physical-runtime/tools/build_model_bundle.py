#!/usr/bin/env python3
"""Build a Tokito offline model bundle and sign its exact manifest bytes."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import subprocess
import tempfile
import zipfile

MANIFEST = "model.manifest.json"
SIGNATURE = "model.manifest.sig"
ALLOWED_LICENSES = {
    "apache-2.0", "mit", "bsd-3-clause", "agpl-3.0-only",
    "licenseref-kroko-piper-espeak-local-evaluation",
}
ZIP_TIMESTAMP = (1980, 1, 1, 0, 0, 0)


def artifact_inventory(source: Path) -> list[dict[str, object]]:
    artifacts: list[dict[str, object]] = []
    for path in sorted(source.rglob("*")):
        if path.is_symlink():
            raise ValueError(f"symbolic links are not admitted: {path}")
        if not path.is_file():
            continue
        relative = PurePosixPath(path.relative_to(source).as_posix())
        if relative.as_posix() in {MANIFEST, SIGNATURE}:
            raise ValueError(f"reserved model bundle path: {relative}")
        digest = hashlib.sha256()
        size = 0
        with path.open("rb") as payload:
            while chunk := payload.read(1024 * 1024):
                digest.update(chunk)
                size += len(chunk)
        if size <= 0:
            raise ValueError(f"empty artifacts are not admitted: {relative}")
        artifacts.append({"path": relative.as_posix(), "size_bytes": size, "sha256": digest.hexdigest()})
    if not artifacts:
        raise ValueError("model source contains no artifacts")
    return artifacts


def manifest_bytes(args: argparse.Namespace, artifacts: list[dict[str, object]]) -> bytes:
    license_id = args.license.lower()
    if license_id not in ALLOWED_LICENSES:
        raise ValueError(f"license is not admitted: {license_id}")
    document = {
        "schema_version": 1,
        "model_id": args.model_id,
        "model_revision": args.model_revision,
        "capability": args.capability,
        "runtime_revision": args.runtime_revision,
        "supported_abis": sorted(set(args.abi)),
        "license": license_id,
        "artifacts": artifacts,
    }
    if not document["model_id"] or not document["model_revision"] or not document["runtime_revision"]:
        raise ValueError("model id, model revision, and runtime revision must be non-empty")
    if not document["supported_abis"]:
        raise ValueError("at least one ABI is required")
    return (json.dumps(document, sort_keys=True, separators=(",", ":")) + "\n").encode()


def add_bytes(archive: zipfile.ZipFile, name: str, payload: bytes) -> None:
    info = zipfile.ZipInfo(name, ZIP_TIMESTAMP)
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o100644 << 16
    archive.writestr(info, payload)


def build(args: argparse.Namespace) -> None:
    source = args.source.resolve(strict=True)
    key = args.signing_key.resolve(strict=True)
    output = args.output.resolve()
    if not source.is_dir() or not key.is_file():
        raise ValueError("source must be a directory and signing key must be a file")
    artifacts = artifact_inventory(source)
    manifest = manifest_bytes(args, artifacts)
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="tokito-model-bundle-") as temporary:
        manifest_path = Path(temporary, MANIFEST)
        signature_path = Path(temporary, SIGNATURE)
        manifest_path.write_bytes(manifest)
        subprocess.run(["openssl", "dgst", "-sha256", "-sign", os.fspath(key), "-out", os.fspath(signature_path), os.fspath(manifest_path)], check=True)
        with zipfile.ZipFile(output, "w", allowZip64=True) as archive:
            add_bytes(archive, MANIFEST, manifest)
            add_bytes(archive, SIGNATURE, signature_path.read_bytes())
            for artifact in artifacts:
                relative = str(artifact["path"])
                add_bytes(archive, relative, source.joinpath(*PurePosixPath(relative).parts).read_bytes())
    print(f"built signed {args.capability} bundle: {output}")


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("source", type=Path)
    result.add_argument("output", type=Path)
    result.add_argument("--signing-key", type=Path, required=True)
    result.add_argument("--capability", choices=("dialogue", "vision", "voice"), required=True)
    result.add_argument("--model-id", required=True)
    result.add_argument("--model-revision", required=True)
    result.add_argument("--runtime-revision", default="1")
    result.add_argument("--abi", action="append", default=[])
    result.add_argument("--license", default="apache-2.0")
    return result


if __name__ == "__main__":
    arguments = parser().parse_args()
    if not arguments.abi:
        arguments.abi = ["arm64-v8a"]
    build(arguments)
