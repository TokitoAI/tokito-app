#!/usr/bin/env python3
"""Tests for reference-board source package validation."""

from __future__ import annotations

import copy
import json
from pathlib import Path
import subprocess
import sys
import unittest

from reference_package.manifest import validate_manifest


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


def true_value() -> bool:
    return True


if __name__ == "__main__":
    unittest.main()
