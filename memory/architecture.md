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

The native session registers one stable installation id, bootstraps once, and
runs SSE receive and HTTP outbox workers concurrently with jittered reconnect.
React Native consumes typed native callbacks; it does not poll. Whole resources
are independently versioned by project/design. Project/design deletion is a
replicated tombstone. The WebView schematic surface is network-sealed by CSP
and exchanges only canonical documents with React Native.

**Android release** (`.github/workflows/android-release.yml`):

- Upload-signed APK and AAB; never use the debug keystore for release.
- Cloud base remains `https://api.tokito.dev`.
- CI fails if the JavaScript bundle, UniFFI `.so`, or release certificate is
  missing/incorrect.
- CI also requires `package.json`, Expo `app.json`, the release tag's base
  version, the monotonic Android `versionCode`, and the finished APK manifest
  to agree. UniFFI `i64` project fields are converted to JS-safe numbers in the
  Kotlin Expo module; passing Kotlin `Long` directly makes Expo reject the
  entire project-detail result after the Rust call succeeds.
- CI runs `expo install --check`, `expo-doctor`, and fails dependency audits at
  high/critical severity. GitHub Actions are pinned to immutable commits.
- Linux Rust, TypeScript CI, and Android releases use the private, hardened
  Tokito VPS runner; Windows compatibility stays on `windows-latest`. Android
  release keeps UniFFI and Gradle in separate jobs so the runner cleanup hook
  removes each repository workspace between stages. It never runs aggressive
  host disk-cleanup actions; SDK/NDK and tool caches are runner-owned and
  reusable.

**Versions:** Rust 1.88+, edition 2021, UniFFI 0.29.5, reqwest 0.12 + rustls.

**How to apply:** keep auth error enums aligned with desktop. Device builds must
not fall back to JavaScript HTTP. Keystore backups stay outside git.
