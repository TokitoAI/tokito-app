# Installed release acceptance

Bench Start must be checked from both project-attached and projectless UI routes.
`PhysicalSessionBridgeDeviceTest` additionally exercises a real React Native
ReadableNativeMap through Expo conversion, including nullable/absent fields,
opaque identities, unknown keys, wrong types, invalid parent relationships and
UTF-8 bounds. These device tests do not substitute for opening the actual bench.
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
not replace installed runner acceptance, cover arbitrary reflective calls, or
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
