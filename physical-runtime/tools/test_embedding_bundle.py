"""Contract tests for reproducible neural embedding payload preparation."""
import hashlib
import io
import math
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, call, patch

import prepare_embedding_bundle as bundle


class EmbeddingBundleTest(unittest.TestCase):
    def test_revision_and_all_digests_are_immutable(self):
        self.assertRegex(bundle.REVISION, r"^[0-9a-f]{40}$")
        self.assertEqual(set(bundle.ARTIFACTS), {"onnx/model.onnx", "vocab.txt", "tokenizer.json", "README.md"})
        for digest in bundle.ARTIFACTS.values():
            self.assertRegex(digest, r"^[0-9a-f]{64}$")

    def test_corrupt_existing_artifact_never_downloads_over_it(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory, "vocab.txt")
            target.write_bytes(b"untrusted")
            with patch("urllib.request.urlopen") as request:
                with self.assertRaisesRegex(ValueError, "cached artifact digest mismatch"):
                    bundle.fetch("vocab.txt", target)
                request.assert_not_called()

    def test_cached_hash_match_never_needs_network(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory, "vocab.txt")
            target.write_bytes(b"verified")
            with patch.dict(bundle.ARTIFACTS, {"vocab.txt": hashlib.sha256(b"verified").hexdigest()}):
                with patch("urllib.request.urlopen") as request:
                    bundle.fetch("vocab.txt", target)
                    request.assert_not_called()

    def test_download_digest_mismatch_removes_partial_and_never_activates(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory, "vocab.txt")
            with patch("urllib.request.urlopen", return_value=io.BytesIO(b"tampered")):
                with self.assertRaisesRegex(ValueError, "downloaded artifact digest mismatch"):
                    bundle.fetch("vocab.txt", target)
            self.assertFalse(target.exists())
            self.assertFalse(target.with_name("vocab.txt.partial").exists())

    def test_download_pins_revision_and_hash_before_atomic_replacement(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory, "vocab.txt")
            with patch.dict(bundle.ARTIFACTS, {"vocab.txt": hashlib.sha256(b"valid").hexdigest()}):
                with patch("urllib.request.urlopen", return_value=io.BytesIO(b"valid")) as request:
                    bundle.fetch("vocab.txt", target)
                    self.assertIn(f"/resolve/{bundle.REVISION}/vocab.txt", request.call_args.args[0])
            self.assertEqual(target.read_bytes(), b"valid")

    def test_corpus_covers_unicode_and_true_truncation(self):
        self.assertEqual(len(bundle.CASES), 8)
        self.assertTrue(any("电阻" in case for case in bundle.CASES))
        self.assertTrue(any("\u0000" in case for case in bundle.CASES))
        self.assertTrue(any(len(case.split()) > 256 for case in bundle.CASES))

    def test_reference_tokenizer_disables_upstream_padding(self):
        tokenizer = Mock()
        bundle.configure_tokenizer(tokenizer)
        self.assertEqual(tokenizer.mock_calls, [call.no_padding(), call.enable_truncation(max_length=256)])

    def test_masked_pool_ignores_padding_and_normalizes(self):
        hidden = [[[3, 4] + [0] * 382, [999] * 384]]
        vector = bundle.normalized_pool(hidden, [[1, 0]])
        self.assertAlmostEqual(float(vector[0]), .6, places=6)
        self.assertAlmostEqual(float(vector[1]), .8, places=6)
        self.assertAlmostEqual(math.sqrt(sum(value * value for value in vector)), 1, places=6)

    def test_invalid_outputs_fail_closed(self):
        for hidden in ([[[0] * 384] * 2], [[[math.nan] * 384] * 2], [[[1] * 383] * 2]):
            with self.assertRaises(ValueError):
                bundle.normalized_pool(hidden, [[1, 1]])


if __name__ == "__main__":
    unittest.main()
