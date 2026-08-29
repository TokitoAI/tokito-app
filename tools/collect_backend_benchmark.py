"""Collect one declared backend benchmark result without selecting a backend."""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
from datetime import UTC, datetime
from pathlib import Path
from typing import Any


REQUIRED_SECTIONS = {
    "correctness",
    "latency",
    "memory",
    "fallback",
    "lifecycle",
    "thermal",
}


class BenchmarkError(ValueError):
    """The candidate did not produce sufficient evidence for selection."""


def load_json(path: Path) -> dict[str, Any]:
    with path.open(encoding="utf-8") as source:
        value = json.load(source)
    if not isinstance(value, dict):
        raise BenchmarkError("configuration must be a JSON object")
    return value


def digest(value: bytes) -> str:
    return f"sha256:{hashlib.sha256(value).hexdigest()}"


def require_text(value: object, field: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise BenchmarkError(f"{field} must be a non-empty string")
    return value


def validate_result(result: object) -> dict[str, Any]:
    if not isinstance(result, dict) or set(result) != REQUIRED_SECTIONS:
        raise BenchmarkError("candidate result must contain exactly the required evidence sections")
    for field in ("correctness", "lifecycle", "thermal"):
        section = result[field]
        if not isinstance(section, dict) or section.get("passed") is not True:
            raise BenchmarkError(f"{field} evidence must explicitly pass")
    latency = result["latency"]
    if (
        not isinstance(latency, dict)
        or not isinstance(latency.get("cold_ms"), (int, float))
        or not isinstance(latency.get("warm_ms"), (int, float))
        or latency["cold_ms"] < 0
        or latency["warm_ms"] < 0
    ):
        raise BenchmarkError("latency evidence must contain non-negative cold_ms and warm_ms")
    memory = result["memory"]
    if not isinstance(memory, dict) or not isinstance(memory.get("peak_bytes"), int) or memory["peak_bytes"] < 0:
        raise BenchmarkError("memory evidence must contain non-negative peak_bytes")
    fallback = result["fallback"]
    if not isinstance(fallback, dict) or fallback.get("silent_cpu_fallback_detected") is not False:
        raise BenchmarkError("fallback evidence must explicitly reject silent CPU fallback")
    return result


def collect(config: dict[str, Any]) -> dict[str, Any]:
    schema_version = require_text(config.get("schema_version"), "schema_version")
    device_profile = require_text(config.get("device_profile"), "device_profile")
    abi = require_text(config.get("abi"), "abi")
    candidate_id = require_text(config.get("candidate_id"), "candidate_id")
    command = config.get("command")
    if not isinstance(command, list) or not command or not all(isinstance(part, str) and part for part in command):
        raise BenchmarkError("command must be a non-empty argument list")

    completed = subprocess.run(command, check=False, capture_output=True, text=False)
    if completed.returncode != 0:
        raise BenchmarkError(f"candidate command exited with {completed.returncode}")
    try:
        result = validate_result(json.loads(completed.stdout.decode("utf-8")))
    except UnicodeDecodeError as error:
        raise BenchmarkError("candidate output must be UTF-8 JSON") from error

    config_bytes = json.dumps(config, sort_keys=True, separators=(",", ":")).encode("utf-8")
    result_bytes = json.dumps(result, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return {
        "schema_version": schema_version,
        "captured_at_utc": datetime.now(UTC).isoformat(),
        "device_profile": device_profile,
        "abi": abi,
        "candidate_id": candidate_id,
        "config_digest": digest(config_bytes),
        "result_digest": digest(result_bytes),
        "result": result,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise SystemExit("refusing to overwrite an existing benchmark report")
    report = collect(load_json(args.config))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("x", encoding="utf-8", newline="\n") as destination:
        json.dump(report, destination, indent=2, sort_keys=True)
        destination.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
