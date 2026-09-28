# Local dialogue runtime

The `dialogue-runtime` Android library runs bounded text generation entirely
on-device. It has no downloader, network permission, cloud inference path, or
silent backend fallback.

## Current device profile

The working iQOO profile uses:

- GenieX Android `0.3.1`;
- Qualcomm's Qwen3 1.7B QAIRT w4a16 bundle for Snapdragon 8 Elite Gen 5;
- the bundle's declared SM8850 / HTP v81 / QNN 2.45 runtime contract;
- a complete nine-file artifact inventory checked for relative path, byte
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
real capability; a proposal is never execution.

## Input and output boundary

Inputs are bounded conversation turns, compact observation summaries, optional
retrieved design evidence, and host-declared capabilities. Raw camera frames and
audio never enter the text prompt. Generation is serialized, cancellable,
deadline-bound, output-limited, and deterministic for the active profile.

Conversation output is limited to plain spoken text. Control payloads, JSON
objects, tool tags, control characters, empty output, and oversized output are
rejected. Tool mode accepts exactly one response, clarification, or typed tool
proposal object and rejects unknown keys, tools, versions, arguments, and
deadlines. The deterministic measurement parser remains the only authority for
normalizing spoken electrical measurements.

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

The same manifest may carry an optional entity/fact knowledge graph compiled
offline from reviewed official data sheets, manuals, schematics, and SDK
documentation.
Every fact cites source pages. PDF text, table images, and the full graph are
never sent to Qwen; retrieval emits only the bounded matching records. Qwen is
therefore a natural-language renderer over admitted evidence, not the knowledge
store and not an authority for unsupported board facts.

The camera-call host is stricter than the general dialogue API: board questions
must retrieve an admitted record, and the spoken answer is constructed directly
from that record. It does not send unmatched speech or prior answers to the LLM.
A bounded, ambiguity-gated edit-distance recovery may match an ASR-corrupted
phrase against aliases carried by the signed board reference; otherwise the
host asks the user to repeat the component name. No transcript replacement or
board-specific vocabulary is compiled into the runtime.

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

```powershell
.\gradlew.bat :dialogue-runtime:testDebugUnitTest :dialogue-runtime:assembleDebug
```

The build requires a 64-bit JDK 17 and an Android SDK containing API 35. The
unit suite covers model inventory admission, path containment, protocol
bounds, strict output shapes, capability authorization, deterministic local
retrieval, evidence retention, and no-match behavior. Device instrumentation
runs five bounded turns against the admitted NPU bundle.
