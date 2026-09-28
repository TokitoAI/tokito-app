# Architecture

Cargo workspace with two members plus a TypeScript React Native shell:

- **`tokito-companion-core`** (`crates/core`) — async reqwest clients, typed
  contracts, resumable SSE, and SQLite WAL state for cursor, materialized
  resources, offline outbox, ordered rebase, and conflict quarantine.
- **`tokito-companion-ffi`** (`crates/ffi`) — UniFFI `CompanionClient` object
  over a process-wide Tokio runtime. It exposes auth plus a callback-driven
  realtime session. Generate Kotlin through the scripts in `scripts/`.
- **`apps/mobile`** — Expo SDK 57, React Native 0.86, and TypeScript. Tokens mirror
  `tokito_ui::Tokens::dark`; tablet layouts use master/detail.
  - **`modules/tokito-companion-ffi`** — Expo local module containing generated
    UniFFI Kotlin and the packaged native `.so`.
  - **`NativeCompanionClient`** — the only device cloud path.
  - **`HttpCloudClient`** — Node/CI unit tests only.

The native session registers one stable installation id with Cloud, bootstraps
its own Cloud-visible workspace, and runs SSE receive and HTTP outbox workers
concurrently with jittered reconnect.
React Native consumes typed native callbacks; it does not poll. Whole resources
are independently versioned by project/design. Project/design deletion is a
replicated tombstone. The WebView schematic surface is network-sealed by CSP
and exchanges only canonical documents with React Native.

**Android release** (`.github/workflows/android-release.yml`):

- Upload-signed APK and AAB; never use the debug keystore for release.
- Cloud base remains `https://api.tokito.dev`.
- CI fails if the JavaScript bundle, UniFFI `.so`, or release certificate is
  missing/incorrect.
- Generated UniFFI Kotlin and `libtokito_companion_ffi.so` are one atomic
  artifact pair. Both platform build scripts emit a SHA-256 manifest; the
  Android module's `preBuild` verifies it and refuses to package absent or
  mixed-revision artifacts. This is required because UniFFI otherwise discovers
  the API checksum mismatch only when the first native call runs on-device.
- CI also requires `package.json`, Expo `app.json`, the release tag's base
  version, the monotonic Android `versionCode`, and the finished APK manifest
  to agree. The legacy project-detail bridge serializes its nested result to
  one JSON string before crossing Expo; recursive Kotlin maps containing
  UniFFI `Long` values are rejected by Expo even after the Rust call succeeds.
  The realtime resource journal is authoritative for Companion's Cloud
  resources, while
  legacy detail and proposal endpoints are optional enrichment and must never
  block a journal-backed workspace from opening.
- CI runs `expo install --check`, `expo-doctor`, and fails dependency audits at
  high/critical severity. GitHub Actions are pinned to immutable commits.
- Linux Rust, TypeScript CI, and Android releases use the private, hardened
  Tokito VPS runner. While hosted Actions billing is unavailable, Windows
  compatibility uses the selected-private-repository organization runner
  labelled `tokito-windows`; public repositories cannot target it. Android
  release keeps UniFFI and Gradle in separate jobs so the runner cleanup hook
  removes each repository workspace between stages. It never runs aggressive
  host disk-cleanup actions; SDK/NDK and tool caches are runner-owned and
  reusable.

**Versions:** Rust 1.88+, edition 2021, UniFFI 0.29.5, reqwest 0.12 + rustls.

**How to apply:** treat `tokito-api` as the public contract; never import or
mirror Desktop internals. Device builds must not fall back to JavaScript HTTP.
Keystore backups stay outside git. `docs/PRODUCT_BOUNDARY.md` is mandatory.
