# Tokito Companion

Mobile companion for the [Tokito](https://github.com/VtronTokito/tokito) desktop
schematic studio — **review**, not CAD edit.

- Sign in with the same Tokito Cloud account (JWT via `tokito-ai`)
- Browse synced projects, BOM, and AI proposals
- Approve / reject decisions that sync back to desktop
- Read-only schematic viewer (SVG/PDF artifacts)

**Stack:** React Native + TypeScript shell, Rust core (`tokito-companion-core`)
exposed through UniFFI (`tokito-companion-ffi`). No JavaScript app sources.

## Workspace

```
crates/core/     # typed Tokito Cloud clients (auth live; companion sync in M2+)
crates/ffi/      # UniFFI CompanionClient (cdylib/staticlib) for RN
apps/mobile/     # React Native + TypeScript auth shell
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

## Android APK (sideload)

**Current install:** the latest **`v*-pre*`** pre-release on
[Releases](https://github.com/VtronTokito/tokito-companion/releases)
(asset `TokitoCompanion-<tag>.apk`).

CI workflow [android-debug](.github/workflows/android-debug.yml):

- Builds **`assembleRelease`** with embedded `index.android.bundle` (no Metro).
- Links **UniFFI** `libtokito_companion_ffi.so` (real Rust cloud client).
- Signs with the debug keystore for sideload (not Play Store).
- Publish by tagging: `git tag v0.1.2-pre.N && git push origin v0.1.2-pre.N`

Do **not** install older than `v0.1.2-pre.1` (missing UniFFI and/or JS bundle).

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
