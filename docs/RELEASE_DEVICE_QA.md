# Installed release acceptance

`LocalQwenTokensDeviceTest` is the exact-Qwen token-budget qualification slice.
It runs in the optimized unified app's signed-model sandbox, not the library
instrumentation sandbox. Its first test verifies the actual QNN System binary
inventory (including complete 4096 graph variants) and eight exact native token-ID
checksums against the pinned host tokenizer reference. Its second test exercises
the production native token-receipt boundary at output caps 64, 128 and 192 with
two independent prompts each. Success requires available exact prompt counts
and bounded generated-token counts; stream callback counts are not receipts.
Complete-response probes explicitly request short answers. A separate eight-token
probe requires typed output-limit rejection after exact receipt validation; a
truncated answer is never counted as a complete-response pass. Preserve failed
earlier runs instead of replacing their evidence with a later concise-prompt run.
Complete input counts also include the QAIRT backend's first-turn BOS insertion,
derived from signed `genie_config.json`, only when the encoded prompt does not
already start with that token. Tokito resets KV between requests, so each request
uses this fresh-session rule. This is not an empirical one-token tolerance:
native counts must still match exactly. See the pinned
[Qualcomm pipeline implementation](https://github.com/qualcomm/geniex-qairt-plugin/blob/15d6e053a62d88b6f7b72c1d5eb9706b299d3012/core/src/pipeline/llm_pipeline.cpp).
The paired DEX audit explicitly checks its five named reflective methods, with
narrow release keeps rather than a new package-wide testing whitelist.
Its separate test program also consumes the named Kotlin array/file multifile
facades, coroutine boxing helper, and two program-owned JSON classes. These
exports retain their exact ABI; renamed copies are not treated as proof that a
framework class can satisfy the test dependency.
These are additional qualification checks, not replacements for the baseline
or factual-quality review. Until the installed runner passes them, the draft
token-budget slice is not device-qualified and must not be merged or shipped.

```powershell
adb -s <serial> shell am instrument -w -r -e class dev.tokito.app.LocalQwenTokensDeviceTest dev.tokito.app.test/androidx.test.runner.AndroidJUnitRunner
```

`LocalEmbeddingDeviceTest` is an additional installed-device gate for the signed
Knowledge search bundle. Run it explicitly after importing that bundle into the
same optimized target. It checks native tokenizer/numerical conformance, twenty
real query encodings, revision-bound SQLite reload and missing-identity rejection.
Its fixed metrics report encoder load/query latency, process PSS and native heap;
those do not include all accelerator/system memory or prove sustained thermal
performance. Synthetic fixture retrieval is not physical-board recognition.
Preserve separate Qwen factual-quality failures and rerun voice tests because
the Java embedding bindings use the existing shared ONNX core.

Bench Start must be checked from both project-attached and projectless UI routes.
`PhysicalSessionBridgeDeviceTest` additionally exercises a real React Native
ReadableNativeMap through Expo conversion, including nullable/absent fields,
opaque identities, unknown keys, wrong types, invalid parent relationships and
UTF-8 bounds. These device tests do not substitute for opening the actual bench.
The converter harness constructs the exact nullable Map/String/nullable-Any
SDK descriptors explicitly. This removes test-only Pika/reflection setup, not
the real Expo conversion; strict paired DEX validation remains mandatory.
`LocalBenchPolicyDeviceTest` verifies production evidence/abstention against the
same twelve electronics questions as the raw evaluation, without replacing its
failed quality results or invoking the model.

Device acceptance runs against the optimized unified `dev.tokito.app` release,
not a separate debug application. `apps/mobile/proguard-release-abi.pro` keeps
only the declared cross-APK runtime exports stable. Shrinking, resource
shrinking and production optimization remain enabled. The Expo plugin applies
the same rules with or without the device-test property.

The AndroidTest R8 configuration applies the release name mapping. That does
not reconstruct optimized parameter order or narrower argument types. A
`NoSuchMethodError` before the runner starts is a failed acceptance run, not
evidence about the local model. Inspect both mapping/configuration outputs and
fix the explicit binary boundary instead of disabling R8 globally.
Likewise a shared target dependency can be removed by production R8 while the
test APK excludes it as already supplied by the target. The runner's
`androidx.tracing.Trace` entrypoints are kept narrowly for this reason; inspect
`usage.txt` when startup reports `NoClassDefFoundError` before a test runs.
The checked ABI also preserves the actual shared dialogue/voice consumer APIs
and named Kotlin multifile facade/delegate families referenced by the test DEX.
This is not a Kotlin/package-wide keep. AndroidTest excludes target libraries,
so it cannot restore a target class or method R8 removed or signature-rewrote.

After Rust/UniFFI generation and Expo Android prebuild, build the two APKs in
one invocation from `apps/mobile/android`:

```powershell
.\gradlew.bat :app:lintRelease :app:assembleRelease :app:assembleReleaseAndroidTest '-Ptokito.deviceTests=true'
```

Before installation, from `apps/mobile`, check the paired outputs:

```powershell
python scripts/release-test-abi.py --target android/app/build/outputs/apk/release/app-release.apk --test android/app/build/outputs/apk/androidTest/release/app-release-androidTest.apk --mapping android/app/build/outputs/mapping/release/mapping.txt --usage android/app/build/outputs/mapping/release/usage.txt
```

The DEX audit checks shared class identity and method resolution (including
facade inheritance); it rejects missing exports and changed signatures. `--rules`
prints exact referenced classes for review when acceptance APIs change. It does
not replace installed runner acceptance, cover arbitrary reflective calls beyond
the explicitly declared exact-Qwen test entrypoints, or
prove model quality. Do not install a pair that fails this preflight.

Record the source commit, both APK SHA-256 values, signing identity and release
`mapping.txt`. Install **both outputs from that invocation** with `adb install
-r` using the explicit device serial. Do not mix an earlier installed target
with a newly compiled test APK. Do not uninstall/clear the target: this removes
admitted models, saved projects and account state.

Invoke the installed runner directly, for example:

```powershell
adb -s <serial> shell am instrument -w -r -e class dev.tokito.app.LocalGroundingDeviceTest#reviewedFactsAndAbstentionsNeverNeedGeneration dev.tokito.app.test/androidx.test.runner.AndroidJUnitRunner
```

Verify an actual test result, not just build/install success. Then exercise the
installed model manager, measured NPU response/cancellation suites, project
sync/chat/schematic UI and process restart. Record unavailable exact-device
checks as pending. Managed-device/UTP cleanup must not delete provisioned
app-private models during this acceptance stage.

The unified release packages nine baseline checks: the two
`PhysicalSessionBridgeDeviceTest` tests, one `LocalBenchPolicyDeviceTest`, two
`LocalGroundingDeviceTest` tests, and these four `LocalModelsDeviceTest` methods:
`vendorJniBeanAbiSurvivesReleaseOptimization`, `installedModelsResolveInUnifiedRelease`,
`npuGeneratesMultipleFreshResponses`, and `localSpeechLoadsAndPlaysAudio`.
Select those methods explicitly when running the baseline. Selecting the entire
LocalModels class also runs the separate raw electronics-quality evaluation,
whose known failed factual/format evidence must remain failed. That evaluation
is an additional tenth test, not an acceptance substitute.
Legacy `dev.tokito.physical` subtree test classes are not packaged into the
unified runner. Selecting those absent classes reports selector errors, not an
application regression or a valid expanded hardware suite.

```powershell
$cases = @(
  'dev.tokito.app.PhysicalSessionBridgeDeviceTest',
  'dev.tokito.app.LocalBenchPolicyDeviceTest',
  'dev.tokito.app.LocalGroundingDeviceTest',
  'dev.tokito.app.LocalModelsDeviceTest#vendorJniBeanAbiSurvivesReleaseOptimization',
  'dev.tokito.app.LocalModelsDeviceTest#installedModelsResolveInUnifiedRelease',
  'dev.tokito.app.LocalModelsDeviceTest#npuGeneratesMultipleFreshResponses',
  'dev.tokito.app.LocalModelsDeviceTest#localSpeechLoadsAndPlaysAudio'
)
adb -s <serial> shell am instrument -w -r -e class ($cases -join ',') dev.tokito.app.test/androidx.test.runner.AndroidJUnitRunner
# Separate raw quality evidence; the current candidate is unqualified.
adb -s <serial> shell am instrument -w -r -e class 'dev.tokito.app.LocalModelsDeviceTest#localElectronicsQualityEvidence' dev.tokito.app.test/androidx.test.runner.AndroidJUnitRunner
```

Raw NPU evaluation obtains and verifies an actual focused RESUMED target
Activity before admission and each turn, and binds runtime ownership to that
Activity's lifecycle. `startActivity` plus `waitForIdleSync`, a lit display or
instrumentation importance alone does not prove foreground ownership. Losing
that owner cancels/closes the runtime rather than waiting invisibly in the
background or resuming it as successful. Model loading, timer/worker markers,
and activity lifecycle must be compared when diagnosing a delayed turn.
The independent diagnostic watchdog cannot run in a suspended process either.
Never wake a stalled run and reinterpret the delayed cancellation as a passing
deadline. Record interruption, resume and failed deadlines honestly, then run
the proper foreground-owned scenario again; do not change device power policy.
