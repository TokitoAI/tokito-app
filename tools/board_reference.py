#!/usr/bin/env python3
"""Freeze and validate evidence-bound board landmark and pin-reference data."""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
from pathlib import Path
from typing import Any, Sequence

from validate_governance import ROOT, load_json, validate_value

SCHEMA_PATH = ROOT / "schemas" / "board.reference-manifest.schema.json"


class BoardReferenceError(RuntimeError):
    """The board reference violates a schema or semantic invariant."""


def fail(message: str) -> None:
    raise BoardReferenceError(message)


def canonical_bytes(document: dict[str, Any]) -> bytes:
    return json.dumps(
        document,
        ensure_ascii=False,
        allow_nan=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def manifest_digest(document: dict[str, Any]) -> str:
    candidate = copy.deepcopy(document)
    candidate["integrity"].pop("manifest_digest", None)
    return "sha256:" + hashlib.sha256(canonical_bytes(candidate)).hexdigest()


def _unique(items: list[dict[str, Any]], key: str, label: str) -> dict[str, dict[str, Any]]:
    indexed: dict[str, dict[str, Any]] = {}
    for item in items:
        identity = item[key]
        if identity in indexed:
            fail(f"duplicate {label}: {identity}")
        indexed[identity] = item
    return indexed


def validate_semantics(document: dict[str, Any], verify_digest: bool = True) -> None:
    validate_value("board-reference", document, load_json(SCHEMA_PATH))
    sources = _unique(document["sources"], "source_id", "source ID")
    _unique(document["landmarks"], "landmark_id", "landmark ID")
    _unique(document["connectors"], "connector_id", "connector ID")

    for source in sources.values():
        if source["license_status"] != "approved" and source["redistributable"]:
            fail(
                f"source {source['source_id']} cannot be redistributable while its license is "
                f"{source['license_status']}"
            )

    binding = document["placement_binding"]
    if binding["status"] == "authored":
        source = sources.get(binding.get("source_id"))
        if source is None or source["kind"] != "authored_design":
            fail("authored placement binding must cite an authored-design source")
        if not binding.get("coordinate_frame_id"):
            fail("authored placement binding requires a coordinate frame")
    elif "source_id" in binding or "coordinate_frame_id" in binding:
        fail("unbound guide evidence cannot claim a placement source or coordinate frame")

    for landmark in document["landmarks"]:
        for source_id in landmark["evidence_refs"]:
            if source_id not in sources:
                fail(f"landmark {landmark['landmark_id']} cites unknown source {source_id}")
        for target in landmark["guide_targets"]:
            source = sources.get(target["source_id"])
            if source is None or source["kind"] != "printed_guide_photo":
                fail(
                    f"guide target for {landmark['landmark_id']} must cite a printed-guide photo"
                )

    for connector in document["connectors"]:
        for source_id in connector["evidence_refs"]:
            if source_id not in sources:
                fail(f"connector {connector['connector_id']} cites unknown source {source_id}")
        pins = connector["pins"]
        numbers = [pin["number"] for pin in pins]
        expected = list(range(1, connector["pin_count"] + 1))
        if sorted(numbers) != expected:
            fail(
                f"connector {connector['connector_id']} pins must cover 1..{connector['pin_count']} exactly"
            )
        for pin in pins:
            for source_id in pin["evidence_refs"]:
                if source_id not in sources:
                    fail(
                        f"connector {connector['connector_id']} pin {pin['number']} cites unknown source {source_id}"
                    )
            role = pin["electrical_role"]
            signal = pin["signal_name"].upper()
            if role == "ground" and signal != "GND":
                fail(f"ground pin must use GND: {connector['connector_id']}:{pin['number']}")
            if role == "not_connected" and signal != "NC":
                fail(f"not-connected pin must use NC: {connector['connector_id']}:{pin['number']}")
            if role not in {"ground", "not_connected"} and signal in {"GND", "NC"}:
                fail(
                    f"reserved signal {signal} has conflicting role: {connector['connector_id']}:{pin['number']}"
                )

    if verify_digest and document["integrity"]["manifest_digest"] != manifest_digest(document):
        fail("manifest digest mismatch")


def freeze(document: dict[str, Any]) -> dict[str, Any]:
    candidate = copy.deepcopy(document)
    candidate.setdefault("integrity", {})["manifest_digest"] = manifest_digest(candidate)
    validate_semantics(candidate)
    return candidate


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    validate = subparsers.add_parser("validate")
    validate.add_argument("--manifest", required=True, type=Path)
    freeze_parser = subparsers.add_parser("freeze")
    freeze_parser.add_argument("--draft", required=True, type=Path)
    freeze_parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args(argv)

    document = load_json(args.manifest if args.command == "validate" else args.draft)
    if args.command == "validate":
        validate_semantics(document)
        print(json.dumps({"valid": True, "manifest_digest": manifest_digest(document)}))
    else:
        frozen = freeze(document)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(frozen, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        print(json.dumps({"output": str(args.output), "manifest_digest": frozen["integrity"]["manifest_digest"]}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
