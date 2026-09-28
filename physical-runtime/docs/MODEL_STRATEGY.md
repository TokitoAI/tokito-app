# Model strategy

## Principle: complete local intelligence, composed efficiently

Continuous camera work and event-driven semantic inference have different
latency, power, and accuracy requirements. Every baseline capability runs on
the phone, including text reasoning, visual reasoning, English recognition, and
English synthesis. The runtime still uses a composed pipeline instead of
forcing one VLM to handle tracking, component identity, LED state, speech, and
guidance.

## Signed bundle production

Release engineering builds the exact offline ZIP consumed by Android with
`tools/build_model_bundle.py`. The tool inventories every artifact, writes
canonical manifest bytes, signs those bytes with the Tokito P-256 release key
through OpenSSL, and emits no private material:

```sh
python tools/build_model_bundle.py ./dialogue-payload ./tokito-dialogue.zip \
  --signing-key /secure/tokito-model-signing-p256.pem \
  --capability dialogue --model-id tokito-dialogue --model-revision 2026.09
```

The signing key belongs only in the release secret store. It must never enter
Git, an APK, a model bundle, logs, or CI artifacts. The app embeds only the
corresponding public verification key.

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
| English streaming STT | [sherpa-onnx](https://k2-fsa.github.io/sherpa/onnx/android/index.html) English Zipformer family | Mature Android-native offline ASR and published hash-addressed Android/model releases | Audit the selected model license and benchmark bench-noise accuracy, endpointing, RSS, and thermals |
| English streaming STT rejected candidate | [Moonshine Voice](https://github.com/moonshine-ai/moonshine) | Retained only as comparative history | Rejected after an uncatchable native `SIGILL` during physical-device TTS model load |
| English TTS | [KittenTTS](https://github.com/KittenML/KittenTTS) nano/micro | Apache-2.0 project, English-focused ONNX models from 15M parameters, with an advertised 25 MB INT8 nano artifact | Developer-preview API and reported INT8 issues require native Android conformance and fallback testing |
| English TTS alternative | [sherpa-onnx TTS](https://k2-fsa.github.io/sherpa/onnx/tts/index.html) | Existing Android-native offline TTS surface allows comparable lifecycle and audio plumbing | Voice/model licenses, footprint, phonemizer, and first-audio latency vary by model |
| Text LLM | Qualcomm Qwen3 1.7B QAIRT w4a16 | Official SM8850/HTP v81 bundle fits bounded local conversation and passes physical-device functional checks | QAIRT grammar generation is rejected; never expose schema-emitting tools through the conversational profile |
| Text LLM runtime | GenieX Android 0.3.1 with QAIRT | Uses the target Snapdragon NPU and admits a complete hash-addressed artifact inventory | Backend remains bound to its declared SoC/runtime profile; GGUF fallback is not silently selected |
| Bounded VLM | [SmolVLM-256M](https://huggingface.co/HuggingFaceTB/SmolVLM-256M-Instruct) and [SmolVLM2-500M](https://huggingface.co/HuggingFaceTB/SmolVLM2-500M-Video-Instruct) | Small official multimodal candidates for ROI explanation and unsupported-case triage | Prove Android export, task accuracy, hallucination controls, memory, and license before inclusion |
| Board/component detector | compact YOLO-family detector exported to LiteRT, QNN, or NCNN | Strong mobile detector ecosystem and suitable custom-training path | Ultralytics code/artifacts may carry AGPL or commercial terms; legal compatibility and non-YOLO baselines are mandatory |
| Mobile inference | [LiteRT](https://ai.google.dev/edge/litert/android/npu), [Qualcomm AI Hub](https://app.aihub.qualcomm.com/docs/index.html), ONNX Runtime, or NCNN | Android CPU/GPU/NPU coverage for specialized vision and audio models | Do not assume the iQOO SoC or NPU; profile the physical device first |

Whisper is not the default. It may enter comparative evaluation, but the
English-only streaming candidates above are the primary starting point.

## Current voice implementation candidate

The executable voice candidate is sherpa-onnx 1.13.6 with the English Kroko
streaming Zipformer2 transducer and LJSpeech medium INT8 VITS voice. The runtime
AAR and both model archives are pinned to upstream-published SHA-256 values.
Moonshine Voice 0.1.5 was removed after its bundled ONNX Runtime reproduced an
uncatchable `SIGILL` on the physical iQOO during Piper model load. The app has no
network permission and fails closed for absent, corrupt, path-unsafe, or
unsupported bundles. This selection remains provisional until held-out speech
and exact-device measurements pass the published gates.

The executable dialogue profile uses GenieX 0.3.1 and Qualcomm's Qwen3 1.7B
QAIRT w4a16 bundle on the Snapdragon NPU. Admission verifies the complete
eleven-file inventory and the bundle's declared SM8850, HTP v81, and QNN 2.45
contract. Device instrumentation verifies bounded grounded answers. The
6927-8114 prompt tokens/s and 125-139 decode tokens/s measurements belong to
the retired 0.6B profile and are not 1.7B performance evidence.

Q8_0 and Q4_K_M llama.cpp candidates were rejected for excessive packing
memory, prolonged foreground preparation, or ANR behavior. The official Q4_0
candidate loaded but failed grammar generation; unconstrained output could
reach JNI as invalid UTF-8 and abort the process. No rejected GGUF artifact is
an automatic fallback for the active QAIRT profile.

The camera-call build profile uses a validated 60-second fail-safe rather than a
short conversational cutoff. Physical testing showed both the generic
four-second API default and a 15-second profile could expire after prompt
evaluation and cancel generation before a complete typed response. The runtime
retains its generic four-second default; the hands-free workflow uses the larger
bounded limit so cancellation, shutdown, and runaway protection remain defined
without truncating ordinary local turns.

QAIRT grammar generation failed in physical testing, so the working profile is
restricted to bounded plain-text conversation with no tool capabilities. The
strict typed proposal parser remains available for a separately validated
schema-constrained backend; it is not bypassed or weakened for QAIRT.

The camera-call profile caps generated dialogue at 128 tokens and asks for at
most two short spoken sentences. Both values are workflow build configuration,
validated against the reusable runtime limits.

Verified assembly-context facts are retrieved locally before generation. Exact
reference keys outrank deterministic bounded text overlap, every result retains
its context revision and source IDs, and no-match queries inject no corpus. An
embedding model is deferred until held-out retrieval evaluation proves that the
small structured per-board corpus needs one.

## Detector candidate status

The FRDM product slice currently runs a manifest-bound YOLO11n pose export
through LiteRT 2.2.0 on CPU. Its four ordered keypoints drive the live board
quad and tracked GLB presentation. The manifest marks its smoke evidence as
not release-eligible, so this is an experimental board-specific path rather
than selection of YOLO as the general vision architecture.

The vision architecture remains composed: deterministic registration,
tracking, coordinate transforms, candidate object detection, task-specific
classification, bounded local VLM fallback, calibration, and typed validation
remain separate stages.

A compact YOLO-family detector is a candidate for learned board/component
object detection because the mobile detector ecosystem is mature and export
paths can target Android runtimes. It is not responsible for board-plane
registration or design identity. Any YOLO implementation must pass license
review, and at least one non-YOLO detector baseline must be evaluated on the
exact iQOO target device before a model decision is recorded.

Initial non-YOLO baselines may include EfficientDet-Lite or a MediaPipe
MobileNet object-detector family when their exact code, model, and training
artifact licenses satisfy release policy. These candidates still need the same
Tokito detection contract, calibration policy, export evidence, and iQOO
benchmark results as any YOLO-family candidate.

The intended physical-vision path is:

```text
camera frame -> deterministic board registration -> candidate detector ROI
             -> board coordinate -> assembly-context identity mapping
             -> typed observation or abstention
```

## Local orchestration contract

The current executable baseline is documented in
[Local dialogue runtime](DIALOGUE_RUNTIME.md): Qwen3 1.7B QAIRT w4a16 through
GenieX 0.3.1, with complete model-inventory checks, bounded serialized
generation, strict conversational output rejection, and deterministic local
design retrieval. It remains a candidate until issue #38 and Gate 5 pass.

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

The unified Android app exposes model provisioning under **Local models**. It
uses Android's document picker, so the app never gains broad storage access.
Each ZIP carries `model.manifest.json`, `model.manifest.sig`, and the exact
manifest inventory. The signature is ECDSA P-256/SHA-256 over the manifest
bytes. The release public key is embedded in the runtime; its private key is
kept outside Git. Dialogue and vision activate only from their verified
`models/<capability>/active` slots. Direct files under `models/dialogue` or
`models/vision` are deliberately ignored, and rollback swaps only the retained
previous verified slot.

## Calibration and abstention

Raw logits/scores are not user confidence. Calibration is evaluated on held-out
boards/sessions and versioned with the model. Identity-sensitive automation
uses a precision-first threshold. Coverage/abstention is a reported product
metric, not hidden missing data.
