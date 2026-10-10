# Installed release acceptance

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

After Rust/UniFFI generation and Expo Android prebuild, build the two APKs in
one invocation from `apps/mobile/android`:

```powershell
.\gradlew.bat :app:lintRelease :app:assembleRelease :app:assembleReleaseAndroidTest -Ptokito.deviceTests=true
```

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
