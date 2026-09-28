# Architecture facts

- Continuous camera work is lightweight tracking/registration/change detection.
- Metric 3D overlays consume a validated GLB package bound to the immutable
  assembly-context revision. Filament renders it from fresh calibrated board
  poses; the converter that produced the asset is outside the Android runtime.
- Heavy vision inference is explicit, ROI-bounded, deadline-bounded, and
  cancellable.
- The runtime emits structured observations with evidence and provenance;
  a local text LLM proposes dialogue/tool actions behind deterministic policy.
- The primary artifact is an embedded camera-first Android AR workspace plus
  reusable Android-native libraries inside the unified APK. Runtime ABI changes
  remain evidence-gated.
- Local text LLM, bounded local VLM, English STT, English TTS, visual models,
  orchestration, and evidence are required for offline baseline operation.
- Raw camera/audio stays on-device and ephemeral by default.

**How to apply:** reject implementations that run a whole-frame VLM continuously,
return untyped natural language, introduce an implicit network service, or make
Cloud availability a precondition for local physical workflows.
