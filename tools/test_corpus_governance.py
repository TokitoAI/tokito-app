#!/usr/bin/env python3
"""Regression tests for immutable corpus governance and leakage controls."""

from __future__ import annotations

import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from corpus_governance import (
    ROOT,
    ZERO_DIGEST,
    CorpusError,
    apply_change_set,
    audit_leakage,
    declared_digest,
    freeze_bundle,
    main,
    manifest_digest,
    validate_bundle,
    verify_assets,
    verify_evaluation_binding,
)

FROZEN_AT = "2026-08-29T00:00:00Z"


def item(
    item_id: str,
    board_id: str,
    session_id: str,
    digest_character: str,
    fingerprint: str,
    *,
    leakage_group_id: str | None = None,
    parents: list[str] | None = None,
) -> dict:
    return {
        "item_id": item_id,
        "board_id": board_id,
        "board_revision": "rev-a",
        "session_id": session_id,
        "leakage_group_id": leakage_group_id or board_id,
        "source_id": "source-1",
        "media": {
            "uri": f"media/{item_id}.png",
            "sha256": "sha256:" + digest_character * 64,
            "size_bytes": 16,
            "mime_type": "image/png",
        },
        "origin_class": "synthetic",
        "capture": {
            "device_id": "renderer-v1",
            "lens": "virtual",
            "width": 640,
            "height": 480,
            "lighting": "uniform",
            "distance": "fixed",
            "angle": "fronto-parallel",
            "occlusion": "none",
            "motion": "static",
        },
        "fingerprints": [{"algorithm": "phash64", "value": fingerprint}],
        "lineage": {"parent_item_ids": parents or [], "transform": "source" if not parents else "crop"},
        "status": "active",
    }


def annotation(annotation_id: str, item_id: str) -> dict:
    return {
        "annotation_id": annotation_id,
        "item_id": item_id,
        "review_status": "verified",
        "identity": {"class_id": "component", "status": "known", "instance_id": annotation_id},
        "geometry": [{"type": "bbox", "coordinate_space": "normalized", "values": [0.1, 0.1, 0.2, 0.2]}],
        "state": {"presence": "present"},
        "uncertainty": {"confidence": 0.95, "ambiguous": False, "reasons": []},
        "unobservable_regions": [],
    }


def draft_bundle() -> dict[str, dict]:
    items = [
        item("a", "board-a", "session-a", "1", "0000000000000000"),
        item("a2", "board-a", "session-a", "5", "00ff00ff00ff00ff"),
        item("b", "board-b", "session-b", "2", "ffffffffffffffff"),
        item("c", "board-c", "session-c", "3", "0f0f0f0f0f0f0f0f"),
        item("d", "board-d", "session-d", "4", "f0f0f0f0f0f0f0f0"),
    ]
    dataset = {
        "schema_version": "1.0",
        "dataset_id": "fixture-corpus",
        "dataset_revision": "r1",
        "created_at": FROZEN_AT,
        "purpose": "Unit-test corpus governance.",
        "visibility": "synthetic",
        "sources": [{
            "source_id": "source-1",
            "origin": "generated in test",
            "retrieved_at": FROZEN_AT,
            "license": {
                "name": "test fixture",
                "identifier": "LicenseRef-Test",
                "uri": "https://example.invalid/license",
                "redistributable": True,
                "review_status": "approved",
            },
            "consent": {"required": False, "basis": "synthetic", "scope": "tests"},
            "intended_uses": ["tests"],
            "restrictions": ["not release evidence"],
        }],
        "annotation_contract": {
            "revision": "annotation-v1",
            "identity_fields": ["class_id", "status"],
            "geometry_types": ["bbox", "polygon"],
            "state_fields": ["presence", "orientation"],
            "uncertainty": "confidence plus explicit ambiguity",
            "unobservable_regions": "normalized geometry",
        },
        "retention": {"raw_media": "test lifetime", "derived_artifacts": "test lifetime", "removal_sla_days": 0},
        "exclusions": [],
        "change_history": [],
        "hashes": {
            "inventory_manifest_digest": ZERO_DIGEST,
            "annotation_manifest_digest": ZERO_DIGEST,
            "split_manifest_digest": ZERO_DIGEST,
            "manifest_digest": ZERO_DIGEST,
        },
    }
    inventory = {
        "schema_version": "1.0",
        "dataset_id": "fixture-corpus",
        "dataset_revision": "r1",
        "created_at": FROZEN_AT,
        "items": items,
        "integrity": {"manifest_digest": ZERO_DIGEST},
    }
    annotations = {
        "schema_version": "1.0",
        "dataset_id": "fixture-corpus",
        "dataset_revision": "r1",
        "annotation_revision": "annotation-v1",
        "created_at": FROZEN_AT,
        "records": [annotation(f"annotation-{entry['item_id']}", entry["item_id"]) for entry in items],
        "integrity": {"inventory_manifest_digest": ZERO_DIGEST, "manifest_digest": ZERO_DIGEST},
    }
    splits = {
        "schema_version": "1.0",
        "dataset_id": "fixture-corpus",
        "dataset_revision": "r1",
        "split_revision": "split-v1",
        "split_unit": "physical_board_and_session",
        "splits": {"train": ["a", "a2"], "validation": ["b"], "calibration": ["c"], "test": ["d"]},
        "leakage_audit": {
            "algorithm_version": "tokito-corpus-audit/1",
            "max_hamming_distance": 2,
            "board_disjoint": True,
            "session_disjoint": True,
            "leakage_group_disjoint": True,
            "lineage_disjoint": True,
            "exact_duplicate_groups": [],
            "exact_cross_split_collisions": [],
            "near_duplicate_pairs": [],
            "near_duplicate_cross_split_pairs": [],
        },
        "integrity": {
            "inventory_manifest_digest": ZERO_DIGEST,
            "annotation_manifest_digest": ZERO_DIGEST,
            "manifest_digest": ZERO_DIGEST,
            "frozen_at": FROZEN_AT,
        },
    }
    return {"dataset": dataset, "inventory": inventory, "annotations": annotations, "splits": splits}


def frozen_bundle() -> dict[str, dict]:
    return freeze_bundle(**draft_bundle(), frozen_at=FROZEN_AT)


def change_set(bundle: dict[str, dict], operations: list[dict], target_revision: str = "r2") -> dict:
    change = {
        "schema_version": "1.0",
        "change_id": f"change-{target_revision}",
        "dataset_id": bundle["dataset"]["dataset_id"],
        "base_revision": bundle["dataset"]["dataset_revision"],
        "base_dataset_digest": declared_digest(bundle["dataset"]),
        "target_revision": target_revision,
        "created_at": "2026-08-30T00:00:00Z",
        "reason": "Test governed revision.",
        "operations": operations,
        "integrity": {"manifest_digest": ZERO_DIGEST},
    }
    change["integrity"]["manifest_digest"] = manifest_digest(change)
    return change


class CorpusGovernanceTest(unittest.TestCase):
    def test_repository_examples_form_a_freezable_bundle(self) -> None:
        example_dir = ROOT / "examples" / "manifests"
        bundle = {
            "dataset": json.loads((example_dir / "corpus.dataset-card.example.json").read_text(encoding="utf-8")),
            "inventory": json.loads((example_dir / "corpus.inventory-manifest.example.json").read_text(encoding="utf-8")),
            "annotations": json.loads((example_dir / "corpus.annotation-manifest.example.json").read_text(encoding="utf-8")),
            "splits": json.loads((example_dir / "corpus.split-manifest.example.json").read_text(encoding="utf-8")),
        }
        frozen = freeze_bundle(**bundle, frozen_at=FROZEN_AT)
        validate_bundle(**frozen)

    def test_freeze_is_deterministic_and_cross_hash_bound(self) -> None:
        first = frozen_bundle()
        second = frozen_bundle()
        self.assertEqual(first, second)
        validate_bundle(**first)
        self.assertEqual(first["dataset"]["hashes"]["inventory_manifest_digest"], declared_digest(first["inventory"]))
        self.assertEqual(first["dataset"]["hashes"]["split_manifest_digest"], declared_digest(first["splits"]))

    def test_tampering_is_rejected(self) -> None:
        bundle = frozen_bundle()
        bundle["inventory"]["items"][0]["board_revision"] = "tampered"
        with self.assertRaisesRegex(CorpusError, "digest mismatch"):
            validate_bundle(**bundle)

    def test_board_and_session_leakage_is_rejected(self) -> None:
        bundle = draft_bundle()
        next(entry for entry in bundle["inventory"]["items"] if entry["item_id"] == "b")["board_id"] = "board-a"
        report, violations = audit_leakage(bundle["inventory"], bundle["splits"], 2)
        self.assertFalse(report["board_disjoint"])
        self.assertTrue(any("physical board" in violation for violation in violations))

    def test_exact_digest_leakage_is_rejected(self) -> None:
        bundle = draft_bundle()
        target = next(entry for entry in bundle["inventory"]["items"] if entry["item_id"] == "b")
        target["media"]["sha256"] = bundle["inventory"]["items"][0]["media"]["sha256"]
        report, violations = audit_leakage(bundle["inventory"], bundle["splits"], 2)
        self.assertTrue(report["exact_cross_split_collisions"])
        self.assertTrue(any("exact media digest" in violation for violation in violations))

    def test_within_split_duplicates_are_recorded_without_becoming_leakage(self) -> None:
        bundle = draft_bundle()
        target = next(entry for entry in bundle["inventory"]["items"] if entry["item_id"] == "a2")
        target["media"]["sha256"] = bundle["inventory"]["items"][0]["media"]["sha256"]
        target["fingerprints"][0]["value"] = "0000000000000001"
        report, violations = audit_leakage(bundle["inventory"], bundle["splits"], 2)
        self.assertTrue(report["exact_duplicate_groups"])
        self.assertTrue(report["near_duplicate_pairs"])
        self.assertFalse(report["exact_cross_split_collisions"])
        self.assertFalse(report["near_duplicate_cross_split_pairs"])
        self.assertFalse(violations)

    def test_near_duplicate_fingerprint_leakage_is_rejected(self) -> None:
        bundle = draft_bundle()
        target = next(entry for entry in bundle["inventory"]["items"] if entry["item_id"] == "b")
        target["fingerprints"][0]["value"] = "0000000000000001"
        report, violations = audit_leakage(bundle["inventory"], bundle["splits"], 2)
        self.assertTrue(report["near_duplicate_cross_split_pairs"])
        self.assertTrue(any("near duplicate" in violation for violation in violations))

    def test_lineage_cannot_cross_splits(self) -> None:
        bundle = draft_bundle()
        target = next(entry for entry in bundle["inventory"]["items"] if entry["item_id"] == "b")
        target["lineage"] = {"parent_item_ids": ["a"], "transform": "crop"}
        report, violations = audit_leakage(bundle["inventory"], bundle["splits"], 2)
        self.assertFalse(report["lineage_disjoint"])
        self.assertTrue(any("lineage" in violation for violation in violations))

    def test_annotation_geometry_and_state_follow_contract(self) -> None:
        bundle = draft_bundle()
        bundle["annotations"]["records"][0]["geometry"][0]["values"] = [0.1, 0.1, 0.0, 0.2]
        with self.assertRaisesRegex(CorpusError, "width and height"):
            freeze_bundle(**bundle, frozen_at=FROZEN_AT)
        bundle = draft_bundle()
        bundle["annotations"]["records"][0]["state"]["undeclared"] = "value"
        with self.assertRaisesRegex(CorpusError, "undeclared state fields"):
            freeze_bundle(**bundle, frozen_at=FROZEN_AT)

        bundle = draft_bundle()
        bundle["annotations"]["records"][0]["review_status"] = "unreviewed"
        with self.assertRaisesRegex(CorpusError, "is not verified"):
            freeze_bundle(**bundle, frozen_at=FROZEN_AT)

    def test_removal_propagates_to_inventory_annotations_and_splits(self) -> None:
        bundle = frozen_bundle()
        change = change_set(bundle, [{"operation": "remove", "item_id": "a", "reason": "consent withdrawn"}])
        revised = apply_change_set(bundle, change, "2026-08-30T00:00:00Z")
        self.assertNotIn("a", {entry["item_id"] for entry in revised["inventory"]["items"]})
        self.assertNotIn("a", {record["item_id"] for record in revised["annotations"]["records"]})
        self.assertNotIn("a", revised["splits"]["splits"]["train"])
        self.assertEqual("r2", revised["dataset"]["dataset_revision"])
        self.assertEqual(declared_digest(change), revised["dataset"]["change_history"][-1]["change_set_digest"])
        validate_bundle(**revised)

    def test_removal_propagates_to_derived_descendants(self) -> None:
        draft = draft_bundle()
        derived = item("a-crop", "board-a", "session-a", "5", "00000000000000ff", parents=["a"])
        draft["inventory"]["items"].append(derived)
        draft["annotations"]["records"].append(annotation("annotation-a-crop", "a-crop"))
        draft["splits"]["splits"]["train"].append("a-crop")
        bundle = freeze_bundle(**draft, frozen_at=FROZEN_AT)
        change = change_set(bundle, [{"operation": "remove", "item_id": "a", "reason": "source removed"}])
        revised = apply_change_set(bundle, change, "2026-08-30T00:00:00Z")
        remaining = {entry["item_id"] for entry in revised["inventory"]["items"]}
        self.assertNotIn("a", remaining)
        self.assertNotIn("a-crop", remaining)

    def test_sensitive_correction_requires_annotation_invalidation(self) -> None:
        bundle = frozen_bundle()
        change = change_set(bundle, [{
            "operation": "correct", "item_id": "a", "reason": "digest corrected",
            "patch": {"media": {"sha256": "sha256:" + "9" * 64}},
            "invalidate_annotations": False,
        }])
        with self.assertRaisesRegex(CorpusError, "must invalidate annotations"):
            apply_change_set(bundle, change, "2026-08-30T00:00:00Z")

        change = change_set(bundle, [{
            "operation": "correct", "item_id": "a", "reason": "digest corrected",
            "patch": {"media": {"sha256": "sha256:" + "9" * 64}},
            "invalidate_annotations": True,
        }])
        revised = apply_change_set(bundle, change, "2026-08-30T00:00:00Z")
        self.assertNotIn("a", {record["item_id"] for record in revised["annotations"]["records"]})

    def test_sensitive_correction_invalidates_descendant_annotations(self) -> None:
        draft = draft_bundle()
        derived = item("a-crop", "board-a", "session-a", "5", "00000000000000ff", parents=["a"])
        draft["inventory"]["items"].append(derived)
        draft["annotations"]["records"].append(annotation("annotation-a-crop", "a-crop"))
        draft["splits"]["splits"]["train"].append("a-crop")
        bundle = freeze_bundle(**draft, frozen_at=FROZEN_AT)
        change = change_set(bundle, [{
            "operation": "correct", "item_id": "a", "reason": "source digest corrected",
            "patch": {"media": {"sha256": "sha256:" + "9" * 64}},
            "invalidate_annotations": True,
        }])
        revised = apply_change_set(bundle, change, "2026-08-30T00:00:00Z")
        annotated = {record["item_id"] for record in revised["annotations"]["records"]}
        self.assertNotIn("a", annotated)
        self.assertNotIn("a-crop", annotated)

    def test_annotation_correction_replaces_invalidated_record(self) -> None:
        bundle = frozen_bundle()
        replacement = annotation("annotation-a-corrected", "a")
        replacement["identity"]["class_id"] = "corrected-component"
        change = change_set(bundle, [{
            "operation": "correct", "item_id": "a", "reason": "label corrected",
            "invalidate_annotations": True,
            "replacement_annotations": [replacement],
        }])
        revised = apply_change_set(bundle, change, "2026-08-30T00:00:00Z")
        records = [record for record in revised["annotations"]["records"] if record["item_id"] == "a"]
        self.assertEqual([replacement], records)
        validate_bundle(**revised)

    def test_cli_seals_change_set_without_overwriting(self) -> None:
        bundle = frozen_bundle()
        change = change_set(bundle, [{"operation": "remove", "item_id": "a", "reason": "withdrawn"}])
        change["integrity"]["manifest_digest"] = ZERO_DIGEST
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            draft_path = root / "draft.json"
            output_path = root / "sealed.json"
            draft_path.write_text(json.dumps(change), encoding="utf-8")
            self.assertEqual(0, main(["seal-change-set", "--change-set", str(draft_path), "--output", str(output_path)]))
            sealed = json.loads(output_path.read_text(encoding="utf-8"))
            self.assertEqual(manifest_digest(sealed), declared_digest(sealed))
            with self.assertRaisesRegex(CorpusError, "output already exists"):
                main(["seal-change-set", "--change-set", str(draft_path), "--output", str(output_path)])

    def test_cli_freeze_then_validate_round_trip(self) -> None:
        bundle = draft_bundle()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            paths = {}
            for key, document in bundle.items():
                paths[key] = root / f"{key}.json"
                paths[key].write_text(json.dumps(document), encoding="utf-8")
            output = root / "frozen"
            common = [
                "--dataset-card", str(paths["dataset"]),
                "--inventory", str(paths["inventory"]),
                "--annotations", str(paths["annotations"]),
                "--splits", str(paths["splits"]),
            ]
            self.assertEqual(0, main(["freeze", *common, "--frozen-at", FROZEN_AT, "--output-dir", str(output)]))
            frozen_common = [
                "--dataset-card", str(output / "corpus.dataset-card.json"),
                "--inventory", str(output / "corpus.inventory-manifest.json"),
                "--annotations", str(output / "corpus.annotation-manifest.json"),
                "--splits", str(output / "corpus.split-manifest.json"),
            ]
            self.assertEqual(0, main(["validate", *frozen_common]))

    def test_asset_verification_checks_digest_size_and_path_boundary(self) -> None:
        bundle = draft_bundle()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            media = root / "media"
            media.mkdir()
            payload = b"0123456789abcdef"
            for entry in bundle["inventory"]["items"]:
                path = root / entry["media"]["uri"]
                path.write_bytes(payload)
                entry["media"]["sha256"] = "sha256:" + hashlib.sha256(payload).hexdigest()
                entry["media"]["size_bytes"] = len(payload)
            verify_assets(bundle["inventory"], root)
            bundle["inventory"]["items"][0]["media"]["size_bytes"] += 1
            with self.assertRaisesRegex(CorpusError, "asset size mismatch"):
                verify_assets(bundle["inventory"], root)

    def test_evaluation_must_bind_every_corpus_manifest_digest(self) -> None:
        bundle = frozen_bundle()
        inputs = {
            "dataset_revision": bundle["dataset"]["dataset_revision"],
            "dataset_manifest_digest": declared_digest(bundle["dataset"]),
            "inventory_manifest_digest": declared_digest(bundle["inventory"]),
            "annotation_manifest_digest": declared_digest(bundle["annotations"]),
            "split_revision": bundle["splits"]["split_revision"],
            "split_manifest_digest": declared_digest(bundle["splits"]),
            "model_bundle_digest": "sha256:" + "a" * 64,
            "preprocessing_digest": "sha256:" + "b" * 64,
        }
        evaluation = {
            "schema_version": "1.0", "run_id": "run-1", "created_at": FROZEN_AT, "inputs": inputs,
            "device": {"profile_id": "fixture", "manufacturer_model": "fixture", "soc": "fixture", "android_build": "fixture", "abi": "arm64-v8a"},
            "runtime": {"revision": "fixture", "backend": "fixture", "precision": "fixture", "threads": 1},
            "metrics": {"quality": {}, "latency": {}, "resources": {}, "thermal": {}},
            "scenarios": ["fixture"],
            "output": {"machine_manifest_digest": "sha256:" + "c" * 64, "human_report_digest": "sha256:" + "d" * 64},
        }
        verify_evaluation_binding(evaluation, bundle)
        evaluation["inputs"]["split_manifest_digest"] = "sha256:" + "e" * 64
        with self.assertRaisesRegex(CorpusError, "split_manifest_digest"):
            verify_evaluation_binding(evaluation, bundle)


if __name__ == "__main__":
    unittest.main()
