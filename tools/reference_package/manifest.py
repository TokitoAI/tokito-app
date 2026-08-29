"""Validate and optionally fetch reference-board source package artifacts."""

from __future__ import annotations

from dataclasses import dataclass
import hashlib
import json
import re
from pathlib import Path
from typing import Any
from urllib.parse import urlparse
from urllib.request import Request, urlopen


PLACEHOLDER_RE = re.compile(r"\b(?:todo|tbd|placeholder|not-yet|unknown|dummy|fake)\b", re.IGNORECASE)
DATE_RE = re.compile(r"^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
SOURCE_ID_RE = re.compile(r"^[a-z0-9][a-z0-9._-]*$")
TRACKED_FORBIDDEN_PARTS = ("captures", "datasets/local", "models/local", "reports/generated", "device-logs")


@dataclass(frozen=True)
class ValidationMessage:
    code: str
    message: str
    path: str

    def to_dict(self) -> dict[str, str]:
        return {"code": self.code, "message": self.message, "path": self.path}


@dataclass(frozen=True)
class ValidationResult:
    messages: tuple[ValidationMessage, ...]

    @property
    def ok(self) -> bool:
        return not self.messages

    def to_dict(self) -> dict[str, Any]:
        return {
            "ok": self.ok,
            "messages": [message.to_dict() for message in self.messages],
        }


@dataclass(frozen=True)
class FetchResult:
    source_id: str
    path: str
    digest: str
    bytes_written: int

    def to_dict(self) -> dict[str, Any]:
        return {
            "source_id": self.source_id,
            "path": self.path,
            "digest": self.digest,
            "bytes_written": self.bytes_written,
        }


def load_manifest(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def validate_manifest(manifest: dict[str, Any]) -> ValidationResult:
    messages: list[ValidationMessage] = []
    _reject_placeholders("$", manifest, messages)

    if manifest.get("schema_version") != "1.0":
        messages.append(ValidationMessage("invalid_argument", "schema_version must be 1.0", "$.schema_version"))

    board = manifest.get("board")
    if not isinstance(board, dict):
        messages.append(ValidationMessage("invalid_argument", "board must be an object", "$.board"))
    else:
        for field in ("vendor", "product", "revision_status"):
            if not board.get(field):
                messages.append(ValidationMessage("invalid_argument", f"board.{field} is required", f"$.board.{field}"))

    retrieval = manifest.get("retrieval_policy")
    if not isinstance(retrieval, dict):
        messages.append(ValidationMessage("invalid_argument", "retrieval_policy must be an object", "$.retrieval_policy"))
    else:
        if retrieval.get("network_downloads_require_explicit_command") is not True:
            messages.append(ValidationMessage("policy_violation", "network downloads must require an explicit command", "$.retrieval_policy.network_downloads_require_explicit_command"))
        if retrieval.get("commit_downloaded_artifacts") is not False:
            messages.append(ValidationMessage("policy_violation", "downloaded artifacts must not be committed", "$.retrieval_policy.commit_downloaded_artifacts"))
        if retrieval.get("account_required_downloads") is not False:
            messages.append(ValidationMessage("policy_violation", "account-required artifacts need manual account and terms review", "$.retrieval_policy.account_required_downloads"))
        _validate_output_directory(str(retrieval.get("default_output_directory", "")), messages)

    capture = manifest.get("capture_evidence_policy")
    if not isinstance(capture, dict):
        messages.append(ValidationMessage("invalid_argument", "capture_evidence_policy must be an object", "$.capture_evidence_policy"))
    else:
        if capture.get("physical_capture_required") is not True:
            messages.append(ValidationMessage("policy_violation", "physical iQOO capture evidence remains required", "$.capture_evidence_policy.physical_capture_required"))
        if capture.get("internet_artifacts_are_physical_captures") is not False:
            messages.append(ValidationMessage("policy_violation", "internet artifacts cannot be labeled as physical captures", "$.capture_evidence_policy.internet_artifacts_are_physical_captures"))
        if capture.get("raw_captures_committed_to_git") is not False:
            messages.append(ValidationMessage("policy_violation", "raw captures must not be committed to Git", "$.capture_evidence_policy.raw_captures_committed_to_git"))

    sources = manifest.get("sources")
    if not isinstance(sources, list) or not sources:
        messages.append(ValidationMessage("invalid_argument", "sources must be a non-empty array", "$.sources"))
    else:
        _validate_sources(sources, messages)

    return ValidationResult(tuple(messages))


def fetch_allowed_sources(manifest: dict[str, Any], output_dir: Path) -> list[FetchResult]:
    result = validate_manifest(manifest)
    if not result.ok:
        formatted = "; ".join(f"{message.path}: {message.message}" for message in result.messages)
        raise ValueError(f"manifest is not valid: {formatted}")

    output_dir.mkdir(parents=True, exist_ok=True)
    fetched: list[FetchResult] = []
    for source in manifest["sources"]:
        if not source["fetch_allowed"]:
            continue
        if source["access"] != "public":
            continue
        url = source["url"]
        request = Request(url, headers={"User-Agent": "TokitoSourcePackageValidator/1.0"})
        with urlopen(request, timeout=30) as response:
            body = response.read()
        digest = "sha256:" + hashlib.sha256(body).hexdigest()
        expected_digest = source.get("expected_digest")
        if expected_digest and expected_digest != digest:
            raise ValueError(f"{source['source_id']}: digest mismatch")
        path = output_dir / _artifact_filename(source)
        path.write_bytes(body)
        fetched.append(FetchResult(source["source_id"], str(path), digest, len(body)))
    return fetched


def _validate_sources(sources: list[Any], messages: list[ValidationMessage]) -> None:
    seen: set[str] = set()
    has_board_reference = False
    has_design_truth_candidate = False
    for index, source in enumerate(sources):
        path = f"$.sources[{index}]"
        if not isinstance(source, dict):
            messages.append(ValidationMessage("invalid_argument", "source must be an object", path))
            continue
        source_id = str(source.get("source_id", ""))
        if not SOURCE_ID_RE.fullmatch(source_id):
            messages.append(ValidationMessage("invalid_argument", "source_id must be stable lowercase identifier", f"{path}.source_id"))
        if source_id in seen:
            messages.append(ValidationMessage("invalid_argument", "source_id must be unique", f"{path}.source_id"))
        seen.add(source_id)
        if source.get("role") == "board_reference":
            has_board_reference = True
        if source.get("role") == "design_truth_candidate":
            has_design_truth_candidate = True
        _validate_url(str(source.get("url", "")), messages, f"{path}.url")
        if not DATE_RE.fullmatch(str(source.get("retrieved_at", ""))):
            messages.append(ValidationMessage("invalid_argument", "retrieved_at must be YYYY-MM-DD", f"{path}.retrieved_at"))
        if source.get("access") != "public" and source.get("fetch_allowed") is True:
            messages.append(ValidationMessage("policy_violation", "only public artifacts may be fetched automatically", f"{path}.fetch_allowed"))
        if source.get("kind") == "product_image" and source.get("trainability") != "requires_explicit_permission":
            messages.append(ValidationMessage("policy_violation", "internet product images need explicit training permission", f"{path}.trainability"))
        if source.get("license_status") in {"public_metadata_only", "pending_review"}:
            if source.get("use_in_pipeline") == "training_candidate_after_license_review":
                messages.append(ValidationMessage("policy_violation", "training candidates need an artifact license review path", f"{path}.use_in_pipeline"))
        if source.get("trainability") == "metadata_only" and source.get("artifact_format") in {"image", "zip"}:
            messages.append(ValidationMessage("policy_violation", "image and archive artifacts need explicit trainability review", f"{path}.trainability"))
    if not has_board_reference:
        messages.append(ValidationMessage("invalid_argument", "at least one board_reference source is required", "$.sources"))
    if not has_design_truth_candidate:
        messages.append(ValidationMessage("invalid_argument", "at least one design_truth_candidate source is required", "$.sources"))


def _validate_url(value: str, messages: list[ValidationMessage], path: str) -> None:
    parsed = urlparse(value)
    if parsed.scheme != "https" or not parsed.netloc:
        messages.append(ValidationMessage("invalid_argument", "source URLs must be absolute HTTPS URLs", path))


def _validate_output_directory(value: str, messages: list[ValidationMessage]) -> None:
    normalized = value.replace("\\", "/").strip("/")
    if not normalized:
        messages.append(ValidationMessage("invalid_argument", "default_output_directory is required", "$.retrieval_policy.default_output_directory"))
        return
    if normalized.startswith("../") or "/../" in normalized:
        messages.append(ValidationMessage("policy_violation", "default_output_directory cannot escape the repository", "$.retrieval_policy.default_output_directory"))
    if normalized.split("/", 1)[0] not in {"build", "reports"}:
        messages.append(ValidationMessage("policy_violation", "download output must be under an ignored build or reports path", "$.retrieval_policy.default_output_directory"))
    for forbidden in TRACKED_FORBIDDEN_PARTS:
        if normalized == forbidden or normalized.startswith(forbidden + "/"):
            messages.append(ValidationMessage("policy_violation", f"download output cannot use tracked-forbidden path {forbidden}", "$.retrieval_policy.default_output_directory"))


def _reject_placeholders(path: str, value: Any, messages: list[ValidationMessage]) -> None:
    if isinstance(value, str):
        if PLACEHOLDER_RE.search(value):
            messages.append(ValidationMessage("placeholder_value", "placeholder values are not allowed in source package manifests", path))
    elif isinstance(value, dict):
        for key, child in value.items():
            _reject_placeholders(f"{path}.{key}", child, messages)
    elif isinstance(value, list):
        for index, child in enumerate(value):
            _reject_placeholders(f"{path}[{index}]", child, messages)


def _artifact_filename(source: dict[str, Any]) -> str:
    suffix = {
        "html": ".html",
        "pdf": ".pdf",
        "zip": ".zip",
        "image": ".img",
        "metadata": ".json",
    }[source["artifact_format"]]
    return f"{source['source_id']}{suffix}"
