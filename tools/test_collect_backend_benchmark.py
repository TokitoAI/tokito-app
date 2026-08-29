from __future__ import annotations

import subprocess
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))

from collect_backend_benchmark import BenchmarkError, collect


def config() -> dict[str, object]:
    return {
        "schema_version": "benchmark.v1",
        "device_profile": "profile-revision",
        "abi": "declared-abi",
        "candidate_id": "candidate-revision",
        "command": ["candidate-command"],
    }


def result(*, fallback: bool = False) -> bytes:
    return (
        b'{"correctness":{"passed":true},"latency":{"cold_ms":1,"warm_ms":1},'
        b'"memory":{"peak_bytes":1},"fallback":{"silent_cpu_fallback_detected":'
        + (b"true" if fallback else b"false")
        + b'},"lifecycle":{"passed":true},"thermal":{"passed":true}}'
    )


class CollectBenchmarkTest(unittest.TestCase):
    @patch("collect_backend_benchmark.subprocess.run")
    def test_collects_complete_evidence(self, run: unittest.mock.Mock) -> None:
        run.return_value = subprocess.CompletedProcess(["candidate-command"], 0, result(), b"")
        report = collect(config())
        self.assertEqual(report["candidate_id"], "candidate-revision")
        self.assertTrue(report["result_digest"].startswith("sha256:"))

    @patch("collect_backend_benchmark.subprocess.run")
    def test_rejects_silent_fallback(self, run: unittest.mock.Mock) -> None:
        run.return_value = subprocess.CompletedProcess(["candidate-command"], 0, result(fallback=True), b"")
        with self.assertRaisesRegex(BenchmarkError, "silent CPU fallback"):
            collect(config())

    @patch("collect_backend_benchmark.subprocess.run")
    def test_rejects_candidate_command_failures(self, run: unittest.mock.Mock) -> None:
        run.return_value = subprocess.CompletedProcess(["candidate-command"], 1, b"", b"")
        with self.assertRaisesRegex(BenchmarkError, "exited with 1"):
            collect(config())


if __name__ == "__main__":
    unittest.main()
