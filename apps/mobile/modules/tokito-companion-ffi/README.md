# Tokito Companion FFI (Expo local module)

Production Android path: **Mozilla UniFFI Kotlin** + Expo Modules.

1. `bash scripts/build-android-ffi.sh` (needs Android NDK + `cargo-ndk`)
   - Builds `libtokito_companion_ffi.so` into `android/src/main/jniLibs/<abi>/`
   - Generates `uniffi.tokito_companion_ffi` Kotlin into `android/src/main/java/`
2. Expo autolinks this module from `apps/mobile/modules/`
3. JS uses `requireNativeModule('TokitoCompanionFfi')` — real Rust HTTP to Tokito Cloud

**Not** a stub. `HttpCloudClient` is Node/CI only.
