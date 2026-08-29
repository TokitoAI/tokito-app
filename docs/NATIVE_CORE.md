# Native runtime core

`native/runtime-core` is the platform-independent native safety core for the
Android library. It implements no model, camera binding, network service, or
public physical tool. Tool support remains absent until a measured device
profile, model bundle, backend conformance result, calibration policy, and
evidence contract enable it.

The core owns only deterministic mechanisms required by the delivery gates:

- lifecycle transitions that force recalibration after suspend/resume;
- capability gating that fails closed for unadvertised tools;
- versioned tool-call admission, capacity-bounded deterministic ledger
  snapshots, idempotent retry handling, and exactly-one terminal outcomes;
- bounded deterministic observation serialization with explicit timestamps,
  design binding, retention, registration, evidence, and provenance;
- immutable assembly-context JSON parsing, canonical signed-payload verification,
  and atomic quota-bound activation;
- model-manifest admission with digest, signature, ABI, runtime-revision, and quota checks;
- evidence-gated backend selection that rejects missing lifecycle, thermal, conformance, profile/ABI, or fallback evidence;
- caller-configured registration confidence and homography validation;
- one deadline/cancellation guard shared by camera, audio, preprocessing, and
  inference adapters;
- bounded, deadline-aware, cancellable ROI work scheduling;
- latest-frame handoff and caller-configured storage quotas; and
- caller-configured thermal concurrency policy.

Runtime errors are typed and carry only retryability plus safe recovery
guidance. They distinguish invalid input, unsupported capability, permission or
lifecycle loss, deadline, cancellation, registration loss, resource/thermal
pressure, model unavailability, backend failure, and invariant failure without
returning raw media, paths, transcripts, tokens, or backend internals.

It deliberately carries no device name, ABI, model, backend, threshold, queue
capacity, or thermal cutoff. The Android layer must provide those only from the
versioned device profile and accepted benchmark evidence.

## Resource stress harness

`resource_governor_stress` is an Android-compatible native executable under
`native/runtime-core/examples`. Every workload and policy value is required on
the command line: duration, scheduler capacity, storage quota, producer batch,
consumer interval, sleep interval, and nominal/constrained/critical
concurrency. There are no device or workload defaults.

The harness exercises latest-frame replacement, observable stale-work drops,
bounded scheduler contention, quota rejection/release, all execution-stage
guards, and all declared thermal-policy states. It emits one JSON report and
fails unless every bounded rejection path ran. Cross-compiling and running it
on the target phone is evidence for the native governor and resource-exhaustion
mechanisms. It is not CameraX, tracker, model, battery, or thermal-throttling
evidence; those remain separate device gates owned by their implementing
pipelines. Raw device reports remain local and must not be committed.
