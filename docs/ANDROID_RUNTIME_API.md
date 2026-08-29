# Android runtime API

The `android/runtime` library is the standalone product's reusable in-process
Android boundary. It has no Tokito Companion dependency and exposes no model,
backend, tensor, camera-frame, or audio-buffer type publicly.

## Capability discovery

`PhysicalRuntime.capabilities()` returns a `CapabilityHandshake`: a versioned
contract identifier, the active device-profile identifier, and a list of
accepted `Capability` entries. This shape is not a second, hand-maintained
contract; its field names and structure are the Kotlin projection of the
canonical `capabilityHandshake` definition in
[`contracts/physical-runtime.v1.schema.json`](../contracts/physical-runtime.v1.schema.json),
and `PhysicalRuntimeTest` decodes the checked-in
`contracts/fixtures/physical-runtime.v1.handshake.json` fixture against it so
the two cannot silently drift.

The default capability list is empty. An integration may add a `Capability`
entry only after its operation's compatible local model, backend evidence,
permission policy, and resource policy have been accepted. An unsupported
operation is omitted from the list entirely rather than represented as a fake
success path; a listed operation's `modelAvailable = false` describes an
accepted operation whose model bundle is not currently resident, which is a
distinct, weaker claim than "unsupported."

Each capability's `resourceLimits` map declares only the value shapes its
evidence actually supports — integer, floating-point, string, or boolean, per
the canonical schema — instead of assuming every limit is numeric.

## Session lifecycle

`openSession()` creates an opaque session with deterministic transitions:

- `Created` starts calibration;
- accepted calibration becomes `Ready` or `Degraded`;
- camera or permission loss suspends the session and invalidates its generation;
- resume always returns to calibration, never directly to active guidance;
- stop leads to `Stopping`, then close leads to `Closed`; and
- callbacks after close are rejected.

The Android sensor integration must translate its permission, camera, process,
and background events into this contract. It must release sensors and cancel
work before closing a session. The library does not claim to bind a camera,
execute a model, or expose a physical tool until those integrations are
implemented and conformance-tested.

`PhysicalRuntimeTest` covers duplicate/retried lifecycle calls (a repeated
`Close` after teardown fails the same way on every attempt, not just the
first), late callbacks after close, and full teardown (`Stop` then `Close`
from every reachable active state).
