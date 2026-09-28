#!/usr/bin/env python3
"""Freeze, validate, audit, and revise Tokito corpus metadata.

The tool deliberately uses only the Python standard library. Raw media remains
outside Git; this program governs the immutable manifests that describe it.
"""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import os
import sys
import tempfile
from collections import defaultdict
from collections.abc import Iterable
from pathlib import Path
from typing import Any

from validate_governance import ROOT, ValidationError, load_json, validate_value

ZERO_DIGEST = "sha256:" + "0" * 64
AUDIT_VERSION = "tokito-corpus-audit/1"
SPLIT_NAMES = ("train", "validation", "calibration", "test")
MANIFEST_FILES = {
    "dataset": "corpus.dataset-card.json",
    "inventory": "corpus.inventory-manifest.json",
    "annotations": "corpus.annotation-manifest.json",
    "splits": "corpus.split-manifest.json",
}


class CorpusError(RuntimeError):
    """A deterministic corpus contract violation."""


def fail(message: str) -> None:
    raise CorpusError(message)


def canonical_bytes(document: dict[str, Any]) -> bytes:
    return json.dumps(
        document,
        ensure_ascii=False,
        allow_nan=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def without_self_digest(document: dict[str, Any]) -> dict[str, Any]:
    candidate = copy.deepcopy(document)
    container = candidate.get("hashes") or candidate.get("integrity")
    if not isinstance(container, dict) or "manifest_digest" not in container:
        fail("manifest has no hashes.manifest_digest or integrity.manifest_digest")
    del container["manifest_digest"]
    return candidate


def manifest_digest(document: dict[str, Any]) -> str:
    return "sha256:" + hashlib.sha256(canonical_bytes(without_self_digest(document))).hexdigest()


def declared_digest(document: dict[str, Any]) -> str:
    container = document.get("hashes") or document.get("integrity")
    if not isinstance(container, dict):
        fail("manifest has no digest container")
    value = container.get("manifest_digest")
    if not isinstance(value, str):
        fail("manifest_digest is missing")
    return value


def verify_digest(document: dict[str, Any], label: str, allow_draft: bool = False) -> None:
    declared = declared_digest(document)
    if declared == ZERO_DIGEST and allow_draft:
        return
    actual = manifest_digest(document)
    if declared != actual:
        fail(f"{label}: manifest digest mismatch; declared {declared}, calculated {actual}")


def schema_validate(document: dict[str, Any], schema_name: str, label: str) -> None:
    schema_path = ROOT / "schemas" / schema_name
    try:
        validate_value(label, document, load_json(schema_path))
    except ValidationError as exc:
        raise CorpusError(str(exc)) from exc


def unique_index(records: Iterable[dict[str, Any]], key: str, label: str) -> dict[str, dict[str, Any]]:
    result: dict[str, dict[str, Any]] = {}
    for record in records:
        value = record[key]
        if value in result:
            fail(f"{label}: duplicate {key} {value!r}")
        result[value] = record
    return result


def split_membership(splits: dict[str, Any]) -> dict[str, str]:
    membership: dict[str, str] = {}
    for split in SPLIT_NAMES:
        for item_id in splits["splits"][split]:
            previous = membership.setdefault(item_id, split)
            if previous != split:
                fail(f"item {item_id!r} appears in both {previous} and {split}")
    return membership


def _group_crossings(
    active_items: list[dict[str, Any]], membership: dict[str, str], key,
) -> list[str]:
    groups: dict[str, set[str]] = defaultdict(set)
    members: dict[str, list[str]] = defaultdict(list)
    for item in active_items:
        group = str(key(item))
        groups[group].add(membership[item["item_id"]])
        members[group].append(item["item_id"])
    return [
        f"{group}:{','.join(sorted(members[group]))}"
        for group, assigned in sorted(groups.items())
        if len(assigned) > 1
    ]


class HammingIndex:
    """BK-tree for exact bounded-distance lookup over fixed-width fingerprints."""

    class Node:
        def __init__(self, value: int, item: tuple[str, str]) -> None:
            self.value = value
            self.items = [item]
            self.children: dict[int, HammingIndex.Node] = {}

    def __init__(self) -> None:
        self.root: HammingIndex.Node | None = None

    @staticmethod
    def distance(left: int, right: int) -> int:
        return (left ^ right).bit_count()

    def insert(self, value: int, item: tuple[str, str]) -> None:
        if self.root is None:
            self.root = self.Node(value, item)
            return
        node = self.root
        while True:
            distance = self.distance(value, node.value)
            if distance == 0:
                node.items.append(item)
                return
            child = node.children.get(distance)
            if child is None:
                node.children[distance] = self.Node(value, item)
                return
            node = child

    def query(self, value: int, radius: int) -> Iterable[tuple[int, tuple[str, str]]]:
        if self.root is None:
            return
        pending = [self.root]
        while pending:
            node = pending.pop()
            distance = self.distance(value, node.value)
            if distance <= radius:
                for item in node.items:
                    yield distance, item
            low, high = distance - radius, distance + radius
            pending.extend(child for edge, child in node.children.items() if low <= edge <= high)


def audit_leakage(
    inventory: dict[str, Any], splits: dict[str, Any], max_hamming_distance: int,
) -> tuple[dict[str, Any], list[str]]:
    if max_hamming_distance < 0:
        fail("max_hamming_distance cannot be negative")
    items = unique_index(inventory["items"], "item_id", "inventory")
    membership = split_membership(splits)
    active = [item for item in inventory["items"] if item["status"] == "active"]
    active_ids = {item["item_id"] for item in active}
    excluded_ids = set(items) - active_ids

    unknown = sorted(set(membership) - set(items))
    missing = sorted(active_ids - set(membership))
    included_excluded = sorted(excluded_ids & set(membership))
    violations: list[str] = []
    if unknown:
        violations.append(f"split references unknown items: {', '.join(unknown)}")
    if missing:
        violations.append(f"active items have no split: {', '.join(missing)}")
    if included_excluded:
        violations.append(f"excluded items appear in splits: {', '.join(included_excluded)}")
    if unknown or missing or included_excluded:
        report = {
            "algorithm_version": AUDIT_VERSION,
            "max_hamming_distance": max_hamming_distance,
            "board_disjoint": False,
            "session_disjoint": False,
            "leakage_group_disjoint": False,
            "lineage_disjoint": False,
            "exact_duplicate_groups": [],
            "exact_cross_split_collisions": [],
            "near_duplicate_pairs": [],
            "near_duplicate_cross_split_pairs": [],
        }
        return report, violations

    board_crossings = _group_crossings(active, membership, lambda item: item["board_id"])
    session_crossings = _group_crossings(
        active, membership, lambda item: f"{item['board_id']}/{item['session_id']}"
    )
    leakage_group_crossings = _group_crossings(active, membership, lambda item: item["leakage_group_id"])

    lineage_pairs: set[str] = set()
    for item in active:
        child_split = membership[item["item_id"]]
        for parent_id in item["lineage"]["parent_item_ids"]:
            if parent_id not in items:
                violations.append(f"item {item['item_id']!r} references unknown parent {parent_id!r}")
                continue
            parent_split = membership.get(parent_id)
            if parent_split is not None and parent_split != child_split:
                lineage_pairs.add(f"{parent_id}@{parent_split}<->{item['item_id']}@{child_split}")

    digest_groups: dict[str, list[tuple[str, str]]] = defaultdict(list)
    for item in active:
        digest_groups[item["media"]["sha256"]].append((item["item_id"], membership[item["item_id"]]))
    exact_all = []
    exact_cross = []
    for digest, entries in sorted(digest_groups.items()):
        if len(entries) > 1:
            description = f"{digest}:{','.join(f'{item}@{split}' for item, split in sorted(entries))}"
            exact_all.append(description)
            if len({split for _, split in entries}) > 1:
                exact_cross.append(description)

    near_all: set[str] = set()
    near_cross: set[str] = set()
    indexes: dict[tuple[str, int], HammingIndex] = defaultdict(HammingIndex)
    for item in sorted(active, key=lambda record: record["item_id"]):
        current = (item["item_id"], membership[item["item_id"]])
        algorithms: set[str] = set()
        for fingerprint in item["fingerprints"]:
            algorithm, value_text = fingerprint["algorithm"], fingerprint["value"]
            if algorithm in algorithms:
                violations.append(f"item {item['item_id']!r} repeats fingerprint algorithm {algorithm!r}")
                continue
            algorithms.add(algorithm)
            try:
                value = int(value_text, 16)
            except ValueError:
                violations.append(f"item {item['item_id']!r} has a non-hex fingerprint")
                continue
            index = indexes[(algorithm, len(value_text))]
            for distance, other in index.query(value, max_hamming_distance):
                ordered = sorted((f"{other[0]}@{other[1]}", f"{current[0]}@{current[1]}"))
                description = f"{algorithm}:d={distance}:{ordered[0]}<->{ordered[1]}"
                near_all.add(description)
                if other[1] != current[1]:
                    near_cross.add(description)
            index.insert(value, current)

    for label, entries in (
        ("physical board", board_crossings),
        ("board session", session_crossings),
        ("leakage group", leakage_group_crossings),
        ("lineage", sorted(lineage_pairs)),
        ("exact media digest", exact_cross),
        ("near duplicate", sorted(near_cross)),
    ):
        if entries:
            violations.append(f"cross-split {label} leakage: {'; '.join(entries)}")

    report = {
        "algorithm_version": AUDIT_VERSION,
        "max_hamming_distance": max_hamming_distance,
        "board_disjoint": not board_crossings,
        "session_disjoint": not session_crossings,
        "leakage_group_disjoint": not leakage_group_crossings,
        "lineage_disjoint": not lineage_pairs,
        "exact_duplicate_groups": exact_all,
        "exact_cross_split_collisions": exact_cross,
        "near_duplicate_pairs": sorted(near_all),
        "near_duplicate_cross_split_pairs": sorted(near_cross),
    }
    return report, violations


def _validate_geometry(geometry: Any, allowed: set[str], label: str) -> None:
    if not isinstance(geometry, list):
        fail(f"{label}: geometry must be an array")
    for index, shape in enumerate(geometry):
        current = f"{label}[{index}]"
        if not isinstance(shape, dict):
            fail(f"{current}: geometry entry must be an object")
        if set(shape) != {"type", "coordinate_space", "values"}:
            fail(f"{current}: expected only type, coordinate_space, and values")
        kind, coordinate_space, values = shape["type"], shape["coordinate_space"], shape["values"]
        if kind not in allowed:
            fail(f"{current}: geometry type {kind!r} is outside the dataset contract")
        if not isinstance(coordinate_space, str) or not coordinate_space:
            fail(f"{current}: coordinate_space must be non-empty")
        if not isinstance(values, list) or any(not isinstance(value, (int, float)) or isinstance(value, bool) for value in values):
            fail(f"{current}: values must be numeric")
        lengths = {"bbox": 4, "keypoint": 2, "transform": 9, "none": 0}
        if kind in lengths and len(values) != lengths[kind]:
            fail(f"{current}: {kind} requires {lengths[kind]} values")
        if kind == "polygon" and (len(values) < 6 or len(values) % 2):
            fail(f"{current}: polygon requires at least three coordinate pairs")
        if coordinate_space == "normalized" and any(value < 0 or value > 1 for value in values):
            fail(f"{current}: normalized values must be between zero and one")
        if kind == "bbox" and (values[2] <= 0 or values[3] <= 0):
            fail(f"{current}: bbox width and height must be positive")


def _validate_lineage(items: dict[str, dict[str, Any]]) -> None:
    visiting: set[str] = set()
    visited: set[str] = set()

    def visit(item_id: str) -> None:
        if item_id in visiting:
            fail(f"inventory lineage contains a cycle at {item_id!r}")
        if item_id in visited:
            return
        visiting.add(item_id)
        for parent in items[item_id]["lineage"]["parent_item_ids"]:
            if parent not in items:
                fail(f"item {item_id!r} references unknown parent {parent!r}")
            if parent == item_id:
                fail(f"item {item_id!r} cannot be its own parent")
            visit(parent)
        visiting.remove(item_id)
        visited.add(item_id)

    for item_id in items:
        visit(item_id)


def validate_semantics(
    dataset: dict[str, Any], inventory: dict[str, Any], annotations: dict[str, Any], splits: dict[str, Any],
) -> tuple[dict[str, Any], list[str]]:
    ids = {dataset["dataset_id"], inventory["dataset_id"], annotations["dataset_id"], splits["dataset_id"]}
    revisions = {dataset["dataset_revision"], inventory["dataset_revision"], annotations["dataset_revision"], splits["dataset_revision"]}
    if len(ids) != 1:
        fail(f"bundle dataset_id mismatch: {sorted(ids)}")
    if len(revisions) != 1:
        fail(f"bundle dataset_revision mismatch: {sorted(revisions)}")

    sources = unique_index(dataset["sources"], "source_id", "dataset sources")
    unique_index(dataset["change_history"], "change_id", "dataset change history")
    items = unique_index(inventory["items"], "item_id", "inventory")
    _validate_lineage(items)
    for item in items.values():
        if item["source_id"] not in sources:
            fail(f"item {item['item_id']!r} references unknown source {item['source_id']!r}")
        source = sources[item["source_id"]]
        if item["status"] == "active" and source["license"]["review_status"] != "approved":
            fail(f"active item {item['item_id']!r} uses a source whose license is not approved")
        if item["status"] == "excluded" and not item.get("exclusion_reason"):
            fail(f"excluded item {item['item_id']!r} requires exclusion_reason")
        if item["status"] == "active" and "exclusion_reason" in item:
            fail(f"active item {item['item_id']!r} cannot carry exclusion_reason")
        if item["status"] == "active" and not item["fingerprints"]:
            fail(f"active item {item['item_id']!r} requires at least one duplicate-detection fingerprint")
        if item["status"] == "active":
            excluded_parents = [
                parent_id
                for parent_id in item["lineage"]["parent_item_ids"]
                if items[parent_id]["status"] != "active"
            ]
            if excluded_parents:
                fail(
                    f"active item {item['item_id']!r} derives from excluded parents: "
                    + ", ".join(sorted(excluded_parents))
                )

    records = unique_index(annotations["records"], "annotation_id", "annotations")
    allowed_geometry = set(dataset["annotation_contract"]["geometry_types"])
    allowed_states = set(dataset["annotation_contract"]["state_fields"])
    for record in records.values():
        item = items.get(record["item_id"])
        if item is None:
            fail(f"annotation {record['annotation_id']!r} references unknown item {record['item_id']!r}")
        if item["status"] != "active":
            fail(f"annotation {record['annotation_id']!r} references excluded item {record['item_id']!r}")
        if record["review_status"] != "verified":
            fail(f"annotation {record['annotation_id']!r} is not verified")
        _validate_geometry(record["geometry"], allowed_geometry, f"annotation {record['annotation_id']} geometry")
        _validate_geometry(record["unobservable_regions"], allowed_geometry, f"annotation {record['annotation_id']} unobservable_regions")
        unknown_states = sorted(set(record["state"]) - allowed_states)
        if unknown_states:
            fail(f"annotation {record['annotation_id']!r} has undeclared state fields: {', '.join(unknown_states)}")
        if record["identity"]["status"] == "ambiguous" and not record["uncertainty"]["ambiguous"]:
            fail(f"annotation {record['annotation_id']!r} marks ambiguous identity without ambiguous uncertainty")
        if record["uncertainty"]["ambiguous"] and not record["uncertainty"]["reasons"]:
            fail(f"annotation {record['annotation_id']!r} requires reasons when ambiguous")

    empty_splits = [name for name in SPLIT_NAMES if not splits["splits"][name]]
    if empty_splits:
        fail(f"frozen corpus requires non-empty splits: {', '.join(empty_splits)}")
    threshold = splits["leakage_audit"]["max_hamming_distance"]
    return audit_leakage(inventory, splits, threshold)


def verify_assets(inventory: dict[str, Any], asset_root: Path) -> None:
    root = asset_root.resolve(strict=True)
    for item in inventory["items"]:
        uri = item["media"]["uri"]
        if "://" in uri:
            fail(f"item {item['item_id']!r}: remote URI cannot be verified against asset root")
        path = (root / uri).resolve(strict=True)
        if path != root and root not in path.parents:
            fail(f"item {item['item_id']!r}: media URI escapes asset root")
        digest = hashlib.sha256()
        size = 0
        with path.open("rb") as handle:
            for block in iter(lambda: handle.read(1024 * 1024), b""):
                digest.update(block)
                size += len(block)
        actual = "sha256:" + digest.hexdigest()
        if actual != item["media"]["sha256"]:
            fail(f"item {item['item_id']!r}: asset digest mismatch")
        if size != item["media"]["size_bytes"]:
            fail(f"item {item['item_id']!r}: asset size mismatch")


def validate_bundle(
    dataset: dict[str, Any], inventory: dict[str, Any], annotations: dict[str, Any], splits: dict[str, Any],
    *, allow_draft: bool = False, asset_root: Path | None = None,
) -> dict[str, Any]:
    schema_validate(dataset, "corpus.dataset-card.schema.json", "dataset card")
    schema_validate(inventory, "corpus.inventory-manifest.schema.json", "inventory")
    schema_validate(annotations, "corpus.annotation-manifest.schema.json", "annotations")
    schema_validate(splits, "corpus.split-manifest.schema.json", "splits")
    report, violations = validate_semantics(dataset, inventory, annotations, splits)
    if violations:
        fail("leakage audit failed:\n- " + "\n- ".join(violations))
    if splits["leakage_audit"] != report and not allow_draft:
        fail("split manifest leakage_audit does not match a fresh deterministic audit")

    for label, document in (("dataset card", dataset), ("inventory", inventory), ("annotations", annotations), ("splits", splits)):
        verify_digest(document, label, allow_draft=allow_draft)
    if not allow_draft:
        inventory_digest = declared_digest(inventory)
        annotation_digest = declared_digest(annotations)
        split_digest = declared_digest(splits)
        expected = {
            "annotations.inventory_manifest_digest": (annotations["integrity"]["inventory_manifest_digest"], inventory_digest),
            "splits.inventory_manifest_digest": (splits["integrity"]["inventory_manifest_digest"], inventory_digest),
            "splits.annotation_manifest_digest": (splits["integrity"]["annotation_manifest_digest"], annotation_digest),
            "dataset.inventory_manifest_digest": (dataset["hashes"]["inventory_manifest_digest"], inventory_digest),
            "dataset.annotation_manifest_digest": (dataset["hashes"]["annotation_manifest_digest"], annotation_digest),
            "dataset.split_manifest_digest": (dataset["hashes"]["split_manifest_digest"], split_digest),
        }
        for label, (actual, wanted) in expected.items():
            if actual != wanted:
                fail(f"{label}: expected {wanted}, got {actual}")
    if asset_root is not None:
        verify_assets(inventory, asset_root)
    return report


def freeze_bundle(
    dataset: dict[str, Any], inventory: dict[str, Any], annotations: dict[str, Any], splits: dict[str, Any], frozen_at: str,
) -> dict[str, dict[str, Any]]:
    dataset, inventory, annotations, splits = map(copy.deepcopy, (dataset, inventory, annotations, splits))
    schema_validate(dataset, "corpus.dataset-card.schema.json", "dataset card")
    schema_validate(inventory, "corpus.inventory-manifest.schema.json", "inventory")
    schema_validate(annotations, "corpus.annotation-manifest.schema.json", "annotations")
    schema_validate(splits, "corpus.split-manifest.schema.json", "splits")
    report, violations = validate_semantics(dataset, inventory, annotations, splits)
    if violations:
        fail("cannot freeze a leaking corpus:\n- " + "\n- ".join(violations))
    splits["leakage_audit"] = report
    splits["integrity"]["frozen_at"] = frozen_at

    inventory["integrity"]["manifest_digest"] = ZERO_DIGEST
    inventory["integrity"]["manifest_digest"] = manifest_digest(inventory)
    annotations["integrity"]["inventory_manifest_digest"] = declared_digest(inventory)
    annotations["integrity"]["manifest_digest"] = ZERO_DIGEST
    annotations["integrity"]["manifest_digest"] = manifest_digest(annotations)
    splits["integrity"]["inventory_manifest_digest"] = declared_digest(inventory)
    splits["integrity"]["annotation_manifest_digest"] = declared_digest(annotations)
    splits["integrity"]["manifest_digest"] = ZERO_DIGEST
    splits["integrity"]["manifest_digest"] = manifest_digest(splits)
    dataset["hashes"]["inventory_manifest_digest"] = declared_digest(inventory)
    dataset["hashes"]["annotation_manifest_digest"] = declared_digest(annotations)
    dataset["hashes"]["split_manifest_digest"] = declared_digest(splits)
    dataset["hashes"]["manifest_digest"] = ZERO_DIGEST
    dataset["hashes"]["manifest_digest"] = manifest_digest(dataset)
    result = {"dataset": dataset, "inventory": inventory, "annotations": annotations, "splits": splits}
    validate_bundle(**result)
    return result


def verify_evaluation_binding(evaluation: dict[str, Any], bundle: dict[str, dict[str, Any]]) -> None:
    schema_validate(evaluation, "evaluation.run-manifest.schema.json", "evaluation")
    inputs = evaluation["inputs"]
    expected = {
        "dataset_revision": bundle["dataset"]["dataset_revision"],
        "dataset_manifest_digest": declared_digest(bundle["dataset"]),
        "inventory_manifest_digest": declared_digest(bundle["inventory"]),
        "annotation_manifest_digest": declared_digest(bundle["annotations"]),
        "split_revision": bundle["splits"]["split_revision"],
        "split_manifest_digest": declared_digest(bundle["splits"]),
    }
    for key, value in expected.items():
        if inputs[key] != value:
            fail(f"evaluation inputs.{key}: expected {value}, got {inputs[key]}")


def _merge_patch(target: dict[str, Any], patch: dict[str, Any]) -> dict[str, Any]:
    result = copy.deepcopy(target)
    for key, value in patch.items():
        if value is None:
            result.pop(key, None)
        elif isinstance(value, dict) and isinstance(result.get(key), dict):
            result[key] = _merge_patch(result[key], value)
        else:
            result[key] = copy.deepcopy(value)
    return result


def apply_change_set(
    bundle: dict[str, dict[str, Any]], change: dict[str, Any], frozen_at: str,
) -> dict[str, dict[str, Any]]:
    validate_bundle(**bundle)
    schema_validate(change, "corpus.change-set.schema.json", "change set")
    verify_digest(change, "change set")
    dataset, inventory, annotations, splits = map(
        copy.deepcopy, (bundle["dataset"], bundle["inventory"], bundle["annotations"], bundle["splits"])
    )
    if change["dataset_id"] != dataset["dataset_id"]:
        fail("change set dataset_id does not match bundle")
    if change["base_revision"] != dataset["dataset_revision"]:
        fail("change set base_revision does not match bundle")
    if change["base_dataset_digest"] != declared_digest(dataset):
        fail("change set base_dataset_digest does not match bundle")
    if change["target_revision"] == change["base_revision"]:
        fail("change set target_revision must differ from base_revision")

    items = unique_index(inventory["items"], "item_id", "inventory")
    seen: set[str] = set()
    removed: set[str] = set()
    invalidate: set[str] = set()
    replacement_annotations: dict[str, list[dict[str, Any]]] = {}
    corrected: set[str] = set()
    for operation in change["operations"]:
        item_id = operation["item_id"]
        if item_id in seen:
            fail(f"change set contains multiple operations for item {item_id!r}")
        seen.add(item_id)
        if item_id not in items:
            fail(f"change set references unknown item {item_id!r}")
        if operation["operation"] == "remove":
            if "patch" in operation or "invalidate_annotations" in operation or "replacement_annotations" in operation:
                fail(f"remove operation for {item_id!r} cannot contain correction fields")
            removed.add(item_id)
            continue
        patch = operation.get("patch")
        replacements = operation.get("replacement_annotations")
        if (not isinstance(patch, dict) or not patch) and not isinstance(replacements, list):
            fail(f"correct operation for {item_id!r} requires a patch or replacement_annotations")
        if operation.get("invalidate_annotations") is not True:
            fail(f"correct operation for {item_id!r} must invalidate annotations")
        if isinstance(patch, dict) and patch:
            if "item_id" in patch:
                fail(f"correct operation for {item_id!r} cannot change item_id")
            items[item_id] = _merge_patch(items[item_id], patch)
        if isinstance(replacements, list):
            for record in replacements:
                if not isinstance(record, dict) or record.get("item_id") != item_id:
                    fail(f"replacement annotation for {item_id!r} must carry the same item_id")
            replacement_annotations[item_id] = copy.deepcopy(replacements)
        invalidate.add(item_id)
        corrected.add(item_id)

    children: dict[str, set[str]] = defaultdict(set)
    for child in items.values():
        for parent_id in child["lineage"]["parent_item_ids"]:
            children[parent_id].add(child["item_id"])

    def closure(roots: set[str]) -> set[str]:
        result = set(roots)
        pending = list(roots)
        while pending:
            for child_id in children.get(pending.pop(), set()):
                if child_id not in result:
                    result.add(child_id)
                    pending.append(child_id)
        return result

    removed = closure(removed)
    removed_corrections = sorted(removed & corrected)
    if removed_corrections:
        fail(
            "change set corrects items removed through the same lineage change: "
            + ", ".join(removed_corrections)
        )
    invalidate = closure(invalidate) - removed

    inventory["items"] = [items[item["item_id"]] for item in inventory["items"] if item["item_id"] not in removed]
    annotations["records"] = [
        record for record in annotations["records"]
        if record["item_id"] not in removed and record["item_id"] not in invalidate
    ]
    for item_id in sorted(replacement_annotations):
        annotations["records"].extend(replacement_annotations[item_id])
    for split in SPLIT_NAMES:
        splits["splits"][split] = [item_id for item_id in splits["splits"][split] if item_id not in removed]

    target_revision = change["target_revision"]
    used_revisions = {entry["target_revision"] for entry in dataset["change_history"]}
    if target_revision in used_revisions:
        fail(f"change set target_revision {target_revision!r} already exists in change history")
    dataset["dataset_revision"] = target_revision
    inventory["dataset_revision"] = target_revision
    annotations["dataset_revision"] = target_revision
    annotations["annotation_revision"] = target_revision
    splits["dataset_revision"] = target_revision
    splits["split_revision"] = target_revision
    inventory["created_at"] = change["created_at"]
    annotations["created_at"] = change["created_at"]
    dataset["change_history"].append({
        "change_id": change["change_id"],
        "base_revision": change["base_revision"],
        "target_revision": target_revision,
        "change_set_digest": declared_digest(change),
    })
    return freeze_bundle(dataset, inventory, annotations, splits, frozen_at)


def load_bundle(args: argparse.Namespace) -> dict[str, dict[str, Any]]:
    return {
        "dataset": load_json(Path(args.dataset_card)),
        "inventory": load_json(Path(args.inventory)),
        "annotations": load_json(Path(args.annotations)),
        "splits": load_json(Path(args.splits)),
    }


def atomic_write_json(path: Path, document: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=path.name + ".", suffix=".tmp", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as handle:
            json.dump(document, handle, ensure_ascii=False, allow_nan=False, sort_keys=True, indent=2)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
    except BaseException:
        try:
            os.unlink(temporary)
        except FileNotFoundError:
            pass
        raise


def write_bundle(output_dir: Path, bundle: dict[str, dict[str, Any]]) -> None:
    if output_dir.exists() and any(output_dir.iterdir()):
        fail(f"output directory is not empty: {output_dir}")
    output_dir.mkdir(parents=True, exist_ok=True)
    for key, file_name in MANIFEST_FILES.items():
        atomic_write_json(output_dir / file_name, bundle[key])


def add_bundle_arguments(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--dataset-card", required=True, type=Path)
    parser.add_argument("--inventory", required=True, type=Path)
    parser.add_argument("--annotations", required=True, type=Path)
    parser.add_argument("--splits", required=True, type=Path)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    validate = sub.add_parser("validate", help="validate a frozen bundle and optional assets/evaluation")
    add_bundle_arguments(validate)
    validate.add_argument("--asset-root", type=Path)
    validate.add_argument("--evaluation", action="append", type=Path, default=[])

    audit = sub.add_parser("audit", help="run deterministic leakage checks on draft or frozen manifests")
    add_bundle_arguments(audit)

    freeze = sub.add_parser("freeze", help="audit and write an immutable, cross-hash-bound bundle")
    add_bundle_arguments(freeze)
    freeze.add_argument("--frozen-at", required=True, help="RFC 3339 timestamp supplied by the caller")
    freeze.add_argument("--output-dir", required=True, type=Path)

    apply = sub.add_parser("apply-changes", help="propagate a signed correction/removal change set")
    add_bundle_arguments(apply)
    apply.add_argument("--change-set", required=True, type=Path)
    apply.add_argument("--frozen-at", required=True, help="RFC 3339 timestamp supplied by the caller")
    apply.add_argument("--output-dir", required=True, type=Path)

    seal = sub.add_parser("seal-change-set", help="calculate and write an immutable change-set digest")
    seal.add_argument("--change-set", required=True, type=Path)
    seal.add_argument("--output", required=True, type=Path)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if args.command == "seal-change-set":
        change = load_json(args.change_set)
        schema_validate(change, "corpus.change-set.schema.json", "change set")
        if declared_digest(change) != ZERO_DIGEST:
            fail("change set is already sealed; refusing to rewrite it")
        change["integrity"]["manifest_digest"] = manifest_digest(change)
        if args.output.exists():
            fail(f"output already exists: {args.output}")
        atomic_write_json(args.output, change)
        print(json.dumps({"sealed": True, "change_set_digest": declared_digest(change)}, sort_keys=True))
        return 0

    bundle = load_bundle(args)
    if args.command == "validate":
        report = validate_bundle(**bundle, asset_root=args.asset_root)
        for path in args.evaluation:
            verify_evaluation_binding(load_json(path), bundle)
        print(json.dumps({"valid": True, "leakage_audit": report}, sort_keys=True))
    elif args.command == "audit":
        schema_validate(bundle["dataset"], "corpus.dataset-card.schema.json", "dataset card")
        schema_validate(bundle["inventory"], "corpus.inventory-manifest.schema.json", "inventory")
        schema_validate(bundle["annotations"], "corpus.annotation-manifest.schema.json", "annotations")
        schema_validate(bundle["splits"], "corpus.split-manifest.schema.json", "splits")
        report, violations = validate_semantics(**bundle)
        print(json.dumps({"valid": not violations, "violations": violations, "leakage_audit": report}, sort_keys=True))
        if violations:
            return 2
    elif args.command == "freeze":
        result = freeze_bundle(**bundle, frozen_at=args.frozen_at)
        write_bundle(args.output_dir, result)
        print(json.dumps({"frozen": True, "dataset_digest": declared_digest(result["dataset"])}, sort_keys=True))
    elif args.command == "apply-changes":
        result = apply_change_set(bundle, load_json(args.change_set), args.frozen_at)
        write_bundle(args.output_dir, result)
        print(json.dumps({"revised": True, "dataset_digest": declared_digest(result["dataset"])}, sort_keys=True))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (CorpusError, ValidationError, OSError, json.JSONDecodeError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        raise SystemExit(1)
