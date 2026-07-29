# AGENTS.md

**For AI coding assistants working in this repo.** Read this first, then the
`memory/` files relevant to your task. Check the org board:
<https://github.com/orgs/VtronTokito/projects/1>.

## Product

Tokito Companion is a **mobile review surface** for Tokito — account, synced
projects, BOM review, AI proposal approve/reject, read-only schematic viewer.
It is **not** a schematic editor. CAD stays on desktop (`VtronTokito/tokito`).

## How this notebook works

- **`AGENTS.md`** — instructions + memory index.
- **`memory/`** — project facts. Update in the same commit as the code that
  made a fact true. Style: `**Why:**` / `**How to apply:**` when non-obvious.

## Board

Companion work is tracked on the org project board. Prefer issues on
`VtronTokito/tokito-companion` for app work; cross-repo API work may also use
`VtronTokito/tokito-ai` / `VtronTokito/tokito` issues linked from the Companion
epic. Type labels: `epic`, `feature`, `task`, `bug`, `chore`.

Keep cards honest: In progress → In review → Done; PRs use `Closes #N`.

## Rules

- **No JavaScript app sources** under `apps/mobile/src` (TypeScript only). CI
  `forbid-js` enforces this. Expo tooling may use root configs
  (`babel.config.js`, etc.) and `.mjs` under `apps/mobile/scripts/`.
- **Android APK** is built by `.github/workflows/android-debug.yml` as
  **`assembleRelease`** (embedded JS bundle — no Metro) with debug signing for
  sideload, published as a GitHub pre-release. UI tokens/icons mirror
  `tokito_ui` (`Tokens::dark`, brand tile `#07101E`, Phosphor Regular, mint/indigo).
- **No stub clients or stub HTTP routes.** If a `/v1/companion/*` method is not
  implemented server-side yet, do not ship a fake client that returns canned
  success — wait for the `tokito-ai` PR and land client + server together.
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
