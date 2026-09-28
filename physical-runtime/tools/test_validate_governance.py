#!/usr/bin/env python3
"""Regression tests for repository governance invariants."""

import unittest
from pathlib import Path

from validate_governance import (
    ValidationError,
    validate_value,
    validate_workflow_runner_spec,
)


class WorkflowRunnerPolicyTest(unittest.TestCase):
    """Builds must not run on billed GitHub-hosted capacity.

    The original rule (#49/#50) required the `tokito-vps` label set on every
    job. That runner is being decommissioned and the Android build moved to
    Buildkite, so the rule now states the intent directly instead of naming a
    machine: GitHub-hosted is refused everywhere except ci.yml, which runs this
    validator and needs the Actions event payload to do it.
    """

    def test_self_hosted_runner_is_accepted(self) -> None:
        validate_workflow_runner_spec(
            Path(".github/workflows/android.yml"),
            "[self-hosted, Linux, X64, tokito-vps, private-build]",
        )

    def test_github_hosted_runner_is_rejected_for_builds(self) -> None:
        with self.assertRaisesRegex(ValidationError, "not allowed here"):
            validate_workflow_runner_spec(
                Path(".github/workflows/android.yml"),
                "ubuntu-latest",
            )

    def test_github_hosted_runner_is_allowed_for_the_governance_workflow(self) -> None:
        validate_workflow_runner_spec(
            Path(".github/workflows/ci.yml"),
            "ubuntu-latest",
        )

    def test_a_new_workflow_cannot_opt_itself_into_hosted_capacity(self) -> None:
        with self.assertRaisesRegex(ValidationError, "not allowed here"):
            validate_workflow_runner_spec(
                Path(".github/workflows/sneaky-build.yml"),
                "ubuntu-latest",
            )

class SchemaSubsetTest(unittest.TestCase):
    def test_unknown_properties_are_rejected_when_schema_is_strict(self) -> None:
        with self.assertRaisesRegex(ValidationError, "unknown keys"):
            validate_value(
                "fixture",
                {"known": "yes", "unexpected": "no"},
                {"type": "object", "additionalProperties": False, "properties": {"known": {"type": "string"}}},
            )

    def test_timezone_is_required_for_date_time(self) -> None:
        with self.assertRaisesRegex(ValidationError, "UTC offset"):
            validate_value("fixture", "2026-08-29T00:00:00", {"type": "string", "format": "date-time"})

if __name__ == "__main__":
    unittest.main()
