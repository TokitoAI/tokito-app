# Architecture

Cargo workspace with two members plus a TypeScript RN shell:

- **`tokito-companion-core`** (`crates/core`) — async reqwest clients, models,
  `CloudError`. Auth against `tokito-ai` is implemented. Companion sync clients
  land when `/v1/companion/*` ships in `tokito-ai` (M2), not before.
- **`tokito-companion-ffi`** (`crates/ffi`) — UniFFI `CompanionClient` Object
  (`cdylib` + `staticlib` + `lib`) over a process-wide Tokio runtime. Generate
  Kotlin with `scripts/generate-bindings.sh` (output gitignored).
- **`apps/mobile`** — React Native + TypeScript: `AuthService`, secure session
  store, OTP sign-in / signed-in screens, `NativeCompanionClient` bridge to
  `TokitoCompanionFfi`.

**Versions:** Rust 1.88+ (`rust-version`), edition 2021. UniFFI **0.29.5**.
reqwest 0.12 + rustls (no OpenSSL).

**Cloud default:** `https://api.tokito.dev`, same resolution order as desktop
(`override` → `TOKITO_CLOUD_BASE_URL` → default).

**How to apply:** keep auth error enums aligned with
`tokito/src/services/cloud.rs` and `CompanionError` in ffi. Do not introduce a
second HTTP stack in TS — TS calls UniFFI, UniFFI calls core.
