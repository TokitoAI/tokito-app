#!/usr/bin/env python3
"""Regression tests for repository governance invariants."""

from pathlib import Path
import unittest

from validate_governance import ValidationError, validate_workflow_runner_spec


class WorkflowRunnerPolicyTest(unittest.TestCase):
    def test_complete_tokito_vps_label_set_is_accepted(self) -> None:
        validate_workflow_runner_spec(
            Path(".github/workflows/ci.yml"),
            "[self-hosted, Linux, X64, tokito-vps, private-build]",
        )

    def test_github_hosted_runner_is_rejected(self) -> None:
        with self.assertRaisesRegex(ValidationError, "GitHub-hosted runner"):
            validate_workflow_runner_spec(
                Path(".github/workflows/ci.yml"),
                "ubuntu-latest",
            )

    def test_partial_vps_label_set_is_rejected(self) -> None:
        with self.assertRaisesRegex(ValidationError, "missing Tokito VPS labels"):
            validate_workflow_runner_spec(
                Path(".github/workflows/ci.yml"),
                "[self-hosted, Linux, X64, tokito-vps]",
            )

    def test_label_names_must_be_exact_tokens(self) -> None:
        with self.assertRaisesRegex(ValidationError, "missing Tokito VPS labels"):
            validate_workflow_runner_spec(
                Path(".github/workflows/ci.yml"),
                "[not-self-hosted, Linux, X64, tokito-vps, private-build]",
            )


if __name__ == "__main__":
    unittest.main()
