#!/usr/bin/env python3
"""Validate Tokito bootstrap governance without external dependencies."""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
SCHEMA_DIR = ROOT / "schemas"
EXAMPLE_DIR = ROOT / "examples" / "manifests"

TRACKED_FORBIDDEN_SUFFIXES = {
    ".apk",
    ".aab",
    ".aar",
    ".tflite",
    ".onnx",
    ".pte",
    ".pt",
    ".safetensors",
    ".bin",
    ".keystore",
    ".jks",
    ".pem",
    ".key",
}
TRACKED_FORBIDDEN_PARTS = {
    "captures",
    "datasets/local",
    "models/local",
    "reports/generated",
    "device-logs",
}


class ValidationError(RuntimeError):
    pass


def load_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise ValidationError(f"{path}: invalid JSON: {exc}") from exc


def fail(message: str) -> None:
    raise ValidationError(message)


def validate_schema_document(path: Path, schema: dict[str, Any]) -> None:
    for key in ("$schema", "$id", "title", "type", "required", "properties"):
        if key not in schema:
            fail(f"{path}: schema missing {key}")
    if schema["type"] != "object":
        fail(f"{path}: top-level schema type must be object")
    if not str(schema["$id"]).startswith("https://tokito.ai/schemas/"):
        fail(f"{path}: $id must use the Tokito schema namespace")


def validate_value(path: str, value: Any, schema: dict[str, Any]) -> None:
    if "const" in schema and value != schema["const"]:
        fail(f"{path}: expected {schema['const']!r}, got {value!r}")
    if "enum" in schema and value not in schema["enum"]:
        fail(f"{path}: expected one of {schema['enum']!r}, got {value!r}")

    expected_type = schema.get("type")
    if expected_type == "object":
        if not isinstance(value, dict):
            fail(f"{path}: expected object")
        for required_key in schema.get("required", []):
            if required_key not in value:
                fail(f"{path}: missing required key {required_key}")
        for key, child in schema.get("properties", {}).items():
            if key in value:
                validate_value(f"{path}.{key}", value[key], child)
    elif expected_type == "array":
        if not isinstance(value, list):
            fail(f"{path}: expected array")
        min_items = schema.get("minItems")
        if min_items is not None and len(value) < min_items:
            fail(f"{path}: expected at least {min_items} items")
        child = schema.get("items")
        if child:
            for index, item in enumerate(value):
                validate_value(f"{path}[{index}]", item, child)
    elif expected_type == "string":
        if not isinstance(value, str):
            fail(f"{path}: expected string")
        if len(value) < schema.get("minLength", 0):
            fail(f"{path}: string is shorter than minLength")
        pattern = schema.get("pattern")
        if pattern and not re.fullmatch(pattern, value):
            fail(f"{path}: value does not match {pattern}")
    elif expected_type == "integer":
        if not isinstance(value, int) or isinstance(value, bool):
            fail(f"{path}: expected integer")
        if "minimum" in schema and value < schema["minimum"]:
            fail(f"{path}: value is below minimum {schema['minimum']}")
    elif expected_type == "number":
        if not isinstance(value, (int, float)) or isinstance(value, bool):
            fail(f"{path}: expected number")
        if "minimum" in schema and value < schema["minimum"]:
            fail(f"{path}: value is below minimum {schema['minimum']}")
    elif expected_type == "boolean":
        if not isinstance(value, bool):
            fail(f"{path}: expected boolean")


def validate_examples(schemas: dict[str, dict[str, Any]]) -> None:
    for example_path in sorted(EXAMPLE_DIR.glob("*.example.json")):
        schema_name = example_path.name.replace(".example.json", ".schema.json")
        schema = schemas.get(schema_name)
        if schema is None:
            fail(f"{example_path}: no matching schema {schema_name}")
        validate_value(str(example_path.relative_to(ROOT)), load_json(example_path), schema)


def tracked_files() -> list[str]:
    result = subprocess.run(
        ["git", "ls-files"],
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
    )
    return [line.strip() for line in result.stdout.splitlines() if line.strip()]


def validate_tracked_artifacts(files: list[str]) -> None:
    for file_name in files:
        normalized = file_name.replace("\\", "/")
        suffix = Path(normalized).suffix.lower()
        if suffix in TRACKED_FORBIDDEN_SUFFIXES:
            fail(f"{file_name}: generated/model/secret artifact must not be tracked")
        for forbidden in TRACKED_FORBIDDEN_PARTS:
            if normalized == forbidden or normalized.startswith(forbidden + "/"):
                fail(f"{file_name}: local generated data must not be tracked")


def require_text(path: str, needles: list[str]) -> None:
    text = (ROOT / path).read_text(encoding="utf-8")
    normalized = text.lower()
    for needle in needles:
        if needle.lower() not in normalized:
            fail(f"{path}: missing required text {needle!r}")


def validate_docs() -> None:
    require_text(
        "docs/EVIDENCE_MANIFESTS.md",
        [f"#{number}" for number in range(25, 34)]
        + [f"#{number}" for number in range(37, 41)]
        + ["#44", "#45"],
    )
    require_text(
        "docs/CI_CD.md",
        ["pull requests", "Direct pushes to `main`", "#33", "#37", "#45", "CI"],
    )
    require_text(
        "AGENTS.md",
        ["Require pull requests before merge", "Block force-push", "required checks"],
    )
    require_text(
        ".github/pull_request_template.md",
        ["Closes #", "CI", "No raw private captures"],
    )
    require_text(
        ".github/workflows/ci.yml",
        ["pull_request", "branches:", "main", "tools/validate_governance.py"],
    )
    require_text(
        ".github/rulesets/main-pr-gates.example.json",
        ["pull_request", "required_linear_history", "required_status_checks"],
    )


def validate_ci_event() -> None:
    event_name = os.environ.get("GITHUB_EVENT_NAME")
    event_path = os.environ.get("GITHUB_EVENT_PATH")
    github_ref = os.environ.get("GITHUB_REF")

    if not event_name or not event_path:
        return

    event = load_json(Path(event_path))
    close_pattern = re.compile(r"\b(close[sd]?|fix(e[sd])?|resolve[sd]?)\s+#\d+\b", re.I)

    if event_name == "pull_request":
        body = (event.get("pull_request") or {}).get("body") or ""
        if not close_pattern.search(body):
            fail("pull request body must include Closes #N, Fixes #N, or Resolves #N")

    if event_name == "push" and github_ref == "refs/heads/main":
        commits = event.get("commits") or []
        reviewed_pattern = re.compile(r"(Merge pull request #\d+|\(#\d+\))")
        suspicious = [
            commit.get("id", "unknown")[:12]
            for commit in commits
            if not reviewed_pattern.search(commit.get("message") or "")
        ]
        if suspicious:
            fail(
                "main received commits that do not look like PR merges or "
                f"squash merges with issue numbers: {', '.join(suspicious)}"
            )


def main() -> int:
    schemas = {
        path.name: load_json(path)
        for path in sorted(SCHEMA_DIR.glob("*.schema.json"))
    }
    if not schemas:
        fail("no schemas found")
    for name, schema in schemas.items():
        validate_schema_document(SCHEMA_DIR / name, schema)
    validate_examples(schemas)
    validate_tracked_artifacts(tracked_files())
    validate_docs()
    validate_ci_event()
    print(f"Validated {len(schemas)} schemas and Group 3 governance checks.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ValidationError as exc:
        print(f"error: {exc}", file=sys.stderr)
        raise SystemExit(1)
