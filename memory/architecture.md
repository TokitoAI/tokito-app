# Architecture facts

- Continuous camera work is lightweight tracking/registration/change detection.
- Heavy vision inference is explicit, ROI-bounded, deadline-bounded, and
  cancellable.
- The runtime emits structured observations with evidence and provenance;
  reasoning remains outside.
- The planned consumer artifact is an Android-native library. No package name or
  ABI is final until the runtime-foundation ADR/issue lands.
- Raw camera/audio stays on-device and ephemeral by default.

**How to apply:** reject implementations that run a whole-frame VLM continuously,
return untyped natural language, or introduce an implicit network service.
