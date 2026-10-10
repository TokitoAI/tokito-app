#!/usr/bin/env python3
"""Compare exported ONNX vectors with pinned official safetensors on CPU.

Run after prepare_embedding_bundle.py. Reference weights stay outside Git and
are never installed on the phone. No remote model code or pickle is loaded.
"""
import argparse
import json
from pathlib import Path

import prepare_embedding_bundle as bundle

REFERENCE = {
    "model.safetensors": "53aa51172d142c89d9012cce15ae4d6cc0ca6895895114379cacb4fab128d9db",
    "config.json": "953f9c0d463486b10a6871cc2fd59f223b2c70184f49815e7efbcab5d8908b41",
}


def verify(root: Path) -> dict:
    import numpy as np
    import torch
    import transformers
    from transformers import AutoModel
    reference = root / "reference"
    for name, expected in REFERENCE.items():
        bundle.ARTIFACTS[name] = expected
        bundle.fetch(name, reference / name)
    model = AutoModel.from_pretrained(reference, local_files_only=True, use_safetensors=True, trust_remote_code=False)
    model.eval()
    torch.set_num_threads(2)
    cases = json.loads((root / "payload" / "conformance.json").read_text(encoding="utf-8"))["cases"]
    metrics = []
    for index, case in enumerate(cases):
        ids = torch.tensor([case["token_ids"]], dtype=torch.long)
        mask = torch.ones_like(ids)
        with torch.no_grad():
            hidden = model(input_ids=ids, attention_mask=mask, token_type_ids=torch.zeros_like(ids)).last_hidden_state
        actual = np.asarray(bundle.normalized_pool(hidden.cpu().numpy(), mask.numpy()), dtype=np.float32)
        expected = np.array(case["vector"], dtype=np.float32)
        max_error = float(np.max(np.abs(actual - expected)))
        cosine = float(np.dot(actual, expected) / (np.linalg.norm(actual) * np.linalg.norm(expected)))
        if max_error > .002 or cosine < .999:
            raise ValueError(f"ONNX export failed independent PyTorch vector conformance: case={index}, max_abs_error={max_error:.8f}, cosine={cosine:.8f}")
        metrics.append({"input_tokens": len(case["token_ids"]), "max_abs_error": max_error, "cosine": cosine})
    report = {"schema_version": 1, "source_revision": bundle.REVISION, "reference_artifacts": REFERENCE,
              "torch": torch.__version__, "transformers": transformers.__version__, "device": "cpu",
              "tolerances": {"max_abs_error": .002, "minimum_cosine": .999}, "cases": metrics}
    bundle.write_json(root / "embedding.export-conformance.json", report)
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifact_directory", type=Path)
    args = parser.parse_args()
    if args.artifact_directory.resolve().is_relative_to(Path(__file__).resolve().parents[2]):
        parser.error("model artifacts must remain outside the repository")
    print(json.dumps(verify(args.artifact_directory.resolve()), indent=2))
