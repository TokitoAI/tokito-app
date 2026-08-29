# Model strategy

## Principle: complete local intelligence, composed efficiently

Continuous camera work and event-driven semantic inference have different
latency, power, and accuracy requirements. Every baseline capability runs on
the phone, including text reasoning, visual reasoning, English recognition, and
English synthesis. The runtime still uses a composed pipeline instead of
forcing one VLM to handle tracking, component identity, LED state, speech, and
guidance.

## Planned capability families

| Layer | Candidate approach | Activation |
|---|---|---|
| Board/feature tracking | classical CV or compact learned features | continuous |
| Change detection | image statistics / compact temporal model | continuous |
| Component localization | compact detector or open-vocabulary candidate generator | event |
| Package/orientation | ROI classifier and geometry/context rules | event |
| Marking/OCR | specialized OCR/recognizer | event |
| LED state/blink | temporal photometric detector/classifier | bounded window |
| General visual reasoning | quantized local VLM with structured decoding | explicit event only |
| Dialogue/tool planning | quantized local text LLM with constrained tools | user turn/event |
| Wake word/VAD | compact streaming audio models | continuous audio session |
| STT | streaming English-only on-device ASR | after wake/explicit request |
| Measurement parsing | deterministic parser after local LLM intent proposal | event |
| TTS | local English streaming synthesizer | explicit guidance |

## Candidate baseline for device benchmarking

These are candidates, not selections. Repository activity, artifact license,
Android integration maturity, correctness, and measurements on the exact iQOO
device decide the accepted bundle.

| Function | Candidate | Why it enters the benchmark | Required caution |
|---|---|---|---|
| English streaming STT | [Moonshine Voice](https://github.com/moonshine-ai/moonshine) small streaming model | Official Android example, native on-device streaming, and published models down to approximately 26 MB | Verify model/runtime licenses, bench-noise accuracy, endpointing, RSS, and sustained thermals |
| English streaming STT alternative | [sherpa-onnx](https://k2-fsa.github.io/sherpa/onnx/android/index.html) English Zipformer/CTC family | Mature Android-native offline ASR, VAD, and multiple model families behind one runtime | Select an English-only artifact and audit each model's separate license |
| English TTS | [KittenTTS](https://github.com/KittenML/KittenTTS) nano/micro | Apache-2.0 project, English-focused ONNX models from 15M parameters, with an advertised 25 MB INT8 nano artifact | Developer-preview API and reported INT8 issues require native Android conformance and fallback testing |
| English TTS alternative | [sherpa-onnx TTS](https://k2-fsa.github.io/sherpa/onnx/tts/index.html) | Existing Android-native offline TTS surface allows comparable lifecycle and audio plumbing | Voice/model licenses, footprint, phonemizer, and first-audio latency vary by model |
| Text LLM | [Qwen3-0.6B GGUF](https://huggingface.co/Qwen/Qwen3-0.6B-GGUF) | Small official quantized distribution suitable for constrained tool planning experiments | Validate instruction following, tool-schema reliability, context/RSS, license, and Android throughput |
| Text LLM runtime | [llama.cpp Android](https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md) or [MLC LLM Android](https://github.com/mlc-ai/mlc-llm/blob/main/docs/deploy/android.rst) | Both expose native Android paths; MLC targets mobile GPU compilation while llama.cpp provides a portable GGUF baseline | Backend choice follows measured correctness, startup, memory, energy, and device compatibility |
| Bounded VLM | [SmolVLM-256M](https://huggingface.co/HuggingFaceTB/SmolVLM-256M-Instruct) and [SmolVLM2-500M](https://huggingface.co/HuggingFaceTB/SmolVLM2-500M-Video-Instruct) | Small official multimodal candidates for ROI explanation and unsupported-case triage | Prove Android export, task accuracy, hallucination controls, memory, and license before inclusion |
| Board/component detector | compact YOLO-family detector exported to LiteRT, QNN, or NCNN | Strong mobile detector ecosystem and suitable custom-training path | Ultralytics code/artifacts may carry AGPL or commercial terms; legal compatibility and non-YOLO baselines are mandatory |
| Mobile inference | [LiteRT](https://ai.google.dev/edge/litert/android/npu), [Qualcomm AI Hub](https://app.aihub.qualcomm.com/docs/index.html), ONNX Runtime, or NCNN | Android CPU/GPU/NPU coverage for specialized vision and audio models | Do not assume the iQOO SoC or NPU; profile the physical device first |

Whisper is not the default. It may enter comparative evaluation, but the
English-only streaming candidates above are the primary starting point.

## Current voice implementation candidate

Issues #19 and #21 currently use Moonshine Voice 0.1.5 as the first executable
Android candidate: English tiny-streaming STT with spelling fusion and the
`piper_en_US-amy-low` voice. This selection is provisional until held-out speech
and exact-device measurements pass the published gates. The abstraction keeps
recognizer, synthesizer, audio input, focus, and orchestration separate so a
candidate can be replaced without changing the public voice contract.

Issue #18 adds the executable activation front end around that candidate:
adaptive local VAD, bounded pre-roll, configurable local wake-phrase matching,
audio-focus interruption, route reporting, and TTS/barge-in state restoration.
Its confidence output is intentionally not called calibrated until the frozen
quiet/bench-noise corpus and exact-device false-accept/false-reject gates pass.

## Local orchestration contract

The text LLM receives bounded conversation, compact observation summaries, and
the current capability schema. It can propose a tool and arguments, ask a
question, or produce conversational guidance. It cannot directly publish a
physical observation, normalize an electrical measurement, retain raw media,
or bypass confidence and safety policy.

The VLM receives only an explicit ROI or bounded still image and a task schema.
Continuous preview processing remains the responsibility of tracking,
registration, and compact detectors. All VLM claims are candidates until a
typed tool validates and calibrates them.

## Training and data plane

Python tooling will own:

- corpus import with license/source manifests;
- annotation validation and board/session grouping;
- training and calibration;
- export and numerical conformance checks;
- quantization and backend compatibility analysis;
- evaluation and report generation.

The device consumes immutable model bundles; it does not train in production.
Personal board captures are not uploaded or used for training without separate,
explicit consent and provenance.

## Export and conformance

Every candidate model must have:

- source/training revision and license;
- input/output contract and preprocessing digest;
- framework checkpoint digest;
- exported artifact digest;
- reference inference vectors;
- per-backend numerical tolerance results;
- quantization method and calibration corpus revision;
- supported operators and fallback behavior;
- measured device performance and accuracy deltas.

An export that loads is not sufficient. Device output must satisfy conformance
tolerances and end-task metrics.

## Backend selection

Backends are selected per device profile using a deterministic preference list
whose entries have passed conformance and hardware gates. A backend cannot be
chosen solely because it reports an accelerator. Selection records operator
fallback, thread count, precision, memory, warm-up, and thermal behavior.

Silent CPU fallback is prohibited when it violates the active latency/thermal
profile. The runtime reports degradation or omits the capability.

## Model lifecycle

Bundles are signed and content-addressed. Activation is transactional:

1. download or sideload into app-private staging;
2. verify signature, digest, manifest schema, license policy, ABI/backend, and
   minimum runtime version;
3. run compact conformance vectors;
4. atomically switch the active manifest;
5. retain one verified rollback bundle within storage policy;
6. emit activation or rollback evidence.

Model artifacts are never committed to Git.

## Calibration and abstention

Raw logits/scores are not user confidence. Calibration is evaluated on held-out
boards/sessions and versioned with the model. Identity-sensitive automation
uses a precision-first threshold. Coverage/abstention is a reported product
metric, not hidden missing data.
