# AGENTS.md

**For AI coding assistants working in this repo.** Read this first, then the
`memory/` files relevant to your task. Check the org board:
<https://github.com/orgs/TokitoAI/projects/1>.

## Product

Tokito Companion is Tokito's **mobile-first realtime review surface** — account,
synced projects/designs, live read-only schematic and BOM inspection, design
chat, and AI proposal review. It follows the Figma-mobile boundary: desktop is
the sole author of project structure, BOM, and CAD resources; Companion authors
only chat messages and explicit approve/reject decisions. Phone and tablet are
both first-class: phone uses a compact flow; tablet uses master/detail and wide
review panes without changing product capabilities.

## How this notebook works

- **`AGENTS.md`** — instructions + memory index.
- **`memory/`** — project facts. Update in the same commit as the code that
  made a fact true. Style: `**Why:**` / `**How to apply:**` when non-obvious.

## Board

Companion work is tracked on the org project board. Prefer issues on
`TokitoAI/tokito-companion` for app work; cross-repo API work may also use
`TokitoAI/tokito-ai` / `TokitoAI/tokito` issues linked from the Companion
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
- **No stub clients or stub HTTP routes.** Device builds must use UniFFI
  (`NativeCompanionClient`). `HttpCloudClient` is for Node unit tests only.
  If a `/v1/companion/*` method is not implemented server-side yet, do not
  ship a fake client — wait for the `tokito-ai` PR.
- **Auth is shared with desktop.** Call `tokito-ai` `/v1/auth/*` only; mirror
  error mapping from `tokito::services::cloud` / this crate's `CloudError`.
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
