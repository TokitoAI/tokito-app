"""Host conformance only. Native Android uses the existing vendor tokenizer C ABI."""
import argparse
import hashlib
import json
import struct
from pathlib import Path

def verify(tokenizer_path: Path, fixture_path: Path) -> int:
    import tokenizers
    fixture = json.loads(fixture_path.read_text(encoding="utf-8"))
    if tokenizers.__version__ != fixture["reference_tokenizers"]:
        raise ValueError("Reference tokenizers version mismatch")
    if hashlib.sha256(tokenizer_path.read_bytes()).hexdigest() != fixture["tokenizer_sha256"]:
        raise ValueError("Generator tokenizer digest mismatch")
    tokenizer = tokenizers.Tokenizer.from_file(str(tokenizer_path))
    for case in fixture["cases"]:
        ids = tokenizer.encode(case["text"], add_special_tokens=False).ids
        digest = hashlib.sha256(b"".join(struct.pack("<i", token) for token in ids)).hexdigest()
        if len(ids) != case["count"] or digest != case["ids_sha256"]:
            raise ValueError("Exact Qwen reference token IDs mismatch")
    return len(fixture["cases"])

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("tokenizer", type=Path)
    parser.add_argument("fixture", type=Path)
    args = parser.parse_args()
    print(f"PASS {verify(args.tokenizer, args.fixture)} exact Qwen tokenizer cases")
