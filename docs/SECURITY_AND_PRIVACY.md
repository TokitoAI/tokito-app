# Security and privacy

## Trust boundary

The phone is the default boundary for camera frames, microphone audio, derived
features, model execution, and session evidence. A reasoning client receives
typed observations and explicitly selected evidence, not a continuous media
stream. The baseline reasoning client is the local standalone application;
text LLM, VLM, speech, and vision execution remain inside this boundary.

Inputs are untrusted:

- design context and tool arguments;
- camera/audio/sensor data;
- model bundles and manifests before verification;
- transcripts and model outputs;
- future consumer callbacks and persisted session state.

## Permissions and user awareness

- Camera and microphone permissions are requested only for an explicit session.
- Active capture is visible in the consuming UI and respects Android privacy
  indicators.
- Background capture is disabled in the baseline runtime.
- Session stop releases camera/audio resources and clears ephemeral buffers.
- Permission loss produces a typed state transition and cannot be auto-granted.

## Data minimization

By default, retain structured observations, provenance, calibration/transform
metadata, and minimal bounded evidence. Do not retain continuous raw video or
audio. Diagnostic capture requires explicit opt-in, a retention limit, visible
storage use, review/export controls, and deletion.

Logs redact transcripts, design identities, filesystem paths, and raw payloads.
Telemetry uses bounded counters/timings and is disabled or local unless a future
privacy-reviewed integration states otherwise.

Observation envelopes declare whether evidence is available, redacted, or
unavailable. Redaction and retention decisions are explicit and do not turn an
empty evidence list into a claim that evidence was retained.

## Model supply chain

Every model bundle is content-addressed and signed. The runtime verifies:

- manifest schema and required fields;
- artifact digest and signature;
- runtime/API compatibility;
- license/source policy;
- ABI/backend/operator profile;
- size/decompression/resource limits;
- reference conformance vectors.

Activation is atomic and rollback uses a previously verified bundle. Archives
reject absolute paths, traversal, symlinks, duplicate entries, and expansion
bombs.

The native admission boundary verifies a canonical SHA-256 artifact digest,
delegates signature trust to the platform's configured verifier, and requires
the declared schema major, runtime revision, and ABI to match the active
runtime. Validation completes before a quota-aware atomic activation replaces a
previously active manifest.

## Tool safety

Tool calls are descriptive and observational. The baseline runtime does not
energize boards or control instruments. Measurement guidance identifies points
only when registration confidence satisfies the accepted threshold; otherwise
it abstains. Electrical interpretation belongs to the local dialogue/tool
system or an optional client and must retain the observation provenance.
Generative output cannot authorize a tool, normalize a measurement, or
override a safety rule.

## Native boundary

All buffers crossing Kotlin/native/backend boundaries have explicit ownership,
size, format, stride, lifetime, and thread rules. Validate dimensions and
integer arithmetic before allocation. Backend crashes/timeouts cannot corrupt
the active model manifest or return partial success.

## Threat-driven tests

- malformed/oversized tool and observation payloads;
- corrupt, truncated, wrong-signature, wrong-ABI, and archive-bomb bundles;
- camera/audio permission revocation during work;
- stale session/context/transform replay;
- duplicate tool-call retry and late callback races;
- cancellation/deadline during capture, inference, and TTS;
- resource exhaustion and thermal/backend failure;
- prompt/transcript content attempting to change runtime policy;
- model output with NaN, infinity, invalid geometry, or impossible class.

## Future network boundary

This repository does not define a public HTTP service. Baseline functionality
must not attempt remote fallback when networking is unavailable. Any future
network integration or cloud inference mode needs an ADR,
authenticated/encrypted transport, explicit consent, retention policy, abuse
controls, and an updated threat model before implementation.
