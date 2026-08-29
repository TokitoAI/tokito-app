#!/usr/bin/env python3
"""Tests for reference-board source package validation."""

from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
from unittest.mock import patch
import unittest

from reference_package.manifest import fetch_allowed_sources, validate_manifest


ROOT = Path(__file__).resolve().parents[1]
EXAMPLE = ROOT / "examples" / "manifests" / "reference.source-package-manifest.example.json"


def load_example() -> dict:
    return json.loads(EXAMPLE.read_text(encoding="utf-8"))


class ReferencePackageTest(unittest.TestCase):
    def test_example_manifest_is_valid(self) -> None:
        result = validate_manifest(load_example())

        self.assertTrue(result.ok, result.to_dict())

    def test_rejects_placeholder_values(self) -> None:
        manifest = load_example()
        manifest["board"]["revision_status"] = "unknown"

        result = validate_manifest(manifest)

        self.assertFalse(result.ok)
        self.assertEqual(result.messages[0].code, "placeholder_value")

    def test_rejects_internet_artifacts_as_physical_capture(self) -> None:
        manifest = load_example()
        manifest["capture_evidence_policy"]["internet_artifacts_are_physical_captures"] = True

        result = validate_manifest(manifest)

        self.assertFalse(result.ok)
        self.assertIn("internet artifacts cannot be labeled", result.messages[0].message)

    def test_rejects_account_required_automatic_fetch(self) -> None:
        manifest = load_example()
        account_source = next(source for source in manifest["sources"] if source["access"] == "account_required")
        account_source["fetch_allowed"] = True

        result = validate_manifest(manifest)

        self.assertFalse(result.ok)
        self.assertTrue(any(message.path.endswith(".fetch_allowed") for message in result.messages))

    def test_rejects_product_images_without_training_permission(self) -> None:
        manifest = load_example()
        source = copy.deepcopy(manifest["sources"][0])
        source.update(
            {
                "source_id": "vendor-product-image",
                "role": "visual_reference",
                "kind": "product_image",
                "artifact_format": "image",
                "license_status": "public_metadata_only",
                "trainability": "training_candidate_after_license_review",
                "fetch_allowed": true_value(),
                "use_in_pipeline": "training_candidate_after_license_review",
            }
        )
        manifest["sources"].append(source)

        result = validate_manifest(manifest)

        self.assertFalse(result.ok)
        self.assertTrue(any(message.path.endswith(".trainability") for message in result.messages))

    def test_public_product_image_is_reference_not_training_claim(self) -> None:
        manifest = load_example()
        image_sources = [source for source in manifest["sources"] if source["kind"] == "product_image"]

        self.assertEqual(len(image_sources), 1)
        self.assertEqual(image_sources[0]["access"], "public")
        self.assertEqual(image_sources[0]["trainability"], "requires_explicit_permission")
        self.assertEqual(image_sources[0]["use_in_pipeline"], "visual_reference_after_review")

    def test_cli_validate_endpoint(self) -> None:
        completed = subprocess.run(
            [
                sys.executable,
                "-m",
                "tools.reference_package.cli",
                "validate",
                "--manifest",
                str(EXAMPLE),
            ],
            cwd=ROOT,
            check=False,
            capture_output=True,
            text=True,
        )

        self.assertEqual(completed.returncode, 0, completed.stderr + completed.stdout)
        self.assertTrue(json.loads(completed.stdout)["ok"])

    def test_fetch_rejects_oversized_response_without_final_artifact(self) -> None:
        manifest = load_example()
        source = next(source for source in manifest["sources"] if source["fetch_allowed"])
        source["expected_digest"] = None

        with tempfile.TemporaryDirectory() as directory, patch("reference_package.manifest.urlopen", return_value=_Response(b"too-large")):
            with self.assertRaisesRegex(ValueError, "response exceeds max_bytes"):
                fetch_allowed_sources(manifest, Path(directory), max_bytes=3)
            self.assertFalse((Path(directory) / f"{source['source_id']}.html").exists())
            self.assertEqual(list(Path(directory).iterdir()), [])

    def test_fetch_publishes_verified_response_atomically(self) -> None:
        manifest = load_example()
        source = next(source for source in manifest["sources"] if source["fetch_allowed"])
        payload = b"verified artifact"
        source["expected_digest"] = "sha256:" + hashlib.sha256(payload).hexdigest()

        with tempfile.TemporaryDirectory() as directory, patch("reference_package.manifest.urlopen", return_value=_Response(payload)):
            fetched = fetch_allowed_sources(manifest, Path(directory), max_bytes=len(payload))
            artifact = Path(fetched[0].path)
            self.assertEqual(artifact.read_bytes(), payload)
            self.assertEqual(list(Path(directory).glob(".*")), [])


def true_value() -> bool:
    return True


class _Response:
    def __init__(self, payload: bytes) -> None:
        self._payload = payload
        self._position = 0

    def __enter__(self) -> "_Response":
        return self

    def __exit__(self, exc_type: object, exc_value: object, traceback: object) -> None:
        return None

    def read(self, size: int = -1) -> bytes:
        if size < 0:
            size = len(self._payload)
        chunk = self._payload[self._position : self._position + size]
        self._position += len(chunk)
        return chunk


if __name__ == "__main__":
    unittest.main()
