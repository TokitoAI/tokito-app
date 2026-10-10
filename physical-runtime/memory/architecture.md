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

Reviewed board answers use `LocalBenchDialoguePolicy` and admitted record text
directly. Do not restore a generative rephrase gate based only on matching
identifiers/numbers: that gate accepted contradictory qualitative claims and
spent an NPU turn on an already known answer. General electronics conversation
remains a separate bounded local-model path. Exact component-reference matches
outrank unrelated alias/placement matches without discarding aggregate evidence.
Unmatched active-bench questions conservatively request evidence; lexical
retrieval failure is not proof of general intent. Explicit general educational
questions can use the model, but references and board-relative wording cannot
bypass grounding. Retrieval is structured/lexical, not an embedding index.

**How to apply:** keep policy tests for injected instructions, unknown references,
contradictory voltage claims, and context-free general questions. Cloud-synced
text is not automatically a verified physical context. Preserve the existing
bundle admission, revision, evidence, and measurement-confirmation boundaries.

**How to apply:** reject implementations that run a whole-frame VLM continuously,
return untyped natural language, introduce an implicit network service, or make
Cloud availability a precondition for local physical workflows.
