# Tokito Companion FFI (Expo local module)

Production Android path: **Mozilla UniFFI Kotlin** + Expo Modules + upload-signed APK/AAB.

1. `bash scripts/build-android-ffi.sh` (NDK + `cargo-ndk`)
2. `node scripts/configure-android-release.mjs` (upload keystore from `ANDROID_UPLOAD_*`, with a private `TOKITO_RELEASE_SIGNING_DIR` cleaned up by the caller; the Buildkite release script owns this lifecycle)
3. JS: `requireNativeModule('TokitoCompanionFfi')` → Rust → `https://api.tokito.dev`

Device builds never use `HttpCloudClient`.
