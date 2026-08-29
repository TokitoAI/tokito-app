# Local dialogue runtime

The `dialogue-runtime` Android library is the executable baseline for issue
[#38](https://github.com/TokitoAI/tokito-physical-runtime/issues/38). It runs a
text model in-process through native llama.cpp. There is no remote inference or
cloud fallback.

## Model and runtime provenance

- Runtime: [`ggml-org/llama.cpp`](https://github.com/ggml-org/llama.cpp) pinned
  as a Git submodule at commit `cc83d7b4824f73cfdda4dfbb47ee39804f71b328`.
- Model: official
  [`Qwen/Qwen3-0.6B-GGUF`](https://huggingface.co/Qwen/Qwen3-0.6B-GGUF),
  revision `23749fefcc72300e3a2ad315e1317431b06b590a`, file
  `Qwen3-0.6B-Q8_0.gguf`.
- Expected size: `639446688` bytes.
- Expected SHA-256:
  `9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031`.

The model is not stored in Git. Release admission places a manifest and model
artifact in app-private storage before runtime initialization. The runtime reads
the manifest, verifies its declared size and SHA-256, and fails closed when the
bundle is missing or invalid. Inference is offline and has no download path.

## Contract and safety boundary

Inputs are bounded conversation turns, compact observation summaries, an
optional bounded design context, and host-declared capabilities. Raw camera or
audio payloads never enter this interface. Generation is serialized,
cancellable, deadline-bound, and output-limited.

The model may produce exactly one of three JSON outcomes:

1. conversational response;
2. clarification question;
3. typed tool proposal.

Strict parsing rejects prose outside the JSON object, unknown fields, invalid
types, and oversized values. A deterministic policy then rejects undeclared
tools, versions, arguments, missing required arguments, excessive deadlines,
and recently duplicated proposal IDs. A proposal is data, not execution. The
host remains responsible for authorization and the physical tool layer remains
responsible for measurements and observations.

The validation application intentionally declares no tools. It can load an
admitted model, send typed or recognized English text to the local LLM, display a
response or clarification, and pass that text to the local TTS runtime. It
cannot pretend that unfinished physical tools exist.

## Verification

```powershell
git submodule update --init --recursive
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :dialogue-runtime:testDebugUnitTest :dialogue-runtime:assembleDebug
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

The unit suite exercises the bounded input contract, exact output shapes,
typed arguments, capability allow-list, deadlines, and rejection of
measurement-like extra fields. CI compiles the arm64 native runtime and uploads
the AAR, APK, lint reports, and unit reports.

This is a runnable candidate, not accepted device evidence. Issue #38 and Gate
5 remain open until schema reliability, first-token latency, sustained token
rate, peak RSS, thermal behavior, cancellation, and offline behavior pass on
the declared iQOO device with the frozen evaluation set.
