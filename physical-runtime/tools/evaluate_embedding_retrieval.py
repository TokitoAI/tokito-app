#!/usr/bin/env python3
"""Frozen held-out semantic retrieval evaluation, not production board admission.

Executes the real pinned neural model on CPU. .60 similarity/.08 ambiguity
margin mirror production without tuning. Identity/pin guard probes report raw
semantic risks separately: this host tool does not execute Kotlin guards or
prove physical-device latency. All raw scores and abstentions remain visible.
"""
from __future__ import annotations

import argparse
from collections import Counter
import json
import math
from pathlib import Path
import time

import prepare_embedding_bundle as bundle

MINIMUM_SIMILARITY = .60
MINIMUM_MARGIN = .08
FIXTURES = Path(__file__).with_name("embedding_retrieval_fixtures.json")


def validate_fixture(document: dict) -> None:
    if document.get("schema_version") != 1 or not document.get("revision"):
        raise ValueError("fixture schema/revision required")
    ids = set()
    if not document.get("corpora"):
        raise ValueError("nonempty evaluation corpora required")
    for corpus in document["corpora"]:
        if corpus["id"] in ids:
            raise ValueError("duplicate corpus")
        ids.add(corpus["id"])
        records = {record["id"] for record in corpus["records"]}
        if len(records) != len(corpus["records"]) or len(records) < 2:
            raise ValueError("unique competing records required")
        query_ids = set()
        for record in corpus["records"]:
            if record["source"] not in document["sources"] or not record["text"] or len(record["text"]) > 1500:
                raise ValueError("record must retain bounded source-reviewed text")
        for query in corpus["queries"]:
            if query["id"] in query_ids or not query["text"] or len(query["text"]) > 1000:
                raise ValueError("unique bounded query required")
            query_ids.add(query["id"])
            if query["expected"] is not None and query["expected"] not in records:
                raise ValueError("expected record absent")
            if query["category"] not in {"paraphrase", "unrelated", "ambiguous", "guard_unknown_reference", "guard_named_reference", "guard_ambiguous_pin", "guard_named_pin", "guard_missing_pin"}:
                raise ValueError("unknown query category")
        semantic = [query for query in corpus["queries"] if not query["category"].startswith("guard_")]
        if not any(query["expected"] is not None for query in semantic) or not any(query["expected"] is None for query in semantic):
            raise ValueError("every corpus requires semantic positives and negatives")
        for query in corpus["queries"]:
            if query["category"] in {"unrelated", "ambiguous", "guard_unknown_reference", "guard_ambiguous_pin", "guard_missing_pin"} and query["expected"] is not None:
                raise ValueError("negative category cannot declare a positive label")
            if query["category"] in {"paraphrase", "guard_named_reference", "guard_named_pin"} and query["expected"] is None:
                raise ValueError("positive category requires an expected record")


def decide(ranked: list[dict]) -> tuple[str | None, str]:
    if not ranked or any(not math.isfinite(item["similarity"]) for item in ranked):
        raise ValueError("finite competing scores required")
    best = ranked[0]
    if best["similarity"] < MINIMUM_SIMILARITY:
        return None, "below_similarity"
    if len(ranked) > 1 and best["similarity"] - ranked[1]["similarity"] < MINIMUM_MARGIN:
        return None, "ambiguous_margin"
    return best["record_id"], "semantic_selected"


def classify(expected: str | None, selected: str | None) -> str:
    if expected is None:
        return "correct_abstention" if selected is None else "false_match"
    if selected is None:
        return "positive_abstention"
    return "correct_match" if selected == expected else "wrong_match"


def evaluate(artifact: Path, report_path: Path) -> dict:
    import numpy as np
    import onnxruntime as ort
    import tokenizers
    document = json.loads(FIXTURES.read_text(encoding="utf-8"))
    validate_fixture(document)
    artifact = artifact.resolve()
    config = json.loads((artifact / "embedding.config.json").read_text(encoding="utf-8"))
    for filename, expected in (("model.onnx", bundle.ARTIFACTS["onnx/model.onnx"]), ("vocab.txt", bundle.ARTIFACTS["vocab.txt"])):
        if bundle.digest(artifact / filename) != expected:
            raise ValueError("evaluation model differs from pinned admitted artifact")
    if config["model_id"] != bundle.MODEL_ID or config["max_tokens"] != 256 or config["dimensions"] != 384:
        raise ValueError("unexpected admitted model configuration")
    tokenizer_path = artifact.parent / "reference" / "tokenizer.json"
    if bundle.digest(tokenizer_path) != bundle.ARTIFACTS["tokenizer.json"]:
        raise ValueError("reference tokenizer digest mismatch")
    tokenizer = tokenizers.Tokenizer.from_file(str(tokenizer_path))
    bundle.configure_tokenizer(tokenizer)
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    started = time.perf_counter()
    session = ort.InferenceSession(str(artifact / "model.onnx"), options, providers=["CPUExecutionProvider"])
    cold_load_ms = (time.perf_counter() - started) * 1000

    def encode(text: str):
        start = time.perf_counter()
        encoded = tokenizer.encode(text)
        feeds = {"input_ids": np.array([encoded.ids], dtype=np.int64),
                 "attention_mask": np.array([encoded.attention_mask], dtype=np.int64),
                 "token_type_ids": np.array([encoded.type_ids], dtype=np.int64)}
        hidden = session.run(["last_hidden_state"], feeds)[0]
        vector = np.asarray(bundle.normalized_pool(hidden, feeds["attention_mask"]), dtype=np.float32)
        norm = math.sqrt(math.fsum(float(value) * float(value) for value in vector))
        vector = np.asarray([float(value) / norm for value in vector], dtype=np.float32)
        return vector, (time.perf_counter() - start) * 1000, len(encoded.ids)

    raw = []
    record_latencies = []
    counts = Counter()
    guard_counts = Counter()
    for corpus in document["corpora"]:
        records = sorted(corpus["records"], key=lambda record: record["id"])
        vectors = []
        for record in records:
            vector, elapsed, _ = encode(record["text"])
            vectors.append(vector)
            record_latencies.append(elapsed)
        for query in corpus["queries"]:
            vector, elapsed, tokens = encode(query["text"])
            ranked = [{"record_id": record["id"], "similarity": math.fsum(float(a) * float(b) for a, b in zip(vector, other))}
                      for record, other in zip(records, vectors)]
            ranked.sort(key=lambda item: (-item["similarity"], item["record_id"]))
            selected, reason = decide(ranked)
            outcome = classify(query["expected"], selected)
            guard = query["category"].startswith("guard_")
            if guard:
                guard_counts[outcome] += 1
            else:
                counts[outcome] += 1
            raw.append({"corpus_id": corpus["id"], "context_revision": corpus["context_revision"],
                        **query, "raw_semantic_selection": selected, "decision_reason": reason,
                        "semantic_outcome": outcome, "requires_identity_guard": guard,
                        "identity_guard_execution": "not_executed_host_evaluation" if guard else None,
                        "ranked_scores": ranked, "margin": ranked[0]["similarity"] - ranked[1]["similarity"],
                        "host_query_latency_ms": elapsed, "input_tokens": tokens})
    positive_total = counts["correct_match"] + counts["wrong_match"] + counts["positive_abstention"]
    negative_total = counts["correct_abstention"] + counts["false_match"]
    selected_total = counts["correct_match"] + counts["wrong_match"] + counts["false_match"]
    raw_positive_top1 = sum(item["ranked_scores"][0]["record_id"] == item["expected"] for item in raw
                            if not item["requires_identity_guard"] and item["expected"] is not None)
    report = {"schema_version": 1, "status": "evaluation_complete_not_a_release_qualification",
              "fixture_revision": document["revision"], "fixture_sha256": bundle.digest(FIXTURES),
              "scope": document["scope"], "model_id": bundle.MODEL_ID, "model_revision": bundle.REVISION,
              "model_sha256": bundle.digest(artifact / "model.onnx"), "conformance_sha256": bundle.digest(artifact / "conformance.json"),
              "configuration": {"minimum_similarity": MINIMUM_SIMILARITY, "minimum_margin": MINIMUM_MARGIN,
                                "onnxruntime": ort.__version__, "backend": session.get_providers(), "threads": 2,
                                "host_only": True, "output_dimensions": 384},
              "summary": {"corpora": len(document["corpora"]), "records": sum(len(c["records"]) for c in document["corpora"]),
                          "queries": len(raw), "semantic_queries": sum(counts.values()), "semantic_outcomes": dict(counts),
                          "positive_queries": positive_total, "negative_queries": negative_total,
                          "positive_recall": counts["correct_match"] / positive_total,
                          "raw_positive_top1_correct": raw_positive_top1,
                          "raw_positive_top1_accuracy": raw_positive_top1 / positive_total,
                          "selected_precision": counts["correct_match"] / selected_total if selected_total else None,
                          "negative_false_match_rate": counts["false_match"] / negative_total,
                          "guard_probe_raw_semantic_outcomes": dict(guard_counts),
                          "guard_probes": sum(guard_counts.values()), "guard_probes_are_not_production_guard_tests": True},
              "host_timing": {"cold_load_ms": cold_load_ms, "record_latencies_ms": record_latencies}, "raw_results": raw}
    bundle.write_json(report_path, report)
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("payload", type=Path)
    parser.add_argument("report", type=Path)
    args = parser.parse_args()
    if args.report.resolve().is_relative_to(Path(__file__).resolve().parents[2]):
        parser.error("raw evaluation reports remain outside Git")
    args.report.parent.mkdir(parents=True, exist_ok=True)
    result = evaluate(args.payload, args.report)
    print(json.dumps(result["summary"], indent=2))
