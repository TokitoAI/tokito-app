# Local neural embedding artifacts and evaluation

These host tools prepare and evaluate the official
`sentence-transformers/all-MiniLM-L6-v2` encoder at pinned upstream revision
`1110a243fdf4706b3f48f1d95db1a4f5529b4d41`. They do not replace Qwen, authorize
unverified facts, recognize a physical board, or qualify a phone backend.

## Reproduce

Use Python 3.11 or newer, with the host-only dependencies in
`embedding-requirements.txt`. Keep artifacts and raw reports outside Git.

```powershell
python -m pip install -r physical-runtime/tools/embedding-requirements.txt
python physical-runtime/tools/prepare_embedding_bundle.py C:/Tokito/models/minilm-embedding-1110a243
python physical-runtime/tools/verify_embedding_reference.py C:/Tokito/models/minilm-embedding-1110a243
python physical-runtime/tools/evaluate_embedding_retrieval.py C:/Tokito/models/minilm-embedding-1110a243/payload C:/Tokito/models/minilm-embedding-1110a243/embedding.retrieval-evaluation.json
python -m unittest discover -s physical-runtime/tools -p 'test_embedding*.py' -v
```

Preparation downloads hash-pinned official weights, vocabulary, tokenizer and
model card. The four shipping payload files are `model.onnx`, `vocab.txt`,
`embedding.config.json` and `conformance.json`. Reference tokenizer/checkpoint,
provenance and export-conformance reports stay alongside, outside the payload.
The evaluator deliberately reads the independently hash-verified upstream
`reference/tokenizer.json`, not an invented tokenizer or a model binary as text.

The upstream tokenizer JSON enables fixed 128-token padding. The tool disables
padding before applying the native dynamic-sequence, 256-token truncation
contract. Independent PyTorch safetensors/ONNX parity checks eight inputs,
including accents, punctuation, Chinese, controls and true truncation. Pooling
is attention-masked mean followed by L2 normalization. Model-load success alone
is not numerical conformance.

Sign the exact payload only after export parity passes, using
`build_model_bundle.py --capability embedding`, the release P-256 secret key,
Apache-2.0 license metadata and the full model revision. Never commit or print
private keys. Device signature admission, tokenizer/numerical conformance,
native linkage, lifecycle and memory measurements are separate gates.

## Android JNI packaging (voice core remains unchanged)

The official `onnxruntime-android:1.27.0` JNI binary needs ELF symbols with
`VERS_1.27.0`. Sherpa 1.13.6's shipped core exports `VERS_1.27.1`; sharing the
library filename alone does not make those bindings compatible. There is no
official Maven 1.27.1 Android AAR. Do not use `pickFirst`, replace the voice core,
patch binary strings, or package a second ONNX core.

`dialogue-runtime:prepareEmbeddingOrtBindings` uses the stdlib-only
`prepare_embedding_jni.py` with Python 3.11+, JDK 17 and Android NDK
`27.1.12297006`. It downloads the official upstream source archive at commit
`df2ba1cf8108aa63627cf4cdf8f807880b938616` (v1.27.1), verifies its pinned SHA-256,
and builds its JNI C sources against each unchanged Sherpa core. The archive
cache is Gradle user home `caches/tokito-embedding-ort`; its first download is
approximately 293 MB. All four supported ABIs are built, not just the QA phone.
The configured Python executable can be set with `-Ptokito.embedding.python=...`.

The pinned source archive SHA-256 is
`2bc333947de2983a4c2d711af4c56c6286565ce7c1202fdfbda56c3431b5d63d`.
The script also verifies the complete official 1.27.0 Java AAR and Sherpa
1.13.6 AAR SHA-256 values before extracting any build inputs. Python 3.11+ must
be installed in the Android Buildkite image too, not only its contract-test
image. No pip dependencies are required for JNI preparation. Supported host
toolchains are Windows x86-64 and Linux x86-64; the Android SDK must contain the
pinned NDK before invoking Gradle.

The official 1.27.0 AAR's `classes.jar` remains byte-identical. The tool compares
all source class/member/native descriptors against the 1.27.1 Java source;
Android-generated `BuildConfig` and non-native compiler-generated `access$NNN`
bridges are excluded from that source comparison. Native methods are never
excluded. The rebuilt JNI must preserve every official JNI export, require
`VERS_1.27.1`, link `libonnxruntime.so`, and use at least 16 KiB LOAD alignment.
Only rebuilt `libonnxruntime4j_jni.so` files enter the generated `jni` directory;
the voice module remains the sole owner of `libonnxruntime.so`.

Gradle writes per-source hashes, artifact hashes, compiler versions, descriptor
digest and all-ABI linkage evidence to
`dialogue-runtime/build/generated/embedding-ort/embedding-jni.provenance.json`.
Scratch cores, headers and source stay outside packaged JNI paths. Source ZIPs
and generated libraries are build artifacts, not committed binaries.

### License and upstream attribution

ONNX Runtime Java, JNI and core are Microsoft ONNX Runtime under the **MIT
License**, copyright Microsoft Corporation. Rebuilding JNI does not remove
those license/notice obligations. The verified source archive's `LICENSE` is
extracted at `source-build/source/LICENSE`; its digest is recorded with the
other source hashes. Preserve the MIT text and copyright in the distributed
app's third-party acknowledgments, alongside the existing Sherpa and model
acknowledgments. The MiniLM model's Apache-2.0 metadata is separate from this
native-runtime MIT license.

Primary upstream references: [pinned source](https://github.com/microsoft/onnxruntime/tree/df2ba1cf8108aa63627cf4cdf8f807880b938616),
[MIT license](https://github.com/microsoft/onnxruntime/blob/df2ba1cf8108aa63627cf4cdf8f807880b938616/LICENSE),
and [official Maven Android version metadata](https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/maven-metadata.xml).

```powershell
# After installing the pinned NDK via Android SDK Manager:
cd physical-runtime
.\gradlew.bat :dialogue-runtime:prepareEmbeddingOrtBindings
```

Host packaging checks are not device qualification. The release APK still needs
inspection for one unchanged voice core per ABI, JNI load, signed embedding
admission and exact-device numerical conformance, plus voice regression tests.

## Frozen held-out evaluation

`embedding_retrieval_fixtures.json` freezes 4 synthetic authored project corpora,
24 source-reviewed component-role records and 64 queries. References, wiring
and board assignments are synthetic test authoring, not observed hardware.
Manufacturer documentation is cited for component roles. Queries were written
before running inference and were not used to tune the model or thresholds;
pretraining exclusion cannot be established for a public pretrained model.

The production thresholds remain **0.60 cosine / 0.08 top-two margin**. The
report preserves every query, expected label, all competing scores, decision,
abstention and host timing. Explicit reference and pin probes are kept separate:
raw vector similarity cannot establish identity or pin truth. This host tool
does not execute Kotlin identity guards; actual native/unit/device guard tests
must prove those independently.

The initial real FP32 model run at these frozen thresholds returned:

- 44 semantic queries: 24 positives and 20 unrelated/ambiguous negatives.
- 3 correct selections, 21 positive abstentions, 20 correct negative abstentions.
- No accepted wrong/false matches in this limited corpus; accepted positive
  recall was **12.5%**, not broad retrieval qualification.
- Raw top-one ranking identified 21/24 positives, but **87.5% raw ranking is not
  accepted-answer accuracy**. Threshold/margin rejection remains meaningful.
- 20 identity/pin probes abstained in the raw semantic layer. This is not proof
  that native reference/pin handling passed.

Host CPU latency is not iQOO NPU/CPU performance, and a safe abstention is not a
factual-accuracy improvement. The report status explicitly says evaluation is
complete, not that a release is qualified. Three accepted positives cannot
support a universal precision claim.

## Next calibration, without leaking this test

Keep this held-out corpus and thresholds unchanged. Add a separately versioned
calibration corpus, disjoint by project/entity families and paraphrase lineage,
before considering chunking, query shaping, thresholds or model changes. Freeze
its source and label digests. Any selected configuration must then run against
this untouched test and a new independently authored held-out set.

Evaluate **production hybrid retrieval versus semantic-only** on the same
frozen inputs, including lexical/exact-reference ordering, unknown identities,
ambiguous pins, stale context and near-duplicate components. Measure accepted
coverage, false matches, precision, abstention and exact-device cost together.
Do not lower thresholds to make these existing positives pass, add their wording
to runtime aliases, or treat model resemblance as source admission.
