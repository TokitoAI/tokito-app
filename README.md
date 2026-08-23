# Tokito Companion

Realtime mobile workspace for the
[Tokito](https://github.com/TokitoAI/tokito) schematic studio.

- Sign in with the same Tokito Cloud account (JWT via `tokito-ai`)
- Create and manage projects and designs independently
- Edit BOM and chat resources; approve or reject AI proposals
- Edit canonical schematics with durable two-way sync and offline outbox
- Resume ordered SSE after disconnect without repeating “Share to Companion”

Physical pick-and-place and desktop-only CAD workflows remain on desktop.

**Stack:** React Native + TypeScript shell, Rust core (`tokito-companion-core`)
exposed through UniFFI (`tokito-companion-ffi`). No JavaScript app sources.

## Workspace

```
crates/core/     # typed cloud clients, SSE resume, SQLite cache/outbox
crates/ffi/      # UniFFI CompanionClient (cdylib/staticlib) for RN
apps/mobile/     # React Native + TypeScript realtime workspace
docs/            # architecture, API contract, hooks
memory/          # agent notebook
scripts/generate-bindings.sh   # Kotlin UniFFI smoke (gitignored output)
scripts/build-android-ffi.sh   # NDK .so + Kotlin into Expo module (APK CI)
```

## Development

```bash
# Rust (required)
cargo fmt --all -- --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace --locked
bash scripts/generate-bindings.sh   # Linux: proves UniFFI surface

# TypeScript / Expo shell
cd apps/mobile && npm ci && npm run forbid-js && npm run typecheck && npm run lint && npm test
cd apps/mobile && npx expo start
```

Default cloud base: `https://api.tokito.dev` (override with `TOKITO_CLOUD_BASE_URL`
or `app.json` → `expo.extra.cloudBaseUrl`).

## Android release (Play-quality signing, live API)

**Current install / Play upload:** latest GitHub release asset
`TokitoCompanion-<tag>.apk` / `.aab` on
[Releases](https://github.com/TokitoAI/tokito-companion/releases).

CI [android-release](.github/workflows/android-release.yml):

- `assembleRelease` + `bundleRelease` with embedded JS + UniFFI `.so`
- **Upload keystore** signing (GitHub secrets `ANDROID_UPLOAD_*`) — not Android Debug
- Cloud: `https://api.tokito.dev` (tokito-ai)
- Tag: `git tag v0.2.2-pre.N && git push origin v0.2.2-pre.N`

Uninstall any older **debug-signed** Companion before installing upload-signed
builds (signature mismatch).

Package id: `dev.tokito.companion`. UI tokens match desktop `tokito_ui` dark
palette (mint accent, brand tile, Phosphor icons). Tablets ≥768dp get a
master/detail split.

## Docs

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- [docs/API_CONTRACT.md](docs/API_CONTRACT.md)
- [docs/HOOKS.md](docs/HOOKS.md)
- [AGENTS.md](AGENTS.md) — for AI coding assistants

## License

MIT — see [LICENSE](LICENSE).
