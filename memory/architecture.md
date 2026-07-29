# Architecture

Cargo workspace with two members plus a TypeScript RN shell:

- **`tokito-companion-core`** (`crates/core`) — async reqwest clients, models,
  `CloudError`. Auth + `/v1/companion/*` sync (projects, artifacts, proposals).
- **`tokito-companion-ffi`** (`crates/ffi`) — UniFFI `CompanionClient` Object
  (`cdylib` + `staticlib` + `lib`) over a process-wide Tokio runtime. Auth **and**
  companion sync methods. Generate Kotlin with `scripts/generate-bindings.sh` /
  `scripts/build-android-ffi.sh` (output gitignored).
- **`apps/mobile`** — Expo SDK 52 + React Native + TypeScript. Tokens mirror
  `tokito_ui::Tokens::dark`. Phosphor + Inter. Tablet ≥768dp master/detail.
  - **`modules/tokito-companion-ffi`** — Expo local module: JNA + UniFFI Kotlin +
    `libtokito_companion_ffi.so` in jniLibs. Autolinked into the APK.
  - **`NativeCompanionClient`** — **only** cloud path on Android/iOS.
  - **`HttpCloudClient`** — Node/CI unit tests only.

**Android release** (`.github/workflows/android-release.yml`):

- Upload-signed APK + AAB (Play-quality keystore in GitHub Actions secrets —
  `ANDROID_UPLOAD_*`). Never debug-keystore for release.
- Cloud base remains `https://api.tokito.dev` (tokito-ai).
- CI fails if JS bundle or UniFFI `.so` missing, or if APK cert is Android Debug.

**Versions:** Rust 1.88+ (`rust-version`), edition 2021. UniFFI **0.29.5**.
reqwest 0.12 + rustls (no OpenSSL).

**How to apply:** keep auth error enums aligned with desktop. Device builds must
not fall back to JS HTTP. Keystore backup lives on the operator machine under
`~/.tokito-secrets/` (not in git).
