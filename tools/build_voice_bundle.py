#!/usr/bin/env python3
"""Assemble an operator-provisioned Sherpa ONNX voice bundle for the app.

The Android voice runtime loads its models from app-private storage at
`voice-models/sherpa-onnx-<version>/`, described by two files it verifies
strictly: `voice-runtime.json` (engines, model file paths, TTS voice
settings -- see `SherpaBundleConfig.load`) and `voice-models.manifest.json`
(a full inventory whose per-file sizes/digests and canonical inventory
digest are checked by `ModelInventory.verify`). Model binaries never enter
Git (docs/RELEASE.md); this tool builds the bundle directory an operator
pushes to the device.

The digest scheme replicates ModelInventory exactly: every file except the
manifest itself is listed with its size and SHA-256, and the inventory
digest is the SHA-256 of the sorted `path:size:sha256` lines joined by
newlines. Getting this wrong fails closed on the device ("inventory digest
mismatch"), so the scheme is covered by unit tests here.

The models themselves come from public, freely licensed distributions
(k2-fsa's sherpa-onnx release archives); record their provenance in the
bundle notes rather than treating them as project secrets.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
from pathlib import Path
from typing import Any

RUNTIME_VERSION = "1.13.6"
MANIFEST_NAME = "voice-models.manifest.json"
CONFIG_NAME = "voice-runtime.json"


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(65536), b""):
            digest.update(block)
    return digest.hexdigest()


def inventory_digest(entries: list[dict[str, Any]]) -> str:
    """SHA-256 of the sorted `path:size:sha256` lines -- the exact canonical
    form ModelInventory.canonicalDigest computes on the device."""
    lines = sorted(f"{e['path']}:{e['size']}:{e['sha256']}" for e in entries)
    return hashlib.sha256("\n".join(lines).encode("utf-8")).hexdigest()


def write_manifest(bundle: Path) -> dict[str, Any]:
    """Records the inventory of every file in the bundle (config included,
    manifest excluded), matching ModelInventory.record()."""
    files = sorted(
        (p for p in bundle.rglob("*") if p.is_file() and p.name != MANIFEST_NAME),
        key=lambda p: p.relative_to(bundle).as_posix(),
    )
    if not files:
        raise ValueError("bundle contains no model files")
    entries = [
        {"path": p.relative_to(bundle).as_posix(), "size": p.stat().st_size, "sha256": sha256_file(p)}
        for p in files
    ]
    manifest = {
        "schema_version": 1,
        "runtime_version": RUNTIME_VERSION,
        "inventory_sha256": inventory_digest(entries),
        "files": entries,
    }
    (bundle / MANIFEST_NAME).write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    return manifest


def assemble(
    stt_dir: Path,
    tts_dir: Path,
    output: Path,
    stt_variant: str = "int8",
    speaker_id: int = 0,
    speed: float = 1.0,
) -> dict[str, Any]:
    """Builds the bundle from extracted k2-fsa archives: a streaming
    zipformer transducer directory and a vits-piper voice directory."""

    def pick(directory: Path, stem: str) -> Path:
        # prefer the quantized variant where one exists; the fp32 decoder is
        # kept regardless (int8 transducer decoders are not recommended)
        for name in (f"{stem}.{stt_variant}.onnx", f"{stem}.onnx"):
            matches = sorted(directory.glob(f"*{name}"))
            if matches:
                return matches[0]
        raise FileNotFoundError(f"no {stem} model found in {directory}")

    encoder = pick(stt_dir, "encoder-epoch-99-avg-1")
    decoder = next(iter(sorted(stt_dir.glob("decoder-*[!8].onnx"))), None) or pick(stt_dir, "decoder-epoch-99-avg-1")
    joiner = pick(stt_dir, "joiner-epoch-99-avg-1")
    stt_tokens = stt_dir / "tokens.txt"
    tts_model = next(iter(sorted(tts_dir.glob("*.onnx"))))
    tts_tokens = tts_dir / "tokens.txt"
    espeak = tts_dir / "espeak-ng-data"
    for required in (encoder, decoder, joiner, stt_tokens, tts_model, tts_tokens):
        if not required.is_file():
            raise FileNotFoundError(str(required))
    if not espeak.is_dir():
        raise FileNotFoundError(str(espeak))

    if output.exists():
        shutil.rmtree(output)
    (output / "stt").mkdir(parents=True)
    (output / "tts").mkdir(parents=True)
    shutil.copy(encoder, output / "stt/encoder.onnx")
    shutil.copy(decoder, output / "stt/decoder.onnx")
    shutil.copy(joiner, output / "stt/joiner.onnx")
    shutil.copy(stt_tokens, output / "stt/tokens.txt")
    shutil.copy(tts_model, output / "tts/model.onnx")
    shutil.copy(tts_tokens, output / "tts/tokens.txt")
    shutil.copytree(espeak, output / "tts/espeak-ng-data")

    config = {
        "schema_version": 1,
        "languages": ["en"],
        "stt": {
            "engine": "sherpa-onnx-online-transducer",
            "model_type": "zipformer",
            "encoder": "stt/encoder.onnx",
            "decoder": "stt/decoder.onnx",
            "joiner": "stt/joiner.onnx",
            "tokens": "stt/tokens.txt",
        },
        "tts": {
            "engine": "sherpa-onnx-vits",
            "model": "tts/model.onnx",
            "tokens": "tts/tokens.txt",
            "data_dir": "tts/espeak-ng-data",
            "speaker_id": speaker_id,
            "speed": speed,
        },
    }
    (output / CONFIG_NAME).write_text(json.dumps(config, indent=2), encoding="utf-8")
    manifest = write_manifest(output)
    total = sum(entry["size"] for entry in manifest["files"])
    return {
        "bundle": str(output),
        "files": len(manifest["files"]),
        "total_bytes": total,
        "inventory_sha256": manifest["inventory_sha256"],
        "device_path": f"files/voice-models/sherpa-onnx-{RUNTIME_VERSION}/",
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Assemble the app's operator-provisioned Sherpa voice bundle.")
    parser.add_argument("--stt-dir", required=True, type=Path, help="extracted streaming zipformer archive")
    parser.add_argument("--tts-dir", required=True, type=Path, help="extracted vits-piper voice archive")
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--speaker-id", type=int, default=0)
    parser.add_argument("--speed", type=float, default=1.0)
    args = parser.parse_args(argv)

    result = assemble(args.stt_dir, args.tts_dir, args.output, speaker_id=args.speaker_id, speed=args.speed)
    print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
