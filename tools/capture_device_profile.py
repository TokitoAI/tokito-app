#!/usr/bin/env python3
"""Capture privacy-safe Android device evidence using a declarative probe set."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


UNKNOWN = "unknown"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", type=Path, required=True, help="Explicit path to the Android Debug Bridge executable")
    parser.add_argument("--serial", required=True, help="ADB device selector; never written to output")
    parser.add_argument("--probe-config", type=Path, required=True, help="Versioned declarative probe configuration")
    parser.add_argument("--output-dir", type=Path, required=True, help="New or empty directory for captured evidence")
    return parser.parse_args()


def load_config(path: Path) -> dict[str, Any]:
    with path.open(encoding="utf-8") as source:
        config = json.load(source)
    required = ("schema_version", "output_schema_version", "privacy", "unknown_capabilities", "probes", "fields")
    missing = [name for name in required if name not in config]
    if missing:
        raise ValueError(f"probe configuration is missing: {', '.join(missing)}")
    if not isinstance(config["probes"], list) or not isinstance(config["fields"], list):
        raise ValueError("probe configuration requires probes and fields arrays")
    if not isinstance(config["privacy"].get("redacted_property_names"), list):
        raise ValueError("probe configuration requires privacy.redacted_property_names")
    if not isinstance(config["privacy"].get("redacted_text_patterns"), list):
        raise ValueError("probe configuration requires privacy.redacted_text_patterns")
    return config


def require_empty_output_dir(path: Path) -> None:
    if path.exists() and any(path.iterdir()):
        raise ValueError("output directory must be new or empty")
    path.mkdir(parents=True, exist_ok=True)


def run_probe(adb: Path, serial: str, argv: list[str]) -> str:
    result = subprocess.run(
        [str(adb), "-s", serial, *argv],
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if result.returncode:
        raise RuntimeError(f"probe failed with exit code {result.returncode}: {' '.join(argv)}")
    return result.stdout


def parse_android_properties(raw: str, redacted_property_names: set[str]) -> dict[str, str]:
    properties: dict[str, str] = {}
    for line in raw.splitlines():
        match = re.fullmatch(r"\[([^]]+)]\:\s*\[(.*)]", line)
        if match and match.group(1) not in redacted_property_names:
            properties[match.group(1)] = match.group(2)
    return properties


def redact_text(raw: str, redacted_text_patterns: list[str]) -> str:
    redacted = raw
    for pattern in redacted_text_patterns:
        redacted = re.sub(pattern, "[redacted sensitive device identifier]\n", redacted)
    return redacted


def extract_field(field: dict[str, Any], probes: dict[str, dict[str, Any]]) -> Any:
    probe = probes.get(field["probe"])
    if probe is None:
        raise ValueError(f"field references missing probe {field['probe']!r}")
    if "property" in field:
        return probe["properties"].get(field["property"], UNKNOWN)
    if "regex" in field:
        matches = re.findall(field["regex"], probe["raw"], re.MULTILINE)
        if field.get("all_matches", False):
            if not matches:
                return [UNKNOWN]
            return list(dict.fromkeys(matches)) if field.get("unique", False) else matches
        return matches[0] if matches else UNKNOWN
    raise ValueError(f"field {field['path']!r} has no extractor")


def assign_path(target: dict[str, Any], path: str, value: Any) -> None:
    segments = path.split(".")
    cursor = target
    for segment in segments[:-1]:
        cursor = cursor.setdefault(segment, {})
    cursor[segments[-1]] = value


def capture(config: dict[str, Any], adb: Path, serial: str) -> tuple[dict[str, Any], dict[str, str]]:
    captured: dict[str, dict[str, Any]] = {}
    raw_outputs: dict[str, str] = {}
    unavailable_probes: list[str] = []
    for probe in config["probes"]:
        probe_id = probe["id"]
        try:
            raw = redact_text(
                run_probe(adb, serial, probe["argv"]),
                config["privacy"]["redacted_text_patterns"],
            )
        except RuntimeError:
            if probe.get("required", True):
                raise
            unavailable_probes.append(probe_id)
            captured[probe_id] = {"raw": "", "properties": {}}
            continue
        raw_outputs[probe_id] = raw
        captured[probe_id] = {
            "raw": raw,
            "properties": parse_android_properties(raw, set(config["privacy"]["redacted_property_names"]))
            if probe["format"] == "android_properties"
            else {},
        }

    profile: dict[str, Any] = {
        "schema_version": config["output_schema_version"],
        "captured_at_utc": datetime.now(timezone.utc).isoformat(),
        "fields": {},
        "probe_evidence": {},
        "unknown_capabilities": config["unknown_capabilities"],
        "unavailable_probes": unavailable_probes,
    }
    for field in config["fields"]:
        assign_path(profile["fields"], field["path"], extract_field(field, captured))
    for probe_id, raw in raw_outputs.items():
        profile["probe_evidence"][probe_id] = {
            "sha256": hashlib.sha256(raw.encode("utf-8")).hexdigest(),
            "path": f"raw/{probe_id}.txt",
        }
    return profile, raw_outputs


def main() -> int:
    args = parse_args()
    config = load_config(args.probe_config)
    require_empty_output_dir(args.output_dir)
    profile, raw_outputs = capture(config, args.adb, args.serial)
    raw_dir = args.output_dir / "raw"
    raw_dir.mkdir()
    for probe_id, raw in raw_outputs.items():
        (raw_dir / f"{probe_id}.txt").write_text(raw, encoding="utf-8")
    (args.output_dir / "device-profile.v1.json").write_text(
        json.dumps(profile, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, RuntimeError, ValueError) as error:
        print(f"device profile capture failed: {error}", file=sys.stderr)
        raise SystemExit(1)
