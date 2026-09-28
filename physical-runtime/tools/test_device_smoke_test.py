#!/usr/bin/env python3
"""Tests for the device smoke test's verdict logic (no device required)."""

from __future__ import annotations

import unittest
from unittest import mock

import device_smoke_test
from device_smoke_test import smoke_test


def fake_run_factory(pid: str, log: str):
    def fake_run(adb, *args, check=True):
        if "pidof" in args:
            return pid
        if args and args[0] == "logcat" and "-d" in args:
            return log
        if "getprop" in args:
            return "I2501" if "model" in args[-1] else "16"
        return ""

    return fake_run


class SmokeTestVerdictTest(unittest.TestCase):
    def _run(self, pid: str, log: str) -> dict:
        with mock.patch.object(device_smoke_test, "run", fake_run_factory(pid, log)), mock.patch(
            "time.sleep"
        ):
            return smoke_test("adb", "dev.tokito.physical", 0.0, None)

    def test_alive_and_clean_log_passes(self) -> None:
        report = self._run("12345", "I tokito: started\n")
        self.assertEqual(report["verdict"], "PASS")
        self.assertTrue(report["process_alive"])
        # a pass is explicitly an integration result, never a quality claim
        self.assertIn("not a detection-quality claim", report["meaning"])

    def test_dead_process_fails(self) -> None:
        report = self._run("", "nothing notable\n")
        self.assertEqual(report["verdict"], "FAIL")

    def test_missing_vision_bundle_detected(self) -> None:
        report = self._run("12345", "E TokitoPoseVision: local vision bundle is not installed\n")
        self.assertEqual(report["verdict"], "FAIL")
        self.assertIn("vision_bundle_missing", [f["marker"] for f in report["findings"]])

    def test_context_mismatch_detected(self) -> None:
        report = self._run("12345", "asset and assembly context revisions differ\n")
        self.assertEqual(report["verdict"], "FAIL")
        self.assertIn("twin_context_mismatch", [f["marker"] for f in report["findings"]])

    def test_digest_mismatch_detected(self) -> None:
        report = self._run("12345", "GLB digest does not match manifest\n")
        self.assertIn("twin_digest_mismatch", [f["marker"] for f in report["findings"]])

    def test_crash_detected(self) -> None:
        report = self._run("12345", "FATAL EXCEPTION: main dev.tokito.physical crashed\n")
        self.assertEqual(report["verdict"], "FAIL")
        self.assertIn("app_crash", [f["marker"] for f in report["findings"]])


if __name__ == "__main__":
    unittest.main()
