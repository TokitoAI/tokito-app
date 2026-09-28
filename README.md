# Tokito Companion

Independent mobile client in the Tokito ecosystem. It has its own release,
runtime, storage, and Cloud client; it is not a remote control or replication
layer for [Tokito Desktop](https://github.com/TokitoAI/tokito).

- Sign in through Tokito Cloud (JWT via `tokito-api`)
- Browse and review Cloud-owned resources exposed to this client
- Keep mobile state in Companion's own cache and outbox
- Pan, zoom, and inspect supported immutable documents

Desktop does not publish to, poll, import, or apply state from this application.
Phone and tablet are first-class responsive layouts: compact tabs on phone;
master/detail and wide review panes on tablet.

**Stack:** React Native + TypeScript shell, Rust core (`tokito-companion-core`)
exposed through UniFFI (`tokito-companion-ffi`). No JavaScript app sources.

## Workspace

```
crates/core/     # typed cloud clients, SSE resume, SQLite cache/outbox
crates/ffi/      # UniFFI CompanionClient (cdylib/staticlib) for RN
apps/mobile/     # React Native + TypeScript mobile review surface
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
- Cloud: `https://api.tokito.dev` (tokito-api)
- Tag: `git tag v0.2.2-pre.N && git push origin v0.2.2-pre.N`

Uninstall any older **debug-signed** Companion before installing upload-signed
builds (signature mismatch).

Package id: `dev.tokito.companion`. Companion owns its mobile dark palette
(mint accent, brand tile, Phosphor icons). Phone controls use 44dp+
targets; tablets ≥768dp use a dedicated master/detail composition.

## Docs

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- [docs/API_CONTRACT.md](docs/API_CONTRACT.md)
- [docs/HOOKS.md](docs/HOOKS.md)
- [docs/PRODUCT_BOUNDARY.md](docs/PRODUCT_BOUNDARY.md)
- [AGENTS.md](AGENTS.md) — for AI coding assistants

## License

MIT — see [LICENSE](LICENSE).
