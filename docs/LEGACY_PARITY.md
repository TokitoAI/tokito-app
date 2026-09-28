# Legacy feature parity

Tracking: [#10](https://github.com/TokitoAI/tokito-app/issues/10)

Parity means an old user-visible capability is reachable from the unified
Tokito application and its implementation passes the same or stronger tests.
Preserved source, a tagged branch, a capability label, or an unconnected
library does not count.

| Legacy capability | Unified implementation | Evidence |
|---|---|---|
| Companion authentication, Cloud projects, design review, chat, proposals, artifacts, and realtime sync | `apps/mobile` plus Rust UniFFI client | Mobile contract/unit tests and unified APK gate |
| Projectless and project-bound physical sessions | Home/project entry into the same native session contract | `physical-integration.test.mjs`, `PhysicalSessionStoreTest` |
| CameraX board workspace and registered AR | Embedded Physical activity and AR runtime | AR JVM tests, app lint/assembly, unified APK gate |
| Digital-twin import, metric scale, visibility, and grounded highlight | `DigitalTwinAsset`, `DigitalTwinView`, `ArSessionController` | AR asset tests and app unit tests |
| Local streaming voice and speech | Sherpa-backed voice runtime with verified offline bundle | Voice JVM tests; no network fallback |
| Local grounded dialogue | admitted local dialogue bundle with verified-reference fallback | Dialogue tests and grounded-answer tests |
| Deterministic electrical measurement parsing, explicit confirmation, and durable capture | `Record reading` in the camera session routes final local transcripts through `MeasurementVoiceSession`; confirmed values become structured `electrical_measurement` observations | Measurement/voice JVM tests, session-store tests, app lint and assembly |
| Local wake phrase and adaptive VAD | `TokitoVoiceRuntime.startActivation()` uses the current verified Sherpa recognizer through the restored bounded pre-roll/VAD front end | Front-end and voice-runtime JVM tests; obsolete Moonshine backend is intentionally not restored |
| Assembly-context import and archive security policy | Rust importer plus Android security policy | Rust golden fixtures and Android policy tests |
| NXP source-package validation | generic reference-package validator | Python validator tests |
| Component identity, location, and deterministic connection guidance | Generic placement/index/query pipeline; no board-specific logic in the resolver | Python component-info, locator, query, and end-to-end chain tests |
| Populated digital-twin build, model coverage, metadata, visibility, and acceptance verification | Generic offline package toolchain feeding the admitted runtime asset contract | Python package, GLB, coverage, highlightability, and runtime-acceptance tests |
| Offline corpus and model experiment governance | leakage, provenance, and sealing checks | Python governance tests |

## Replacement decisions

Parity does not mean restoring superseded infrastructure. The removed Moonshine
backend is replaced by the verified Sherpa runtime while retaining wake/VAD,
streaming STT, local TTS, and barge-in behavior. Retired GitHub Actions and VPS
workflow files are replaced by the repository's Buildkite pipeline. The old
standalone native CMake entry point is replaced by the admitted bundled
dialogue runtime. These are implementation upgrades, not dropped capabilities.

The last standalone Companion tree is an ancestor of this repository. No file
under `apps/mobile`, `crates/core`, or `crates/ffi` was deleted during the
unification; the unified navigation composes those existing capabilities with
the embedded Physical runtime.

Exact camera, microphone, AR alignment, thermal, and model-performance results
remain device evidence, not simulated parity. Their absence must never be
reported as a passed hardware check.
