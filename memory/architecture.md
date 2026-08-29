# Architecture facts

- Continuous camera work is lightweight tracking/registration/change detection.
- Heavy vision inference is explicit, ROI-bounded, deadline-bounded, and
  cancellable.
- The runtime emits structured observations with evidence and provenance;
  a local text LLM proposes dialogue/tool actions behind deterministic policy.
- The primary artifact is a standalone camera-first Android AR application plus
  reusable Android-native libraries. No package name or ABI is final until the
  runtime-foundation ADR/issue lands.
- Local text LLM, bounded local VLM, English STT, English TTS, visual models,
  orchestration, and evidence are required for offline baseline operation.
- Raw camera/audio stays on-device and ephemeral by default.

**How to apply:** reject implementations that run a whole-frame VLM continuously,
return untyped natural language, introduce an implicit network service, or
make Tokito/Companion availability a precondition for the standalone app.
