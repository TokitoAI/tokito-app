# Physical workspace architecture

> **Unified-product note (2026-09-29):** The runtime below is embedded in the
> single Tokito Android application. Historical "standalone" wording means its
> local workflows do not require Cloud; it does not authorize a second app,
> launcher, repository, or pipeline. See root `docs/decisions/0001-unified-mobile-application.md`.

## System boundary

```mermaid
flowchart TB
    subgraph Authoring[Optional production authoring path - never required at runtime]
        Desktop[Tokito project]
        Datasheets[Uploaded datasheets]
        DSVire[DS-ViRe evidence extraction]
        Design[PCB, BOM, placement and assembly data]
        Bundle[Versioned assembly context bundle]
        MobileShell[Unified Tokito mobile shell]
    end

    subgraph Product[Embedded offline workspace - tokito-app]
        App[Native physical activity]
        API[Versioned local API and capability manifest]
        Session[Session and dialogue coordinator]
        LLM[Local text LLM and constrained tool planner]
        Camera[Board discovery, tracking, and registration]
        ROI[Event-driven ROI scheduler]
        Vision[Local detector, classifiers, OCR, and bounded VLM]
        Audio[Local wake word, English STT, parser, and English TTS]
        Tools[Deterministic physical tool executor]
        Evidence[Observation and evidence store]
        Models[Verified local model registry]
        Device[Device capability and thermal governor]
        Overlay[Registered AR overlay renderer]
    end

    Board[Physical board and engineer]

    Desktop --> Design
    Desktop --> Datasheets
    Datasheets --> DSVire
    DSVire -->|part, package, pin and polarity evidence| Bundle
    Design -->|board geometry, refdes, centroid and rotation| Bundle
    Bundle -.->|optional verified local import| API
    MobileShell -->|typed low-rate native boundary| API
    App --> Session
    API --> Session
    Session --> LLM
    LLM -->|schema-constrained proposal| Tools
    Session -->|policy validation| Tools
    Tools --> ROI
    ROI --> Vision
    Camera --> ROI
    Camera --> Overlay
    Audio --> Session
    Models --> Vision
    Models --> Audio
    Models --> LLM
    Device --> Camera
    Device --> ROI
    Device --> Vision
    Device --> LLM
    Vision --> Evidence
    Audio --> Evidence
    Evidence --> Session
    Session --> Overlay
    Session --> Audio
    Board --> Camera
    Board --> Audio
    Overlay --> Board
    Audio --> Board
```

The 3D renderer consumes a digest-verified GLB whose metric origin and axes are
bound to the same immutable assembly context used by tools and observations.
The CAD/Gerber converter is replaceable tooling; it cannot be called from the
shipping Android hot path. Camera calibration and a fresh board pose compose
the asset into the camera frame. Invalid assets or registration loss suppress
3D content instead of retaining the last transform.

## Deployable artifacts

The unified repository produces, or is converging on, these artifacts:

1. one installable Tokito Android application with an embedded camera-first workspace;
2. internal Android libraries exposing reusable Kotlin APIs and lifecycle integration;
3. native runtime libraries selected by ABI and inference backend;
4. signed, digest-addressed model bundles for text LLM, visual models, English
   speech, and supporting models, with provenance manifests;
5. a Python development package for training, export, evaluation, and reference
   conformance only;
6. reproducible benchmark reports and a machine-readable capability manifest.

The root Expo application is the sole user-facing Android application. The
imported Physical `app` module is an internal library/activity, not a launcher.
Its camera-first activity composes `voice-runtime`, `measurement-runtime`, `dialogue-runtime`,
and `android/ar-runtime` in-process so AR, speech, dialogue, and typed tools
share one lifecycle and one interaction surface. `android/ar-demo` is only the
manual-registration conformance host and is not a second product. Their package coordinates remain
development coordinates until the runtime-foundation epic accepts the public
ABI. The active iQOO dialogue candidate uses GenieX 0.3.1 with the verified,
app-private Qwen3 1.7B QAIRT w4a16 NPU bundle. GGUF/llama.cpp candidates are
not selected automatically. Reviewed board answers bypass generation; ordinary
electronics conversation uses bounded plain text. QAIRT tool proposals are
rejected because constrained generation has not passed its device gate.
Automatic vision and remaining physical-tool artifacts remain gated work
rather than stubbed capabilities.

## Runtime layers

### 1. Device layer

Discovers camera formats, available accelerators, supported operators, memory,
thermal state, and audio capabilities. It chooses only a benchmarked execution
profile and reports degraded modes explicitly.

### 2. Continuous perception layer

Owns CameraX lifecycle, exposure/focus stabilization, frame timestamps,
automatic PCB/board discovery, lightweight feature tracking, board
registration, change detection, and coordinate transforms. It cannot invoke a
heavyweight model on every frame.

### 3. Event inference layer

Accepts a bounded ROI, task, deadline, and cancellation token. It performs
task-specific preprocessing and inference, then emits calibrated candidates and
evidence. Backpressure is latest-useful-work, not an unbounded frame queue.

### 4. Voice layer

Runs wake-word detection, voice activity detection, streaming English STT,
deterministic measurement parsing, and interruptible English TTS. Raw audio is
ephemeral unless the user explicitly enables a diagnostic capture.

Only final STT segments enter `MeasurementVoiceSession`. The session delegates
numeric and unit handling to `measurement-runtime`, speaks correction or
confirmation prompts through the same local TTS runtime, and accepts either a
bounded spoken yes/no response or an explicit touch decision. Partial STT
hypotheses cannot mutate measurement state.

### 5. Local dialogue and reasoning layer

Runs a quantized local text LLM with bounded context and schema-constrained
tool proposals. It combines local conversation, observation summaries, and
optional imported design context. Deterministic policy, not the LLM, authorizes
tools, validates units and arguments, enforces deadlines, and decides whether
an observation is sufficient. The baseline path has no remote fallback.

### 6. Physical tool layer

Validates tool arguments against session context and declared capabilities,
coordinates camera/audio operations, enforces deadlines, and returns exactly one
terminal result: observation, abstention, cancellation, or typed error.

### 7. AR presentation layer

Renders board acquisition, tracking quality, component targets, regions,
warnings, confidence, and step guidance over the live camera view. Rendering
consumes the canonical coordinate-frame graph and never invents independent
pixel transforms. Touch and speech are equal local interaction paths.

The renderer consumes immutable, sequence-ordered scenes through a bounded
single-slot mailbox. Every primitive carries a coordinate frame, transform
revision, observation time, expiry, and uncertainty. Scene projection is
suppressed when any required edge is missing, expired, non-invertible, or from
a different revision. Lifecycle suspension invalidates both registration and
pending scenes. See [AR_RUNTIME.md](AR_RUNTIME.md) and
[DIGITAL_TWIN_AR.md](DIGITAL_TWIN_AR.md).

### 8. Evidence layer

Maintains bounded session state: observations, coordinate frames, model/runtime
provenance, and user-confirmed measurements. It stores derived evidence by
default, not continuous raw media.

## Process and thread model

- Android lifecycle and public callbacks run on the application-managed main
  thread only where required.
- Camera acquisition never blocks on inference.
- Tracking uses a dedicated bounded worker and drops stale frames.
- Heavy inference uses a small scheduler with one active accelerator task per
  backend unless profiling proves safe concurrency.
- Audio capture and TTS are independently cancellable; TTS yields immediately
  to barge-in speech.
- Text LLM generation is cancellable, memory-bounded, and serialized against
  accelerator workloads according to the active device profile.
- Tool requests carry deadlines and structured cancellation.
- Observation delivery is ordered per session and idempotent by observation ID.

## Coordinate spaces

Every visual result declares its coordinate frame. Required spaces are sensor,
upright image, preview, ROI, registered board plane, and optional design/PCB
coordinates. Transforms include source/destination frame IDs, calibration
revision, timestamp, and uncertainty. A box without a frame ID is invalid.

## Integration policy

The primary integration surface is the unified Tokito application using the
runtime in-process. It requires neither a local HTTP server nor Python IPC.
An imported Tokito design package or future authenticated transport may add
design context, but loss or absence of that integration cannot disable generic
board discovery, local conversation, inspection, or AR guidance. The root
mobile shell already consumes the reusable API; exact-device gates still
govern capability claims, not whether a second app should exist.

The integration boundary is a versioned **assembly context bundle**, not a live
dependency on Tokito or DS-ViRe. Tokito owns design identity, BOM, board
geometry, reference designators, placements, and rotations. DS-ViRe contributes
source-grounded datasheet facts such as package constraints, pin-one and
polarity evidence. The physical runtime owns camera registration, physical
observations, dialogue, guidance, and abstention. It must never infer a board
placement from a datasheet or overwrite design truth with a visual guess.

See [RUNTIME_CONTRACT.md](RUNTIME_CONTRACT.md) for the external contract and
[decisions/](decisions/) for rationale.
