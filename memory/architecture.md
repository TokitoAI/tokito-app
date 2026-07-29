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
  - **`NativeCompanionClient`** — **production device path** (`createCloudClient`).
  - **`HttpCloudClient`** — Node/CI tests only (same wire contract; not shipped
    as the device cloud path).

**Versions:** Rust 1.88+ (`rust-version`), edition 2021. UniFFI **0.29.5**.
reqwest 0.12 + rustls (no OpenSSL).

**Cloud default:** `https://api.tokito.dev`, same resolution order as desktop
(`override` → `TOKITO_CLOUD_BASE_URL` → default). UI displays the resolved host.

**How to apply:** keep auth error enums aligned with
`tokito/src/services/cloud.rs` and `CompanionError` in ffi. APK CI must fail if
`libtokito_companion_ffi.so` is missing from the package.
