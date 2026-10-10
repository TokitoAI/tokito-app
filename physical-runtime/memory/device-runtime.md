# Device runtime facts

The declared target was captured from connected hardware on 2026-08-29. Its
observed brand/model is iQOO I2501 (product I2501i), with QTI SM8850, Android
16 / SDK 36, `arm64-v8a`, and 15,594,224 KiB total memory. The Android camera
service reports five service-visible devices, two normal devices, and two
API1-visible devices; audio policy reports six inputs and four outputs.

The sanitized profile and raw-probe digests are recorded in
[`docs/DEVICE_PROFILE_I2501.md`](../docs/DEVICE_PROFILE_I2501.md). Raw ADB
output and the device selector remain local.

The NNAPI shell probe is unavailable on this build. This does not prove that
NNAPI or a vendor accelerator is absent. Operator support, native backend
choice, thread policy, latency, memory, fallback, and thermal behavior remain
unmeasured until issue #9's conformance benchmark.

The runtime selection mechanism is deliberately fail-closed: it accepts only a
caller-ordered backend candidate whose recorded conformance, lifecycle, and
thermal evidence digests match the active device-profile and ABI and report no
silent fallback. This mechanism does not constitute measured evidence or select
a backend for the captured device.

The native model registry accounts the active and one previously verified
rollback artifact together against its caller-owned storage quota. Activation
replaces the older rollback only after schema, runtime, ABI, digest, and
signature admission succeeds; failed upgrades preserve both slots. Rollback is
therefore a swap between already admitted bundles, never a verification bypass.

The shipping Android loader consumes only `filesDir/models/dialogue/active`
and `filesDir/models/vision/active`. `ModelBundleRepository` is the platform
admission boundary: the unified React Native shell passes only a document URI
and capability, while bounded copy, ECDSA signature verification, manifest
inventory/digests, runtime/ABI/license policy, capability-specific load
conformance, atomic activation, and rollback stay native. There is no legacy
directory fallback.

The shipping Activity reads Android's current `MemoryInfo` and thermal status
before loading dialogue and vision models. It reserves 512 MiB of system
headroom, rejects low-memory or severe-thermal admission, and reports moderate
thermal pressure as constrained concurrency. This is a safety preflight, not
the missing exact-device benchmark evidence.

**How to apply:** use the versioned profile capture procedure for each firmware
revision. Do not hardcode device, vendor/NPU, camera, ABI, or backend
assumptions into the runtime; unsupported paths must fail closed.

Python is approved for training, export, evaluation, and reference conformance,
not the shipping Android camera/audio hot path (ADR 0003).

The Android AR presentation boundary is `android/ar-runtime`; `app` is the
single user-facing host that composes AR, local dialogue, STT/TTS, and typed
measurements. `android/ar-demo` is manual-registration conformance tooling, not
a second product. Presentation consumes the same
versioned coordinate graph as camera/tracking and uses a latest-only scene
mailbox. Every overlay primitive is frame-, revision-, time-, expiry-, and
uncertainty-bound. Missing/stale transforms, lifecycle suspension, and a new
registration revision clear geometry rather than preserving the last visual.
The demo is conformance tooling and must never be cited as automatic vision.
The shipping app must not expose a synthetic-pose preview or substitute
`CalibrationBoardAsset` for a missing PCB. It may load that object only inside
an explicit measured calibration session using the matching printable 120 x
80 mm physical target. Product pose estimation takes metric board landmarks
only from the active validated project digital-twin package.
The shipping app may offer a clearly labelled unregistered inspection view of
that admitted project package, provided it creates no physical pose and clears
the view before registered guidance resumes.
Filament `ModelViewer` owns native teardown through its `TextureView` detach
listener. Hosts mark `DigitalTwinView` destroyed and stop scheduling frames,
but must not invoke `ModelViewer.destroy()` a second time during Activity
teardown; doing so double-frees the resource loader during configuration
recreation.
The admitted FRDM GLB's 681-node scene measured an 8.06 MiB peak low-level
command allocation on the declared iQOO. The renderer therefore provisions a
12 MiB minimum command buffer, a 36 MiB three-buffer arena, and a 32 MiB handle
arena. The manual inspection camera fits the signed package bounds and does not
create or retain a physical registration.

Baseline runtime inference is entirely local: text LLM, bounded VLM, compact
vision models, English STT, and English TTS. The executable speech candidate is
sherpa-onnx; Qwen3 1.7B QAIRT is the active text-dialogue profile and SmolVLM
256M/500M remain bounded visual-reasoning candidates. No candidate is admitted
as a release backend until license, export, conformance, and physical-device
gates pass.

The executable voice candidate is sherpa-onnx 1.13.6 using the English Kroko
streaming Zipformer2 transducer and LJSpeech medium INT8 VITS voice. Moonshine
Voice 0.1.5 was rejected after an uncatchable native `SIGILL` during physical
iQOO model load. Android capture, focus, AEC/noise suppression, typed transcript
events, synthesis replacement/queueing, and barge-in live in `voice-runtime`.
The AAR and models are hash-pinned and provisioned to app-private storage; no
network permission or cloud fallback exists. This is not an accepted release
backend until the connected iQOO passes issue #8 and Gate 4.

The executable text-dialogue candidate is Qualcomm's Qwen3 1.7B QAIRT w4a16
bundle through GenieX Android 0.3.1. Its full eleven-file inventory is verified
before loading and it runs on the declared iQOO's Snapdragon NPU. Device
instrumentation verifies bounded grounded answers. The previously recorded
6927-8114 prompt tokens/s and 125-139 decode tokens/s belong to the retired
0.6B profile, not this one. QAIRT grammar generation failed, so this profile is
conversational only and declares no tool capabilities. It is not an accepted
general release backend until issue #38 and Gate 5 pass.

The active FRDM detector is a manifest-bound YOLO11n pose export running through
LiteRT 2.2.0 on CPU. It supplies a four-corner board quad to the presentation
layer; the GLB follows that quad and clears after tracking expiry. Its manifest
explicitly marks the smoke evidence as not release-eligible, so it is an
experimental FRDM product path rather than proof of the general vision gate.

Q8_0 and Q4_K_M llama.cpp profiles were rejected for target-device packing
memory, startup, and ANR behavior. Q4_0 loaded but grammar generation failed and
unconstrained output could abort JNI through invalid UTF-8. These local
artifacts are quarantined evidence, not runtime fallbacks.

Local design retrieval is deterministic and bounded: exact reference keys are
authoritative, lexical evidence search is secondary, and each result carries
the verified context revision and source IDs. No FRDM facts are built into
runtime control flow or the APK.

Deterministic electrical measurement parsing lives in `measurement-runtime`.
`MeasurementVoiceSession` accepts only final STT segments, retains at most one
unconfirmed draft, speaks prompts through local TTS, and requires confirmation
before emitting `engineer_voice` evidence. Numeric, locale, unit, and
relationship normalization must never be delegated to the local LLM.

Release instrumentation is a separate R8 consumer: AndroidTest excludes target
dependencies even when production optimization merges their classes or rewrites
methods. Preserve reviewed cross-APK exports (including Kotlin multifile facade
delegate families) in `apps/mobile/proguard-release-abi.pro`, never disable R8
globally. Run the paired DEX class/method audit described in
`docs/RELEASE_DEVICE_QA.md` before installing both outputs. This linkage check is
not device execution or model-performance evidence.

Android's boot classpath owns `org.json`. The desktop `org.json:json` artifact
belongs only in host-test dependencies, never `implementation`. An installed
optimized corpus-import run failed because R8 renamed desktop `JSONTokener.nextValue`
to `e`, while Android resolved the class to its platform implementation. Removing
the packaged shadow fixes the boundary; preserving one renamed method is not
a substitute for removing the duplicate runtime class family.

GenieX 0.3.1 has no AAR consumer keep rules. Its native LLM creation consumes
private bean fields by original name. An optimized iQOO run crashed in native
`strlen` during creation while its mapping renamed `model_path`/`model_name`
and removed default fields. Preserve SDK bean/JNI classes and members via the dialogue
consumer rules. Validate the Java ABI before native creation and include SDK
mapping checks in release preflight. Never report NPU metrics from a run that
crashes at initialization.

Qwen3's official non-thinking guidance warns against greedy decoding. The
admitted QAIRT spoken profile uses temperature 0.7, top-p 0.8, top-k 20 and
min-p 0; model configuration and the SDK template both disable thinking.
Do not restore zero-temperature/top-k-one sampling based on an assumption that
determinism implies correctness. The installed electronics quality suite records
raw repeated answers and independent semantic rubrics; format checks are not
factual-accuracy evidence. Reviewed board facts still bypass generation.

GenieX init and model creation enter blocking vendor JNI; a coroutine timeout
around that child work cannot promise prompt completion. Initialization uses
one process-wide native-load admission and an isolated worker. Cancellation or
close revokes engine installation; a late result is disposed off the UI thread.
**Why:** real release instrumentation exceeded its 120-second init deadline
without producing a model answer. Screen lock was observed, but its causal role
was not established. Report fixed phases/timing and timeout state, never infer
that NPU execution succeeded or was physically interrupted.

Response ownership guards terminal state, bounded caller waiting and engine
disposal. The complete native response and cleanup run on an isolated worker;
the caller owns only a revocable receipt, not a structured child joining JNI.
Timeout/cancellation quarantines the runtime and rejects late output. The worker
retains process-wide admission until native work, cleanup and disposal finish,
blocking replacement model creation/generation as well as concurrent destruction.
Empty replacement loaders are atomically sealed and retired immediately without
joining the shared disposal queue; repeated recreation cannot retain unlimited
resource-free runtimes behind a stuck native call.
Close publishes terminal `Closed` immediately and does not join vendor cleanup.
Caller cancellation remains cancellation, including a shorter outer timeout;
only the owned receipt deadline maps to `GENERATION_TIMEOUT`.
**Why:** a real optimized awake device run exceeded both 60s generation and 65s
caller limits because vendor work or NonCancellable cleanup remained a child of
the caller. Fixed phase/timing diagnostics locate future stalls without request
text. A blocked native call may remain alive; bounded receipt termination is
not physical interruption, a factual-quality pass or proof of lock causality.

The optimized extended sequence subsequently exceeded the isolated receipt's
60s/65s deadlines after completing the raw corpus and two fresh turns. A shorter
passing sequence does not qualify sustained deadline behavior. The exact APK
contains the final isolated worker; phase-only logs did not establish causality.
The foreground-resume experiment below separates delayed scheduling from
receipt-handler overhead without identifying a private OEM mechanism.
**How to apply:** preserve fixed timer-setup/revocation-entry/exit diagnostics
and the independent one-thread observational Java watchdog. Compare its deadline
and fixed-role thread-state markers with a live thread dump before assigning
causality. The watchdog changes no result, cancellation or native ownership;
never log request data or treat a watchdog event as successful caller termination.

The same-process foreground-resume experiment released the delayed watchdog and
receipt timer together after roughly 278 seconds, with revocation/cancellation
then completing promptly. A resumed UI owner, not instrumentation importance or
screen-awake state, is required for raw-runtime QA. This supports process
scheduling suspension, not a particular OEM implementation or a JNI deadlock.
**How to apply:** construct raw dialogue only with a main-thread-bound RESUMED
LifecycleOwner. Pause/stop permanently revoke that runtime; resume is explicit
new ownership and still subject to process-wide native admission. Production
host `onStop` revokes voice/turn ownership synchronously before epoch-guarded
audio cleanup. Never introduce a hidden keepalive or replace raw factual-quality
failure with an abstention/scheduling pass.

## Exact Qwen tokenizer admission

The pinned GenieX Android 0.3.1 wrapper has no public encode/count API. Its
packaged `libgeniex-proc.so` does export the tokenizers-cpp C ABI. The local
dialogue bridge uses those existing symbols with the signed Qwen tokenizer,
counts the complete vendor template, and checks vendor completion receipts.
The QAIRT text pipeline also prepends the signed context's BOS token on a fresh
KV session unless the encoded prompt already starts with it. Include this rule
in prompt counts; do not add an empirical offset or allow count tolerances.
Tokito resets native KV between requests, making each request a fresh session.
The producer rule is documented with a pinned source link in
`docs/RELEASE_DEVICE_QA.md` at the repository root.
**Why:** MiniLM WordPiece, character ratios and stream chunks cannot measure
Qwen BPE tokens or prove output-cap enforcement.
**How to apply:** keep Qwen and its NPU backend unchanged; treat the tokenizer
and native ABI hashes as release pins. Use QNN System to enumerate all actual
signed binary graph variants and verify complete shard coverage; export JSON
attention/KV shapes describe representatives, not necessarily the maximum.
Qualify actual QNN graph selection before release. Do not reinterpret a source
positional maximum as compiled capacity or scan binary strings for admission.
Run the host exact-ID reference check and real Android JNI conformance tests;
host reference parity alone is not a device or generation-cap pass.

Conversation admission uses the actual 4,096-token signed graph capacity and
reserves 512 output tokens. The evidence character preflight permits up to
12,000 characters; it is not a tokenizer estimate or an authorization to exceed
the compiled context. If the complete formatted prompt overflows, oldest quoted
history is evicted and the candidate is re-tokenized. Current question, verified
evidence and output reservation are never silently reduced.
**Why:** low 64/128-token evaluation caps and the former 35-word speech rejection
could reject a complete native answer independently of available model context.
Final-answer extraction follows Qwen's last closing delimiter and keeps the
reasoning prelude out of speech and retained assistant history; unfinished
final-answer tags and incomplete native
responses still fail closed. Playback is not factual-quality qualification.

## Dataset reference selection

Signed embedding bundles may include the validated SQLite reference corpus and
its manifest. Bench reference questions use that admitted corpus and the same
384-dimensional encoder; they do not require a GLB or camera registration.
Qwen selects zero to four existing fact IDs in a single `facts` array. Native
code reconstructs speech from the cited source values. The selector cannot
authorize component highlights or invent physical identity.
**Why:** unfamiliar reference names must be answered from retrieved documentation,
not rejected based on model recall. Extra schema fields make small local-model
selection less reliable without adding authority. Whole-response JSON fences
are presentation wrappers only; unknown IDs and arbitrary prose still fail.
**How to apply:** keep facts/aliases in the pinned dataset, not board-specific
application branches. Test multiple references on the optimized installed APK,
including local playback, and retain independent physical-identity gates.
Evidence-ID selection uses deterministic top-1 decoding; the conversational
Qwen sampler remains separate. Do not use creative sampling for authoritative
ID classification: stochastic empty selections can incorrectly claim that an
available reference has no evidence.
