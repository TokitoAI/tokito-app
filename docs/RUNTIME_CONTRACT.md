# Runtime contract

This document defines the future external contract independently of programming
language or serialization. Concrete Kotlin/Rust/Python types must be generated
or tested against one canonical schema once implementation begins.

## Capability handshake

A client opens the runtime and receives:

- runtime and contract versions;
- device profile ID and Android/ABI details;
- available camera, audio, accelerator, and sensor capabilities;
- supported tool versions and package/task families;
- active model bundle and preprocessing digests;
- latency/thermal profile and current degradation state;
- privacy/persistence capabilities.
- standalone/offline readiness and locally available LLM, VLM, STT, and TTS
  profiles.

Unsupported tools are omitted. A registered tool may still return a typed
runtime condition such as registration lost or permission denied.

The standalone application is itself a client of this contract. Local dialogue
and AR rendering consume the same typed events exposed to future integrations;
they do not use privileged model-only shortcuts.

## Assembly context bundle

An optional, immutable bundle binds authored design truth to grounded source
evidence. Its canonical schema must include:

- schema version, bundle ID, design/project identity, board revision, and
  content digest;
- board outline, coordinate system, side, units, and registration landmarks;
- BOM identity and per-reference footprint, centroid, rotation, side, pin-one
  or polarity rule, and permitted alternates;
- evidence records with source-document digest, page/region locator, extractor
  revision, confidence, and review state;
- provenance for every generated field and the tools that produced it;
- licensing/redistribution metadata and signature/integrity information.

Board-specific values are data. Importers may support multiple CAD/vendor
formats, but runtime control flow cannot branch on a particular board name.
Missing or conflicting placement/evidence fields produce a typed import error
or an unsupported capability; they are never filled with model guesses.

## Tool call envelope

```json
{
  "schema_version": "1.0",
  "tool_call_id": "uuid",
  "session_id": "uuid",
  "context_revision": "opaque-immutable-revision",
  "origin": "local_dialogue",
  "tool": "inspect_component",
  "tool_version": "1.0",
  "deadline_monotonic_ms": 123456789,
  "required_confidence": 0.995,
  "arguments": {
    "component_ref": "U7",
    "expected_package": "LQFP48",
    "checks": ["presence", "package", "orientation"]
  }
}
```

IDs and revision strings are examples of shape, not hardcoded values.

## Observation envelope

```json
{
  "schema_version": "1.0",
  "observation_id": "uuid",
  "tool_call_id": "uuid",
  "session_id": "uuid",
  "context_revision": "opaque-immutable-revision",
  "kind": "component_inspection",
  "source": "camera",
  "captured_monotonic_ms": 123456000,
  "confidence": 0.997,
  "calibration": {
    "method": "held-out-temperature-scaling",
    "revision": "digest"
  },
  "coordinate_frame": {
    "frame_id": "registered-board-plane",
    "transform_revision": "digest",
    "uncertainty": 0.003
  },
  "payload": {
    "component_ref": "U7",
    "present": true,
    "package": "LQFP48",
    "orientation_degrees": 90
  },
  "evidence": [
    {
      "kind": "roi_crop",
      "digest": "sha256",
      "retention": "session"
    }
  ],
  "provenance": {
    "runtime_revision": "git-or-release-revision",
    "model_bundle_digest": "sha256",
    "preprocessing_digest": "sha256",
    "backend": "declared-runtime-backend",
    "device_profile": "profile-id"
  },
  "warnings": []
}
```

## Terminal outcomes

Exactly one terminal outcome is emitted per accepted call:

| Outcome | Meaning |
|---|---|
| `observation` | Completed with typed evidence |
| `abstained` | Evidence insufficient for the requested threshold |
| `cancelled` | Caller/session/lifecycle cancellation won the race |
| `error` | Typed runtime or argument failure |

Late backend callbacks after a terminal outcome are discarded and counted.

## Baseline tools

### `discover_board`

Detects and stabilizes a PCB or electronics work area without requiring a
Tokito design. Returns a tracked board-plane frame, visible polygon, capture
quality, uncertainty, and candidate board class. It abstains when a stable
board region cannot be established.

### `inspect_region`

Performs a bounded generic inspection in an unbound board-plane or image ROI.
It returns only capability-declared classes and evidence. A local VLM may
generate candidates, but cannot promote them to observations without typed
validation and calibrated thresholds.

### `locate_component`

With design context, returns registered physical geometry and preview guidance
for an exact reference. Without design context, accepts a supported generic
description and returns ranked candidates without claiming design identity. It
must abstain if candidates cannot be disambiguated at the requested threshold.

### `inspect_component`

Evaluates only requested checks for a bounded component ROI. Supported package
families and orientation classes are capability data.

### `read_led_state`

Returns state (`on`, `off`, `blinking`, or abstained), observation window,
brightness trace summary, and blink frequency/range when supported.

### `request_measurement`

Prompts the engineer, captures local speech, parses a typed value/unit/relation,
and requires confirmation when ambiguous. It never invents a value.

### `verify_assembly`

Compares a declared bounded board region/list against expected BOM/design
context. Initial versions report only supported presence/package/orientation
checks, not universal manufacturing correctness.

### `verify_change`

Watches a bounded region until a declared predicate changes, remains stable for
a duration, times out, or is cancelled. It does not run indefinitely.

## Error codes

Stable families:

- `invalid_argument`
- `unsupported_capability`
- `permission_denied`
- `session_state_invalid`
- `lifecycle_unavailable`
- `registration_lost`
- `capture_quality_insufficient`
- `deadline_exceeded`
- `cancelled`
- `thermal_limited`
- `model_unavailable`
- `model_integrity_failure`
- `backend_failure`
- `resource_exhausted`
- `internal_invariant_violation`

Every error includes retryability and safe recovery guidance. Internal paths,
stack traces, raw transcripts, and model internals are not exposed by default.

## Compatibility

- Major schema/tool version changes are incompatible and rejected.
- Minor versions are additive; unknown optional fields are preserved or ignored
  according to generated conformance tests.
- Capability discovery precedes tool use.
- A context revision mismatch is a conflict, not an implicit upgrade.
- Tool-call IDs make retries idempotent within the documented retention window.
