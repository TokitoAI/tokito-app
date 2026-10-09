#!/usr/bin/env python3
"""Tests for voice-bundle assembly; the digest scheme must match the
Android ModelInventory exactly or the bundle fails closed on-device."""

from __future__ import annotations

import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from build_voice_bundle import assemble, inventory_digest, write_manifest


def make_sources(root: Path) -> tuple[Path, Path]:
    stt = root / "stt-src"
    tts = root / "tts-src"
    stt.mkdir()
    tts.mkdir()
    (stt / "encoder-epoch-99-avg-1.int8.onnx").write_bytes(b"ENC8")
    (stt / "encoder-epoch-99-avg-1.onnx").write_bytes(b"ENC32")
    (stt / "decoder-epoch-99-avg-1.onnx").write_bytes(b"DEC")
    (stt / "joiner-epoch-99-avg-1.int8.onnx").write_bytes(b"JOIN8")
    (stt / "tokens.txt").write_text("a\nb\n")
    (tts / "voice-amy.onnx").write_bytes(b"VITS")
    (tts / "tokens.txt").write_text("x\n")
    (tts / "espeak-ng-data").mkdir()
    (tts / "espeak-ng-data" / "phondata").write_bytes(b"PH")
    return stt, tts


class InventoryDigestTest(unittest.TestCase):
    def test_digest_is_sha256_of_sorted_lines(self) -> None:
        entries = [
            {"path": "b.bin", "size": 2, "sha256": "bb"},
            {"path": "a.bin", "size": 1, "sha256": "aa"},
        ]
        expected = hashlib.sha256(b"a.bin:1:aa\nb.bin:2:bb").hexdigest()
        self.assertEqual(inventory_digest(entries), expected)

    def test_order_independent(self) -> None:
        entries = [{"path": "a", "size": 1, "sha256": "aa"}, {"path": "b", "size": 2, "sha256": "bb"}]
        self.assertEqual(inventory_digest(entries), inventory_digest(list(reversed(entries))))


class AssembleTest(unittest.TestCase):
    def test_plain_names_are_supported(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            stt, tts = make_sources(root)
            for file in stt.glob('*.onnx'):
                if '.int8.' in file.name:
                    file.unlink()
                else:
                    file.rename(stt / (file.name.split('-')[0] + '.onnx'))
            (stt / 'joiner.onnx').write_bytes(b'JOIN')
            assemble(stt, tts, root / 'bundle')
            self.assertEqual((root / 'bundle/stt/encoder.onnx').read_bytes(), b'ENC32')

    def test_ambiguous_models_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            stt, tts = make_sources(root)
            (stt / 'encoder-other.int8.onnx').write_bytes(b'OTHER')
            with self.assertRaisesRegex(ValueError, 'ambiguous encoder'):
                assemble(stt, tts, root / 'bundle')

    def test_bundle_layout_config_and_manifest(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            stt, tts = make_sources(root)
            result = assemble(stt, tts, root / "bundle")
            bundle = root / "bundle"

            # int8 encoder/joiner preferred, fp32 decoder kept
            self.assertEqual((bundle / "stt/encoder.onnx").read_bytes(), b"ENC8")
            self.assertEqual((bundle / "stt/decoder.onnx").read_bytes(), b"DEC")
            self.assertEqual((bundle / "stt/joiner.onnx").read_bytes(), b"JOIN8")

            config = json.loads((bundle / "voice-runtime.json").read_text())
            self.assertEqual(config["stt"]["engine"], "sherpa-onnx-online-transducer")
            self.assertEqual(config["stt"]["model_type"], "auto")
            self.assertEqual(config["tts"]["engine"], "sherpa-onnx-vits")
            self.assertEqual(config["tts"]["data_dir"], "tts/espeak-ng-data")
            self.assertGreaterEqual(config["tts"]["speaker_id"], 0)

            manifest = json.loads((bundle / "voice-models.manifest.json").read_text())
            self.assertEqual(manifest["schema_version"], 1)
            self.assertEqual(manifest["runtime_version"], "1.13.6")
            paths = {entry["path"] for entry in manifest["files"]}
            # the config is inventoried; the manifest itself is not
            self.assertIn("voice-runtime.json", paths)
            self.assertNotIn("voice-models.manifest.json", paths)
            self.assertIn("tts/espeak-ng-data/phondata", paths)
            self.assertEqual(manifest["inventory_sha256"], inventory_digest(manifest["files"]))
            self.assertEqual(result["files"], len(manifest["files"]))

    def test_missing_espeak_data_fails(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            stt, tts = make_sources(root)
            (tts / "espeak-ng-data" / "phondata").unlink()
            (tts / "espeak-ng-data").rmdir()
            with self.assertRaises(FileNotFoundError):
                assemble(stt, tts, root / "bundle")

    def test_manifest_regenerates_deterministically(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            stt, tts = make_sources(root)
            assemble(stt, tts, root / "bundle")
            first = json.loads((root / "bundle" / "voice-models.manifest.json").read_text())
            second = write_manifest(root / "bundle")
            self.assertEqual(first["inventory_sha256"], second["inventory_sha256"])


if __name__ == "__main__":
    unittest.main()
