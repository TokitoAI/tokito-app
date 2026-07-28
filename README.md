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
crates/ffi/      # blocking facade for UniFFI / RN
apps/mobile/     # React Native + TypeScript (shell grows in M1+)
docs/            # architecture, API contract, hooks
memory/          # agent notebook
```

## Development

```bash
# Rust (required)
cargo fmt --all -- --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace --locked

# TypeScript shell
cd apps/mobile && npm ci && npm run forbid-js && npm run typecheck && npm run lint && npm test
```

Default cloud base: `https://api.tokito.dev` (override with `TOKITO_CLOUD_BASE_URL`
or per-call base URL).

## Docs

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- [docs/API_CONTRACT.md](docs/API_CONTRACT.md)
- [docs/HOOKS.md](docs/HOOKS.md)
- [AGENTS.md](AGENTS.md) — for AI coding assistants

## License

MIT — see [LICENSE](LICENSE).
