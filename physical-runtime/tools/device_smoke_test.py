#!/usr/bin/env python3
"""Launch the app on a connected device and report what the runtime actually
accepted or rejected -- the repeatable smoke test behind a release.

Provisioning can succeed byte-for-byte and the product still fail silently:
a manifest bound to the wrong context revision is rejected as
CONTEXT_MISMATCH with no dialog, a missing vision bundle only shows up as a
logcat error, and the 3D layer simply stays invisible. This tool launches
the app cold, watches logcat for the runtime's own acceptance/rejection
markers, confirms the process stayed alive, and captures a screenshot for
the human record.

It reports what happened; it does not decide detection quality. A PASS here
means "the runtime admitted the provisioned assets and the app is running",
nothing more.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import time
from pathlib import Path
from typing import Any

PACKAGE = "dev.tokito.physical"
ACTIVITY = f"{PACKAGE}/.MainActivity"

# logcat markers with their meaning; every terminal state is listed so
# silence is distinguishable from success
MARKERS = [
    (re.compile(r"local vision bundle is not installed"), "vision_bundle_missing", "FAIL"),
    (re.compile(r"vision model digest mismatch"), "vision_digest_mismatch", "FAIL"),
    (re.compile(r"Local vision bundle was rejected"), "vision_bundle_rejected", "FAIL"),
    (re.compile(r"CONTEXT_MISMATCH|context revisions differ"), "twin_context_mismatch", "FAIL"),
    (re.compile(r"DIGEST_MISMATCH|digest does not match"), "twin_digest_mismatch", "FAIL"),
    (re.compile(r"INVALID_GLB|not a complete GLB"), "twin_invalid_glb", "FAIL"),
    (re.compile(r"FATAL EXCEPTION.*" + re.escape(PACKAGE)), "app_crash", "FAIL"),
]


def run(adb: str, *args: str, check: bool = True) -> str:
    completed = subprocess.run([adb, *args], capture_output=True, text=True, encoding="utf-8", errors="replace")
    if check and completed.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed: {completed.stderr.strip() or completed.stdout.strip()}")
    return completed.stdout


def smoke_test(adb: str, package: str, settle_seconds: float, screenshot: Path | None) -> dict[str, Any]:
    device = run(adb, "shell", "getprop", "ro.product.model").strip()
    android = run(adb, "shell", "getprop", "ro.build.version.release").strip()

    run(adb, "shell", "am", "force-stop", package, check=False)
    run(adb, "logcat", "-c", check=False)
    run(adb, "shell", "am", "start", "-n", ACTIVITY)
    time.sleep(settle_seconds)

    pid = run(adb, "shell", "pidof", package, check=False).strip()
    log = run(adb, "logcat", "-d", "-t", "1500", check=False)

    findings = []
    for pattern, name, severity in MARKERS:
        if pattern.search(log):
            findings.append({"marker": name, "severity": severity})

    if screenshot is not None:
        raw = subprocess.run([adb, "exec-out", "screencap", "-p"], capture_output=True)
        if raw.returncode == 0 and raw.stdout[:8].startswith(b"\x89PNG"):
            screenshot.write_bytes(raw.stdout)

    alive = bool(pid)
    failures = [f for f in findings if f["severity"] == "FAIL"]
    return {
        "device": device,
        "android": android,
        "process_alive": alive,
        "pid": pid or None,
        "findings": findings,
        "screenshot": str(screenshot) if screenshot and screenshot.is_file() else None,
        "verdict": "PASS" if alive and not failures else "FAIL",
        "meaning": "runtime admitted the provisioned assets and the app is running -- an integration result, not a detection-quality claim"
        if alive and not failures
        else "see findings; a FAIL marker names the runtime's own rejection",
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Cold-launch the app and report runtime asset admission from logcat.")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--package", default=PACKAGE)
    parser.add_argument("--settle-seconds", type=float, default=10.0)
    parser.add_argument("--screenshot", type=Path, help="save a PNG of the app for the human record")
    args = parser.parse_args(argv)

    report = smoke_test(args.adb, args.package, args.settle_seconds, args.screenshot)
    print(json.dumps(report, indent=2))
    return 0 if report["verdict"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
