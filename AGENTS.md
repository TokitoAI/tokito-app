# AGENTS.md

**For AI coding assistants working in this repo.** Read this first, then the
`memory/` files relevant to your task. Check the org board:
<https://github.com/orgs/TokitoAI/projects/1>.

## Product

Tokito Companion is an **independent mobile application** in the Tokito
ecosystem. It owns its runtime, release, local storage, and Cloud client.
Tokito Desktop neither supplies nor consumes its project state. Phone and
tablet are both first-class: phone uses a compact flow; tablet uses
master/detail and wide review panes without changing product capabilities.

## How this notebook works

- **`AGENTS.md`** — instructions + memory index.
- **`memory/`** — project facts. Update in the same commit as the code that
  made a fact true. Style: `**Why:**` / `**How to apply:**` when non-obvious.

## Board

Companion work is tracked on the org project board. Prefer issues on
`TokitoAI/tokito-companion` for app work; cross-repo API work may also use
`TokitoAI/tokito-api` / `TokitoAI/tokito` issues linked from the Companion
epic. Type labels: `epic`, `feature`, `task`, `bug`, `chore`.

Keep cards honest: In progress → In review → Done; PRs use `Closes #N`.

## Rules

- **No JavaScript app sources** under `apps/mobile/src` (TypeScript only). CI
  `forbid-js` enforces this. Expo tooling may use root configs
  (`babel.config.js`, etc.) and `.mjs` under `apps/mobile/scripts/`.
- **Android release** is built by `.github/workflows/android-release.yml`:
  **`assembleRelease` + `bundleRelease`**, UniFFI
  `libtokito_companion_ffi.so`, **upload keystore** signing (GitHub secrets
  `ANDROID_UPLOAD_*` — Play-quality, not debug). Cloud stays
  `https://api.tokito.dev`. UI tokens/icons mirror `tokito_ui`.
- **Android UniFFI artifacts are an inseparable pair.** Before any local APK,
  run `scripts/build-android-ffi.ps1` on Windows or
  `scripts/build-android-ffi.sh` on Linux. Gradle verifies the generated
  manifest and refuses to package stale Kotlin bindings with a different `.so`.
- **No stub clients or stub HTTP routes.** Device builds must use UniFFI
  (`NativeCompanionClient`). `HttpCloudClient` is for Node unit tests only.
  If a `/v1/companion/*` method is not implemented server-side yet, do not
  ship a fake client — wait for the `tokito-api` PR.
- **Cloud auth is a public service contract, not Desktop coupling.** Call
  `tokito-api` `/v1/auth/*` only and maintain this client's own error mapping.
- **Desktop and Companion are fully detached.** Never add a dependency on the
  Desktop repository, its local database, workspace folders, internal event
  types, or editor mutation paths. Do not ask Desktop to publish/poll/apply
  Companion state. See `docs/PRODUCT_BOUNDARY.md`; the core regression test is
  review-blocking.
- **No large binaries in git.** Artifacts live behind the API, not in the repo.
- **Small PRs, green CI.** Milestone series M0–M6 — see `memory/milestones.md`.

## Memory index

| File | Contents |
|---|---|
| [memory/product.md](memory/product.md) | Companion product framing |
| [memory/architecture.md](memory/architecture.md) | Workspace shape, RN+UniFFI, deps |
| [memory/hooks.md](memory/hooks.md) | Cross-repo hook points |
| [memory/milestones.md](memory/milestones.md) | M0–M6 PR series |
| [memory/auth-contract.md](memory/auth-contract.md) | Auth wire contract facts |
| [memory/signing.md](memory/signing.md) | Android upload keystore / Play signing |
