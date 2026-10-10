# Local dialogue runtime

The `dialogue-runtime` Android library runs bounded text generation entirely
on-device. It has no downloader, network permission, cloud inference path, or
silent backend fallback.

## Current device profile

The working iQOO profile uses:

- GenieX Android `0.3.1`;
- Qualcomm's Qwen3 1.7B QAIRT w4a16 bundle for Snapdragon 8 Elite Gen 5;
- the bundle's declared SM8850 / HTP v81 / QNN 2.45 runtime contract;
- a complete eleven-file artifact inventory checked for relative path, byte
  length, and SHA-256 before model creation; and
- `npu` as the build-validated compute unit.

The upstream release bundle is not committed to Git. Provisioning extracts it
to app-private storage and admits the bundle only when its schema-v2 manifest
matches all eleven expected files. Missing, extra, traversing, size-mismatched,
or digest-mismatched artifacts fail closed.

The earlier 0.6B profile completed five physical-device turns at 6927-8114
prompt tokens/s and 125-139 decode tokens/s. It has been replaced by the
manifest-admitted 1.7B profile; those old numbers must not be presented as 1.7B
benchmarks. Device instrumentation verifies bounded 1.7B FRDM answers, while
full thermal and accuracy measurements remain separate release evidence.

## Rejected target-device paths

The official Qwen3-0.6B Q8_0 GGUF and Q4_K_M candidates were unsuitable for
foreground startup because their llama.cpp packing paths caused excessive
memory, prolonged preparation, or an Android input ANR. The official Q4_0 GGUF
loaded through GenieX's llama.cpp runtime, but grammar generation failed and
unconstrained generation returned invalid UTF-8 that aborted inside JNI.
Those artifacts remain quarantined as local evidence and are not active.

QAIRT generation with a JSON grammar also failed inside the native runtime.
Consequently, the camera-call application uses QAIRT only for bounded plain-text
conversation with no declared tool capabilities. Tool proposals remain a
separate strict JSON protocol and are authorized only when the host declares a
real capability and the runtime supports constrained generation; a proposal is
never execution. QAIRT rejects tool mode before invoking the backend, rather
than asking unconstrained generation to imitate the tool schema.

## Input and output boundary

The GenieX AAR's JNI beans are an external binary contract. GenieX 0.3.1 does
not supply consumer shrink rules, while native creation reads private fields
such as `model_path` by their original JVM name. The dialogue module preserves
the SDK `bean` and `jni` boundaries under production R8. Before entering native
creation, `requireGenieXJniAbi` validates the pinned field and entrypoint ABI;
a mismatch fails model admission instead of attempting a potentially fatal
native lookup. Release mapping preflight and installed instrumentation cover
this boundary; debug-only inference does not prove a minified release safe.

Inputs are bounded conversation turns, compact observation summaries, optional
retrieved design evidence, and host-declared capabilities. Raw camera frames and
audio never enter the text prompt. Generation is serialized, cancellable,
deadline-bound and output-limited. The admitted Qwen3 QAIRT conversational
profile uses non-thinking sampling (temperature 0.7, top-p 0.8, top-k 20,
min-p 0), following the [official model guidance](https://huggingface.co/Qwen/Qwen3-1.7B).
It is not deterministic greedy generation. Thinking is disabled in model
configuration and the SDK chat-template call; use the admitted template rather
than hand-building ChatML. Boolean diagnostics expose template markers without
logging conversation content. Both spoken prompts request one sentence of at
most 35 words; output limits are fail-safes, not automatic summarization.
Never truncate a long answer and claim it is a complete factual response.

Conversation output is limited to plain spoken text. Control payloads, JSON
objects, tool tags, control characters, empty output, and oversized output are
rejected. Tool mode accepts exactly one response, clarification, or typed tool
proposal object and rejects unknown keys, tools, versions, arguments, and
deadlines. The deterministic measurement parser remains the only authority for
normalizing spoken electrical measurements.

Initialization has a 120-second end-to-end deadline, including serialization and
inventory verification. Blocking vendor JNI runs on an isolated bounded worker,
not a child coroutine which could prevent the deadline from returning. Timeout
and caller cancellation revoke installation and preserve coroutine cancellation;
state distinguishes model-load timeout/cancellation from model integrity failure.
There is at most one native load in flight across runtime recreation. While an
expired JNI call is still finishing, another load is rejected rather than queued.
This does not claim to interrupt vendor JNI: any late engine is disposed on its
worker and cannot replace a newer session or revive a closed runtime. Model
creation, disposal and phase diagnostics never block the UI thread. Diagnostics
contain only fixed phase names and elapsed milliseconds, not prompt/model text.
Lock-screen conditions must be recorded separately; a timeout is not proof that
the operating system suspended the NPU.

## Local design retrieval

`LocalDesignContextIndex` accepts records only after an assembly-context bundle
has been verified. It preserves the context revision, authority, exact keys,
source IDs, and source-page locators. It builds token postings once when the
context is activated, prioritizes exact reference-designator and alias matches,
then uses deterministic bounded token overlap. No match returns no context
instead of injecting the whole board corpus. Unqualified pin numbers resolve
only when exactly one connector contains that pin number; ambiguous pins fail
closed and require the connector name.

A reviewed board-reference manifest may be activated at the app-private
`contexts/active.board-reference.json` path. Android verifies the same canonical
SHA-256 payload used by `tools/board_reference.py`, rejects unknown fields,
unreviewed evidence, broken pin maps, and invalid placement claims, then indexes
all connector pins and landmarks. Guide-relative targets remain explicitly
unbound and never become authored board-plane coordinates. Requested reference
designators must occur in the retrieved records or dialogue returns a
clarification without invoking the model.

A project-bound physical session must explicitly match that manifest's immutable
context revision before using its facts or digital twin. A missing or different
revision leaves the board reference unavailable; it never adopts the globally
active board as evidence for another project. Projectless local bench sessions
can use the locally admitted reference without requiring Cloud.

The same manifest may carry an optional entity/fact knowledge graph compiled
offline from reviewed official data sheets, manuals, schematics, and SDK
documentation.
Every fact cites source pages. PDF text, table images, and the full graph are
never sent to Qwen; retrieval emits only the bounded matching records. Qwen is
therefore a natural-language renderer over admitted evidence, not the knowledge
store and not an authority for unsupported board facts.

The camera-call host is stricter than the general dialogue API: grounded board
answers are returned directly from admitted records, with no model rephrase.
An identifier/number-preserving rewrite is not proof of factual equivalence:
it can invert a voltage limit or invent qualitative claims. Bypassing generation
also avoids NPU prefill/decode work for these exact factual answers. No latency
or token-rate improvement is claimed without an exact-device report.

An explicit unknown reference or unresolved board-specific question returns a
clarification, not a general-knowledge answer about the user's board. General
electronics questions remain available through the local LLM without a board
reference. In an active bench session an unmatched question is conservatively
board-bound, not evidence of general intent. Only explicit educational phrasing
(for example "Explain capacitance", "How does a resistor work?", or "In general,
...") may select the general local-model route; explicit component references
and deictic board wording still require evidence. The guard validates input
bounds before either route. This is structured/lexical retrieval, not vector
search, and embeddings are not required to enforce that boundary.
A bounded, ambiguity-gated edit-distance recovery may match an ASR-corrupted
phrase against aliases carried by the signed board reference; otherwise the
host asks the user to repeat the component name. No transcript replacement or
board-specific vocabulary is compiled into the runtime.

Exact reference-key matches are selected before unrelated alias/placement
matches. Having a highlight coordinate cannot redirect a question about one
component to another. Tool versions/deadlines must be JSON integers (no string
or fraction coercion); numeric arguments must be finite. Conversational output
rejects reasoning/control tags and fenced or array-shaped control payloads.
Context records must retain an explicit authority and nonempty source list;
source-page locators must refer to those sources. An exact-key-only object is
not accepted as evidence. This structural validation does not replace bundle
signature/integrity admission or authorize arbitrary synchronized text.

This implementation intentionally does not add an embedding model. A single
board's structured graph and placement records are small, exact keys
are safer for identity, and an additional model would consume memory without
yet having measured retrieval-quality evidence. A local embedding fallback may
be added later behind the same record contract if held-out queries prove the
lexical path insufficient. Design evidence is quoted data, never prompt
instructions, and the LLM cannot turn datasheet evidence into placement truth.

An authored assembly-context package and digital twin are not built into the
APK. They become eligible for local activation only after the generic importer,
authenticated-integrity, coordinate-frame, and source-license policies pass.

## Verification

Qualcomm's [GenieX runtime guide](https://github.com/qualcomm/GenieX/blob/main/notes/run.md)
distinguishes QAIRT bundles from GGUF/llama.cpp models. Its hybrid/thread/layer
advice for llama.cpp is not an optimization recipe for this QAIRT NPU bundle.
Upstream power-profile controls also require verifying that the pinned Android
SDK exposes them and recording exact-device latency, RSS, thermal, cancellation,
and factual-quality evidence before enabling them. The
[QAIRT plugin](https://github.com/qualcomm/geniex-qairt-plugin) publishes its
runtime dependency separately. Do not upgrade the SDK or model/runtime pairing
just to copy a benchmark from a different device.

```powershell
.\gradlew.bat :dialogue-runtime:testDebugUnitTest :dialogue-runtime:assembleDebug
```

The build requires a 64-bit JDK 17 and an Android SDK containing API 35. The
unit suite covers model inventory admission, path containment, protocol
bounds, strict output shapes, capability authorization, deterministic local
retrieval, evidence retention, and no-match behavior. Device instrumentation
runs bounded turns against the admitted NPU bundle. The unified release's
`LocalModelsDeviceTest.localElectronicsQualityEvidence` records 12 additional
general-electronics cases twice, predeclared semantic rubrics, raw answers,
timing, and spoken-format checks. It is execution/adherence testing plus review
evidence, not an automatic semantic accuracy score. Review all answers for
contradictions and unsupported claims before claiming local-model quality;
passing three definitions or a JNI load gate is insufficient.
