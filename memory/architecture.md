# Architecture

Cargo workspace with two members plus a TypeScript RN shell:

- **`tokito-companion-core`** (`crates/core`) — async reqwest clients, models,
  `CloudError`. Auth against `tokito-ai` is implemented. Companion sync clients
  land when `/v1/companion/*` ships in `tokito-ai` (M2), not before.
- **`tokito-companion-ffi`** (`crates/ffi`) — blocking `CompanionClient` over a
  process-wide Tokio runtime for UniFFI / RN. UDL + generated bindings = M1.
- **`apps/mobile`** — React Native + TypeScript only. Native module types live
  in `src/cloud.ts` and must match the ffi surface.

**Versions:** Rust 1.88+ (`rust-version`), edition 2021, `forbid(unsafe_code)`
in library crates. reqwest 0.12 + rustls (no OpenSSL).

**Cloud default:** `https://api.tokito.dev`, same resolution order as desktop
(`override` → `TOKITO_CLOUD_BASE_URL` → default).

**How to apply:** keep auth error enums aligned with
`tokito/src/services/cloud.rs`. Do not introduce a second HTTP stack in TS —
TS calls UniFFI, UniFFI calls core.
