# Architecture

Cargo workspace with two members plus a TypeScript RN shell:

- **`tokito-companion-core`** (`crates/core`) — async reqwest clients, models,
  `CloudError`. Auth + `/v1/companion/*` sync (projects, artifacts, proposals).
- **`tokito-companion-ffi`** (`crates/ffi`) — UniFFI `CompanionClient` Object
  (`cdylib` + `staticlib` + `lib`) over a process-wide Tokio runtime. Generate
  Kotlin with `scripts/generate-bindings.sh` (output gitignored).
- **`apps/mobile`** — React Native + TypeScript: `AuthService`, secure session
  store, OTP sign-in, project list / BOM / proposals / SVG viewer.
  - **`HttpCloudClient`** (`src/api/cloudHttp.ts`) — real `fetch` client with the
    same wire contracts as core (used by the shell + Node CI without native
    UniFFI linked).
  - **`NativeCompanionClient`** — UniFFI bridge when the native module is present.

**Versions:** Rust 1.88+ (`rust-version`), edition 2021. UniFFI **0.29.5**.
reqwest 0.12 + rustls (no OpenSSL).

**Cloud default:** `https://api.tokito.dev`, same resolution order as desktop
(`override` → `TOKITO_CLOUD_BASE_URL` → default).

**How to apply:** keep auth error enums aligned with
`tokito/src/services/cloud.rs` and `CompanionError` in ffi. Prefer one wire
contract: TS `HttpCloudClient` and Rust core must stay in sync with
`tokito-ai` `/v1/companion/*`.
