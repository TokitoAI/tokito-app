#!/usr/bin/env python3
"""Compile actual dataset engineering records into a scoped local RAG corpus.

No photo labels, synthetic fixtures or inferred coordinates become source facts.
Output is private development data until separate license/signature admission.
"""
from __future__ import annotations
import argparse
import csv
import hashlib
import json
from pathlib import Path
import sqlite3
import struct
import subprocess
import tempfile
import os
from contextlib import closing

import prepare_embedding_bundle as embedding


def canonical(value) -> bytes:
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode()


def read_source(root: Path, relative: str, revision: str) -> tuple[bytes, dict]:
    path = (root / relative).resolve()
    if not path.is_relative_to(root.resolve()) or not path.is_file() or path.stat().st_size > 16_000_000:
        raise ValueError("source path escaped dataset or exceeded bound")
    data = path.read_bytes()
    if data.startswith(b"version https://git-lfs.github.com/spec"):
        raise ValueError(f"source is an unmaterialized LFS pointer: {relative}")
    committed = subprocess.run(["git", "show", f"{revision}:{relative}"], cwd=root, check=True, capture_output=True).stdout
    # Windows checkout conversion is not a change to the source's Git identity.
    if data != committed and data.replace(b"\r\n", b"\n") != committed:
        raise ValueError(f"source differs from pinned commit: {relative}")
    return committed, {"path": relative, "sha256": hashlib.sha256(committed).hexdigest()}


def compile_corpus(root: Path, board_ids: list[str]) -> dict:
    if not board_ids or len(board_ids) > 64 or len(set(board_ids)) != len(board_ids):
        raise ValueError("board scope must be nonempty, bounded and unique")
    revision = subprocess.run(["git", "rev-parse", "HEAD"], cwd=root, check=True, capture_output=True, text=True).stdout.strip()
    raw, source = read_source(root, "source-bundles.json", revision)
    catalog = json.loads(raw)
    license_raw, license_source = read_source(root, "docs/sources/LICENSE_NOTES.md", revision)
    boards, records, sources = [], [], [source, license_source]
    reference_facts = {}
    if (root / "knowledge/reference-facts.json").exists():
        reference_raw, reference_source = read_source(root, "knowledge/reference-facts.json", revision)
        reference_facts = json.loads(reference_raw)
        sources.append(reference_source)
    for board_id in board_ids:
        if board_id not in catalog:
            raise ValueError(f"unknown dataset board identity: {board_id}")
        entry = catalog[board_id]
        base = entry["path"]
        # Adapter selection follows structured source formats, never board-name branches.
        candidates = [f"{base}/structured-rev-c/components.json",
                      f"{base}/structured-source/schematic-component-inventory.json",
                      f"{base}/community-clone/structured/bom-components.csv"]
        available = [name for name in candidates if (root / name).is_file()]
        if len(available) != 1:
            raise ValueError(f"expected one supported engineering inventory for {board_id}")
        filename = available[0]
        payload, citation = read_source(root, filename, revision)
        sources.append(citation)
        if filename.endswith(".csv"):
            rows = list(csv.DictReader(payload.decode("utf-8").splitlines()))
            components = [(r["reference"], {"value": r["value"], "footprint": r["footprint"]},
                           {"csv_row_1based": i + 2}) for i, r in enumerate(rows)]
        else:
            document = json.loads(payload)
            if isinstance(document, dict):
                components = [(r["refdes"], {k: v for k, v in r.items() if k != "schematic_text_candidates"},
                               {"json_pointer": f"/components/{i}"}) for i, r in enumerate(document["components"])]
            else:
                components = [(r["designator"], r, {"json_pointer": f"/{i}"}) for i, r in enumerate(document)]
        grouped = {}
        for reference, facts, locator in components:
            if (not isinstance(reference, str) or not reference.strip() or len(reference) > 64
                    or any(ord(c) < 32 for c in reference) or ":" in reference):
                raise ValueError("invalid source designator")
            grouped.setdefault(reference, []).append((facts, locator))
        boards.append({"board_id": board_id, "source_bundle": base, "relation": entry["relation"],
                       "aliases": reference_facts.get(board_id, {}).get("aliases", [board_id.replace("_", " ")]),
                       "exact_component_mapping": entry["exact_component_mapping"],
                       "physical_revision_verified": False,
                       "highlight_authority": "requires_detector_identity_and_registered_component_binding"})
        overview = reference_facts.get(board_id)
        if overview:
            for i, fact in enumerate(overview["facts"]):
                if not isinstance(fact["text"], str) or not 1 <= len(fact["text"]) <= 240:
                    raise ValueError("reference fact must be bounded source-reviewed text")
                if not fact["source_url"].startswith("https://"):
                    raise ValueError("reference fact requires a source URL")
                records.append({"record_id": f"{board_id}:overview:{i}", "board_id": board_id,
                    "component_id": f"{board_id}:reference", "reference_designator": "Board reference",
                    "authority": "source_design_not_observed_component", "facts": {"description": fact["text"]},
                    "citation": {**reference_source, "json_pointer": f"/{board_id}/facts/{i}", "url": fact["source_url"]},
                    "relation": entry["relation"], "physical_identity_proven": False, "ar_node_id": None})
        for reference, sections in sorted(grouped.items()):
            component_id = f"{board_id}:{reference}"
            for section, (facts, locator) in enumerate(sections):
                # Distinct schematic units retain their source locators and cannot overwrite each other.
                record = {"record_id": f"{component_id}:source-{section}", "board_id": board_id,
                          "component_id": component_id, "reference_designator": reference,
                          "authority": "source_design_not_observed_component", "facts": facts,
                          "citation": {**citation, **locator}, "relation": entry["relation"],
                          "physical_identity_proven": False, "ar_node_id": None}
                records.append(record)
    corpus = {"schema_version": 1, "dataset_repo": "TokitoAI/tokito-app-dataset", "dataset_commit": revision,
              "boards": boards, "sources": sources, "records": records,
              "license_evidence": license_raw.decode("utf-8"), "distribution_admitted": False}
    corpus["corpus_sha256"] = hashlib.sha256(canonical(corpus)).hexdigest()
    if not records or len(records) > 16384:
        raise ValueError("corpus record count exceeds admission bound")
    return corpus


def vectorize(corpus: dict, artifact: Path, database: Path) -> dict:
    import numpy as np
    import onnxruntime as ort
    import tokenizers
    for filename, key in (("model.onnx", "onnx/model.onnx"), ("vocab.txt", "vocab.txt")):
        if embedding.digest(artifact / filename) != embedding.ARTIFACTS[key]:
            raise ValueError("encoder does not match pinned real model")
    tokenizer_path = artifact.parent / "reference/tokenizer.json"
    if embedding.digest(tokenizer_path) != embedding.ARTIFACTS["tokenizer.json"]:
        raise ValueError("tokenizer integrity mismatch")
    tokenizer = tokenizers.Tokenizer.from_file(str(tokenizer_path))
    embedding.configure_tokenizer(tokenizer)
    options = ort.SessionOptions()
    options.intra_op_num_threads, options.inter_op_num_threads = 2, 1
    session = ort.InferenceSession(str(artifact / "model.onnx"), options, providers=["CPUExecutionProvider"])
    if database.exists():
        raise ValueError("refusing to replace an existing corpus database")
    database.parent.mkdir(parents=True, exist_ok=True)
    descriptor, staging_name = tempfile.mkstemp(prefix="knowledge-", suffix=".sqlite", dir=database.parent)
    os.close(descriptor)
    staging = Path(staging_name)
    # mkstemp's descriptor must not keep the database locked on Windows.
    # Reopen below through SQLite only.
    try:
        return _write_vectors(corpus, database, staging, tokenizer, session, np)
    finally:
        if staging.exists():
            staging.unlink()


def _write_vectors(corpus, database, staging, tokenizer, session, np):
    with closing(sqlite3.connect(staging)) as db, db:
        db.execute("CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
        db.execute("CREATE TABLE records(id TEXT PRIMARY KEY,board_id TEXT NOT NULL,component_id TEXT NOT NULL,source_json TEXT NOT NULL,vector BLOB NOT NULL,vector_sha256 TEXT NOT NULL)")
        db.execute("CREATE INDEX board_scope ON records(board_id)")
        for record in corpus["records"]:
            facts = record["facts"]
            if "bom_columns" in facts:
                salient = facts["bom_columns"]
            else:
                salient = facts
            text = record["reference_designator"] + " " + canonical(salient).decode("utf-8")
            encoded = tokenizer.encode(text)
            feeds = {"input_ids": np.array([encoded.ids], dtype=np.int64),
                     "attention_mask": np.array([encoded.attention_mask], dtype=np.int64),
                     "token_type_ids": np.array([encoded.type_ids], dtype=np.int64)}
            hidden = session.run(["last_hidden_state"], feeds)[0]
            vector = np.asarray(embedding.normalized_pool(hidden, feeds["attention_mask"]), dtype=np.float32).reshape(-1)
            norm = float(np.linalg.norm(vector))
            if vector.size != 384 or not np.isfinite(vector).all() or not np.isfinite(norm) or norm < 1e-8:
                raise ValueError("encoder produced invalid vector")
            vector = vector / norm
            data = struct.pack("<384f", *vector)
            db.execute("INSERT INTO records VALUES(?,?,?,?,?,?)", (record["record_id"], record["board_id"], record["component_id"], canonical(record).decode(), data, hashlib.sha256(data).hexdigest()))
        metadata = {"corpus_sha256": corpus["corpus_sha256"], "dataset_commit": corpus["dataset_commit"],
                    "encoder_id": embedding.MODEL_ID, "encoder_model_sha256": embedding.ARTIFACTS["onnx/model.onnx"],
                    "dimensions": 384, "distribution_admitted": False}
        db.executemany("INSERT INTO metadata VALUES(?,?)", [(k, json.dumps(v)) for k, v in metadata.items()])
    # Hard-link publication is atomic and refuses an existing target. Unlike
    # replace(), a concurrent compiler cannot overwrite an admitted inventory.
    os.link(staging, database)
    return {**metadata, "records": len(corpus["records"]), "database_sha256": embedding.digest(database)}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dataset", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--boards", nargs="+", required=True)
    parser.add_argument("--encoder", type=Path, required=True)
    args = parser.parse_args()
    corpus = compile_corpus(args.dataset.resolve(), args.boards)
    args.output.mkdir(parents=True, exist_ok=True)
    report = vectorize(corpus, args.encoder.resolve(), args.output / "knowledge.sqlite")
    (args.output / "corpus.json").write_bytes(canonical(corpus))
    (args.output / "ingestion.json").write_bytes(canonical(report))
    (args.output / "knowledge.manifest.json").write_bytes(canonical({"schema_version": 1,
        "database_sha256": report["database_sha256"], "corpus_sha256": corpus["corpus_sha256"],
        "encoder_model_sha256": report["encoder_model_sha256"], "boards": corpus["boards"]}))
    print(json.dumps(report, indent=2))
