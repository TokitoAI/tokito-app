#!/usr/bin/env python3
"""Prepare pinned MiniLM FP32 neural payload; never download during app execution.

Requires numpy, onnxruntime==1.27.0 and tokenizers==0.15.2. The tokenizer JSON
is an independent upstream reference, not a shipping Python dependency. Sign
the generated payload with build_model_bundle.py; never put weights in Git.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
import urllib.request

MODEL_ID = "sentence-transformers/all-MiniLM-L6-v2"
REVISION = "1110a243fdf4706b3f48f1d95db1a4f5529b4d41"
ARTIFACTS = {
    "onnx/model.onnx": "6fd5d72fe4589f189f8ebc006442dbb529bb7ce38f8082112682524616046452",
    "vocab.txt": "07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3",
    "tokenizer.json": "be50c3628f2bf5bb5e3a7f17b1f74611b2561a3a27eeab05e5aa30f411572037",
    "README.md": "dcd602d2fd35c203a247304a06fec6654a12f7941b739f9221a064fe8dc3b7f0",
}
CASES = (
    "Which resistor sets the LED current?",
    "A diode permits current primarily in one direction.",
    "An inductor stores energy in its magnetic field.",
    "Café naïve résumé: +3.3V / USB-C (R1), 10µF!",
    "电阻 电压 接地",
    "Reset\tcontroller\nstatus\u0000ready 😀 café",
    "A\u0301lpha coöperate Straße ﬁlter",
    "resistor " * 300,
)


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def fetch(name: str, target: Path) -> None:
    expected = ARTIFACTS[name]
    if target.exists():
        if digest(target) != expected:
            raise ValueError(f"cached artifact digest mismatch: {name}")
        return
    target.parent.mkdir(parents=True, exist_ok=True)
    partial = target.with_name(target.name + ".partial")
    url = f"https://huggingface.co/{MODEL_ID}/resolve/{REVISION}/{name}"
    try:
        with urllib.request.urlopen(url, timeout=90) as response, partial.open("wb") as out:
            while block := response.read(1024 * 1024):
                out.write(block)
        if digest(partial) != expected:
            raise ValueError(f"downloaded artifact digest mismatch: {name}")
        partial.replace(target)
    finally:
        partial.unlink(missing_ok=True)


def write_json(path: Path, data: object) -> None:
    path.write_text(json.dumps(data, sort_keys=True, separators=(",", ":"), ensure_ascii=False) + "\n", encoding="utf-8")


def configure_tokenizer(tokenizer) -> None:
    # Upstream JSON enables 128-token fixed padding, but Android uses dynamic
    # [1, seq]. Keeping padded ids with an all-ones native mask changes vectors.
    tokenizer.no_padding()
    tokenizer.enable_truncation(max_length=256)


def normalized_pool(hidden, mask):
    # Stdlib reference math is intentionally independent of ONNX/PyTorch.
    hidden = hidden.tolist() if hasattr(hidden, "tolist") else hidden
    mask = mask.tolist() if hasattr(mask, "tolist") else mask
    if len(hidden) != 1 or len(mask) != 1 or not mask[0] or len(hidden[0]) != len(mask[0]):
        raise ValueError("unexpected embedding output")
    if any(len(token) != 384 or any(not math.isfinite(float(value)) for value in token) for token in hidden[0]):
        raise ValueError("unexpected embedding output")
    if any(value not in (0, 1) for value in mask[0]):
        raise ValueError("invalid embedding attention mask")
    count = sum(mask[0])
    if count <= 0:
        raise ValueError("empty embedding attention mask")
    vector = [math.fsum(float(token[index]) * flag for token, flag in zip(hidden[0], mask[0])) / count
              for index in range(384)]
    norm = math.sqrt(math.fsum(value * value for value in vector))
    if not math.isfinite(norm) or norm < 1e-12:
        raise ValueError("invalid embedding norm")
    return [value / norm for value in vector]


def prepare(output: Path) -> dict:
    import numpy as np
    import onnxruntime as ort
    import tokenizers
    if ort.__version__ != "1.27.0" or tokenizers.__version__ != "0.15.2":
        raise ValueError("reference dependencies must be onnxruntime 1.27.0 / tokenizers 0.15.2")
    output = output.resolve()
    payload, reference = output / "payload", output / "reference"
    payload.mkdir(parents=True, exist_ok=True)
    reference.mkdir(parents=True, exist_ok=True)
    for name in ARTIFACTS:
        target = payload / Path(name).name if name in ("onnx/model.onnx", "vocab.txt") else reference / name
        fetch(name, target)
    tokenizer = tokenizers.Tokenizer.from_file(str(reference / "tokenizer.json"))
    configure_tokenizer(tokenizer)
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    session = ort.InferenceSession(str(payload / "model.onnx"), options, providers=["CPUExecutionProvider"])
    inputs = {item.name for item in session.get_inputs()}
    if inputs != {"input_ids", "attention_mask", "token_type_ids"}:
        raise ValueError(f"unexpected ONNX inputs: {sorted(inputs)}")
    if "last_hidden_state" not in {item.name for item in session.get_outputs()}:
        raise ValueError("ONNX lacks last_hidden_state")
    cases = []
    for text in CASES:
        encoded = tokenizer.encode(text)
        feeds = {"input_ids": np.array([encoded.ids], dtype=np.int64),
                 "attention_mask": np.array([encoded.attention_mask], dtype=np.int64),
                 "token_type_ids": np.array([encoded.type_ids], dtype=np.int64)}
        hidden = session.run(["last_hidden_state"], feeds)[0]
        vector = np.asarray(normalized_pool(hidden, feeds["attention_mask"]), dtype=np.float32)
        cases.append({"text": text, "token_ids": encoded.ids, "vector": vector.tolist()})
    config = {"schema_version": 1, "model_id": MODEL_ID,
              "model_sha256": ARTIFACTS["onnx/model.onnx"], "vocab_sha256": ARTIFACTS["vocab.txt"],
              "max_tokens": 256, "dimensions": 384, "backend": "onnx_cpu",
              "pooling": "masked_mean_l2", "lowercase": True, "strip_accents": True}
    write_json(payload / "embedding.config.json", config)
    write_json(payload / "conformance.json", {"schema_version": 1, "cases": cases})
    report = {"schema_version": 1, "model_id": MODEL_ID, "source_revision": REVISION,
              "license": "apache-2.0", "source_url": f"https://huggingface.co/{MODEL_ID}/tree/{REVISION}",
              "source_artifacts": ARTIFACTS, "onnxruntime": ort.__version__, "tokenizers": tokenizers.__version__,
              "numpy": np.__version__, "backend": session.get_providers(), "precision": "fp32",
              "conformance_cases": len(cases), "payload": {
                  path.name: {"sha256": digest(path), "size_bytes": path.stat().st_size}
                  for path in sorted(payload.iterdir()) if path.is_file()}}
    write_json(output / "embedding.provenance.json", report)
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path, help="artifact directory outside the checkout")
    arguments = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    if arguments.output.resolve().is_relative_to(repo):
        parser.error("model artifacts must remain outside the repository")
    print(json.dumps(prepare(arguments.output), indent=2))
