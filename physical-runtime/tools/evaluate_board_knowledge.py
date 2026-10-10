"""Inspect actual corpus retrieval; this does not qualify Qwen or device audio."""
import argparse
import json
import sqlite3
import struct
from pathlib import Path
import numpy as np
import onnxruntime as ort
import tokenizers
import prepare_embedding_bundle as embedding

parser = argparse.ArgumentParser()
parser.add_argument("database", type=Path)
parser.add_argument("encoder", type=Path)
args = parser.parse_args()
for filename, key in (("model.onnx", "onnx/model.onnx"), ("vocab.txt", "vocab.txt")):
    assert embedding.digest(args.encoder / filename) == embedding.ARTIFACTS[key], "Encoder integrity mismatch"
assert embedding.digest(args.encoder.parent / "reference/tokenizer.json") == embedding.ARTIFACTS["tokenizer.json"]
tokenizer = tokenizers.Tokenizer.from_file(str(args.encoder.parent / "reference/tokenizer.json"))
embedding.configure_tokenizer(tokenizer)
options = ort.SessionOptions()
options.intra_op_num_threads = 2
options.inter_op_num_threads = 1
session = ort.InferenceSession(str(args.encoder / "model.onnx"), options, providers=["CPUExecutionProvider"])
with sqlite3.connect(f"file:{args.database.as_posix()}?mode=ro", uri=True) as db:
    cases = [("esp32_board", "What is ESP32?"), ("esp8266_board", "What is ESP8266?"),
             ("nxp_frdm_mcxn236_board", "What is FRDM MCXN236?"), ("ultimainboard", "What is Ultimainboard?")]
    results = []
    for board, question in cases:
        encoded = tokenizer.encode(question)
        feeds = {"input_ids": np.array([encoded.ids], dtype=np.int64),
                 "attention_mask": np.array([encoded.attention_mask], dtype=np.int64),
                 "token_type_ids": np.array([encoded.type_ids], dtype=np.int64)}
        hidden = session.run(["last_hidden_state"], feeds)[0]
        vector = np.asarray(embedding.normalized_pool(hidden, feeds["attention_mask"]), dtype=np.float32).reshape(-1)
        top = sorted([(float(np.dot(vector, np.array(struct.unpack("<384f", blob)))), identifier)
                      for identifier, blob in db.execute("SELECT id,vector FROM records WHERE board_id=?", (board,))], reverse=True)[:8]
        assert any(":overview:" in identifier for _, identifier in top), (board, top)
        results.append({"board_id": board, "question": question, "top": top})
    print(json.dumps(results, indent=2))
