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
| Deterministic electrical measurement parsing and confirmation | measurement and voice-session runtimes | Measurement and voice JVM tests |
| Assembly-context import and archive security policy | Rust importer plus Android security policy | Rust golden fixtures and Android policy tests |
| NXP source-package validation | generic reference-package validator | Python validator tests |
| Component location from imported board geometry | generic locator and package builder | Python locator/placement tests |
| Offline corpus and model experiment governance | leakage, provenance, and sealing checks | Python governance tests |

Exact camera, microphone, AR alignment, thermal, and model-performance results
remain device evidence, not simulated parity. Their absence must never be
reported as a passed hardware check.
