# Android release

Tokito Physical Runtime releases are built from a reviewed `main` commit. Model
binaries, board assets, signing keys, passwords, and device logs are never
committed to Git or embedded by a build script.

## Release inputs

A release records the source commit, application version, APK SHA-256, signing
certificate SHA-256, admitted model manifests, active board-reference revision,
digital-twin digest, target device profile, and verification results. A release
does not promote an experimental model manifest into calibrated evidence.

The Android build reads version overrides from `tokito.versionCode` and
`tokito.versionName`. A signed release additionally requires all four values:

- `TOKITO_RELEASE_STORE_FILE`
- `TOKITO_RELEASE_STORE_PASSWORD`
- `TOKITO_RELEASE_KEY_ALIAS`
- `TOKITO_RELEASE_KEY_PASSWORD`

Supplying only part of the signing configuration fails the build. Credentials
must come from the operator environment or an approved secret store.

## Verification

```powershell
.\gradlew.bat clean testDebugUnitTest lintDebug assembleDebug assembleRelease
python tools/validate_governance.py
python -m unittest discover -s tools -p "test_*.py"
```

Verify the signed APK with Android SDK `apksigner`, install the exact digest on
the declared device, run the instrumentation suites, exercise the offline
camera-call flow, and review `logcat` for crashes, ANRs, native faults, model
admission failures, and frame or audio leakage. Publish the APK and a SHA-256
sidecar only after the pull request is merged and the `main` workflows pass.

App-private model bundles are preserved only across APK upgrades signed by the
same key. A signing-key change requires explicit model reprovisioning; never
work around Android signature enforcement.
