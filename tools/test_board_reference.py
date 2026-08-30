#!/usr/bin/env python3
"""Tests for board-reference provenance and electrical semantics."""

from __future__ import annotations

import copy
import unittest

from board_reference import ROOT, BoardReferenceError, freeze, validate_semantics
from validate_governance import load_json


def draft() -> dict:
    source = {
        "source_id": "guide-1",
        "kind": "printed_guide_photo",
        "media_sha256": "sha256:" + "1" * 64,
        "locator": "user-supplied guide image",
        "license_status": "pending",
        "redistributable": False,
        "review": {
            "state": "reviewed",
            "reviewer": "reviewer@example.invalid",
            "reviewed_at": "2026-08-30T00:00:00+05:30",
        },
    }
    return {
        "schema_version": "1.0",
        "manifest_id": "reference-board",
        "board": {"manufacturer": "Vendor", "model": "Reference Board"},
        "placement_binding": {
            "status": "unbound",
            "reason": "printed evidence is not authored placement truth",
        },
        "sources": [
            source,
            {
                **source,
                "source_id": "datasheet-1",
                "kind": "datasheet",
                "locator": "official reference-board datasheet",
                "license_status": "approved",
            },
        ],
        "landmarks": [
            {
                "landmark_id": "power-header",
                "display_name": "Power header",
                "category": "header",
                "identifiers": {"reference_designator": "J1"},
                "guide_targets": [
                    {"source_id": "guide-1", "x_percent": 50, "y_percent": 50}
                ],
                "evidence_refs": ["guide-1"],
            }
        ],
        "connectors": [
            {
                "connector_id": "J1",
                "display_name": "Power header",
                "pin_count": 2,
                "pins": [
                    {
                        "number": 1,
                        "signal_name": "P3V3",
                        "aliases": [],
                        "functions": ["power"],
                        "electrical_role": "power_rail",
                        "evidence_refs": ["guide-1"],
                    },
                    {
                        "number": 2,
                        "signal_name": "GND",
                        "aliases": [],
                        "functions": ["power"],
                        "electrical_role": "ground",
                        "evidence_refs": ["guide-1"],
                    },
                ],
                "evidence_refs": ["guide-1"],
            }
        ],
        "knowledge_graph": {
            "entities": [
                {
                    "entity_id": "reference-board",
                    "display_name": "Reference Board",
                    "entity_type": "board",
                    "aliases": ["test board"],
                    "identifiers": [],
                }
            ],
            "facts": [
                {
                    "fact_id": "board-voltage",
                    "subject_id": "reference-board",
                    "predicate": "uses_voltage",
                    "value": "The board uses a 3.3 V logic rail.",
                    "spoken_summary": "The board uses 3.3 volt logic.",
                    "query_aliases": ["logic voltage"],
                    "related_entity_ids": [],
                    "evidence": [
                        {"source_id": "datasheet-1", "page": 1, "locator": "power table"}
                    ],
                }
            ],
        },
        "integrity": {"manifest_digest": "sha256:" + "0" * 64},
    }


class BoardReferenceTest(unittest.TestCase):
    def test_checked_in_frdm_reference_is_valid(self) -> None:
        validate_semantics(
            load_json(
                ROOT
                / "reference"
                / "boards"
                / "frdm-mcxn236.board-reference.json"
            )
        )

    def test_freeze_and_validate(self) -> None:
        frozen = freeze(draft())
        validate_semantics(frozen)
        self.assertNotEqual(
            frozen["integrity"]["manifest_digest"], "sha256:" + "0" * 64
        )

    def test_rejects_guide_as_authored_placement(self) -> None:
        document = draft()
        document["placement_binding"] = {
            "status": "authored",
            "reason": "invalid",
            "source_id": "guide-1",
            "coordinate_frame_id": "board-mm",
        }
        with self.assertRaisesRegex(BoardReferenceError, "authored-design"):
            validate_semantics(freeze(document))

    def test_rejects_missing_or_duplicate_pin_numbers(self) -> None:
        document = draft()
        document["connectors"][0]["pins"][1]["number"] = 1
        with self.assertRaisesRegex(BoardReferenceError, "cover 1..2"):
            validate_semantics(freeze(document))

    def test_rejects_conflicting_ground_role(self) -> None:
        document = draft()
        document["connectors"][0]["pins"][1]["signal_name"] = "P3V3"
        with self.assertRaisesRegex(BoardReferenceError, "ground pin"):
            validate_semantics(freeze(document))

    def test_rejects_digest_drift(self) -> None:
        frozen = freeze(draft())
        changed = copy.deepcopy(frozen)
        changed["board"]["model"] = "Changed"
        with self.assertRaisesRegex(BoardReferenceError, "digest mismatch"):
            validate_semantics(changed)

    def test_rejects_unapproved_redistribution(self) -> None:
        document = draft()
        document["sources"][0]["redistributable"] = True
        with self.assertRaisesRegex(BoardReferenceError, "cannot be redistributable"):
            freeze(document)

    def test_rejects_unknown_knowledge_entities_and_sources(self) -> None:
        document = draft()
        document["knowledge_graph"]["facts"][0]["subject_id"] = "missing-board"
        with self.assertRaisesRegex(BoardReferenceError, "unknown subject"):
            freeze(document)

        document = draft()
        document["knowledge_graph"]["facts"][0]["evidence"][0]["source_id"] = "missing-source"
        with self.assertRaisesRegex(BoardReferenceError, "unknown source"):
            freeze(document)

    def test_rejects_nontechnical_knowledge_evidence(self) -> None:
        document = draft()
        document["knowledge_graph"]["facts"][0]["evidence"][0]["source_id"] = "guide-1"
        with self.assertRaisesRegex(BoardReferenceError, "nontechnical source"):
            freeze(document)


if __name__ == "__main__":
    unittest.main()
