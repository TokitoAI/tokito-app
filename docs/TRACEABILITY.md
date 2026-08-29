# Proposal-to-delivery traceability

The source proposal in [`proposals/`](proposals/) is informative: it explains
the intended experience, but does not override the Technical Bible, accepted
ADRs, or runtime contract. This matrix makes each proposed outcome traceable to
normative documentation, tracked work, and release evidence.

The GitHub project board owns sequencing and status. This file owns neither;
issue links are identifiers for the work required to substantiate each claim.

## Capability matrix

| Proposed outcome | Normative source | Tracked work | Evidence gate |
|---|---|---|---|
| Standalone offline AR product without Tokito | [ADR 0004](decisions/0004-standalone-fully-local-ar-product.md) and [product scope](PRODUCT_SCOPE.md) | [#37](https://github.com/TokitoAI/tokito-physical-runtime/issues/37) | Gates 1 through 7 |
| Automatic PCB discovery and registered AR overlays | [Architecture](ARCHITECTURE.md) and [runtime contract](RUNTIME_CONTRACT.md#discover_board) | [#37](https://github.com/TokitoAI/tokito-physical-runtime/issues/37), [#40](https://github.com/TokitoAI/tokito-physical-runtime/issues/40) | Gate 2 |
| Continuous, lightweight camera understanding | [Technical Bible: perception](TECHNICAL_BIBLE.md#5-camera-and-continuous-perception) and [ADR 0002](decisions/0002-event-driven-composed-pipeline.md) | [#12](https://github.com/TokitoAI/tokito-physical-runtime/issues/12), [#13](https://github.com/TokitoAI/tokito-physical-runtime/issues/13) | Gate 2 |
| Expensive vision only on an event or request | [Architecture: process model](ARCHITECTURE.md#process-and-thread-model) and [ADR 0002](decisions/0002-event-driven-composed-pipeline.md) | [#14](https://github.com/TokitoAI/tokito-physical-runtime/issues/14) | Gate 3 |
| Locate and inspect registered components | [Runtime contract: baseline tools](RUNTIME_CONTRACT.md#baseline-tools) | [#15](https://github.com/TokitoAI/tokito-physical-runtime/issues/15) | Gates 3 and 5 |
| Read LED state and blink behavior | [Runtime contract: baseline tools](RUNTIME_CONTRACT.md#baseline-tools) | [#16](https://github.com/TokitoAI/tokito-physical-runtime/issues/16) | Gates 3 and 5 |
| Verify assembly and observed changes | [Runtime contract: baseline tools](RUNTIME_CONTRACT.md#baseline-tools) | [#17](https://github.com/TokitoAI/tokito-physical-runtime/issues/17) | Gates 3 and 5 |
| Local wake word and voice activity detection | [Technical Bible: voice](TECHNICAL_BIBLE.md#8-voice-runtime) | [#18](https://github.com/TokitoAI/tokito-physical-runtime/issues/18) | Gate 4 |
| Local streaming speech recognition | [Technical Bible: voice](TECHNICAL_BIBLE.md#8-voice-runtime) and [model strategy](MODEL_STRATEGY.md#planned-capability-families) | [#19](https://github.com/TokitoAI/tokito-physical-runtime/issues/19) | Gate 4 |
| Typed extraction of spoken measurements | [Technical Bible: voice](TECHNICAL_BIBLE.md#8-voice-runtime) and [runtime contract](RUNTIME_CONTRACT.md#request_measurement) | [#20](https://github.com/TokitoAI/tokito-physical-runtime/issues/20) | Gates 4 and 5 |
| Local spoken feedback with barge-in | [Technical Bible: voice](TECHNICAL_BIBLE.md#8-voice-runtime) | [#21](https://github.com/TokitoAI/tokito-physical-runtime/issues/21) | Gate 4 |
| Local text LLM conversation and tool planning | [ADR 0004](decisions/0004-standalone-fully-local-ar-product.md) and [model strategy](MODEL_STRATEGY.md#local-orchestration-contract) | [#38](https://github.com/TokitoAI/tokito-physical-runtime/issues/38) | Gate 5 |
| Bounded local visual-language reasoning | [ADR 0002](decisions/0002-event-driven-composed-pipeline.md), [ADR 0004](decisions/0004-standalone-fully-local-ar-product.md), and [model strategy](MODEL_STRATEGY.md) | [#39](https://github.com/TokitoAI/tokito-physical-runtime/issues/39) | Gates 3 and 5 |
| Typed tools and evidence-bearing observations | [Runtime contract](RUNTIME_CONTRACT.md) | [#22](https://github.com/TokitoAI/tokito-physical-runtime/issues/22), [#23](https://github.com/TokitoAI/tokito-physical-runtime/issues/23), [#24](https://github.com/TokitoAI/tokito-physical-runtime/issues/24) | Gate 5 |
| On-device operation and private sensor data | [Security and privacy](SECURITY_AND_PRIVACY.md) | [#25](https://github.com/TokitoAI/tokito-physical-runtime/issues/25) | Every gate |
| Measured model/runtime selection on the iQOO target | [Model strategy](MODEL_STRATEGY.md) and [evaluation](EVALUATION.md) | [#8](https://github.com/TokitoAI/tokito-physical-runtime/issues/8), [#9](https://github.com/TokitoAI/tokito-physical-runtime/issues/9), [#26](https://github.com/TokitoAI/tokito-physical-runtime/issues/26)-[#29](https://github.com/TokitoAI/tokito-physical-runtime/issues/29) | Gates 1, 3, and 4 |
| Standalone application and reusable Android runtime boundary | [ADR 0001](decisions/0001-separate-physical-runtime.md), [ADR 0003](decisions/0003-python-off-device.md), [ADR 0004](decisions/0004-standalone-fully-local-ar-product.md), and [architecture](ARCHITECTURE.md) | [#30](https://github.com/TokitoAI/tokito-physical-runtime/issues/30), [#37](https://github.com/TokitoAI/tokito-physical-runtime/issues/37) | Gate 6 |
| End-to-end hackathon scenarios | [Delivery gates](DELIVERY_GATES.md) | [#31](https://github.com/TokitoAI/tokito-physical-runtime/issues/31) | Gate 7 |
| Future Companion consumption | [Architecture: integration policy](ARCHITECTURE.md#integration-policy) and [ADR 0001](decisions/0001-separate-physical-runtime.md) | [#32](https://github.com/TokitoAI/tokito-physical-runtime/issues/32) | Blocked until Gate 6 |
| Tokito + DS-ViRe assembly-context boundary and FRDM reference validation | [Architecture: integration policy](ARCHITECTURE.md#integration-policy), [runtime contract](RUNTIME_CONTRACT.md#assembly-context-bundle), and [FRDM validation](FRDM_MCXN236_VALIDATION.md) | [#42](https://github.com/TokitoAI/tokito-physical-runtime/issues/42) | Gates 2, 3, and 5 |

## Claim policy

A checked box, merged implementation, or successful demo does not by itself
substantiate a product claim. A claim is supported only when its issue
acceptance criteria and corresponding delivery-gate evidence are complete on
the exact declared device and frozen evaluation inputs.

Unknown hardware capabilities remain unknown until captured by
[#8](https://github.com/TokitoAI/tokito-physical-runtime/issues/8). The runtime
must not encode an assumed iQOO SoC, accelerator, memory budget, or Android API
level before that evidence exists.

Standalone operation is the primary release path. Companion integration remains
intentionally dormant. Issue
[#32](https://github.com/TokitoAI/tokito-physical-runtime/issues/32) cannot start
until the standalone Android application, reusable package, and conformance
evidence satisfy Gate 6.
