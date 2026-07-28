# Architecture

```
RN TypeScript UI ──UniFFI──▶ tokito-companion-ffi ──▶ tokito-companion-core
                                                         │
                                                         ▼
                                              https://api.tokito.dev (tokito-ai)
```

Desktop (`tokito-native`) shares the same JWT and, from M3, publishes review
bundles to `/v1/companion/*`. Companion never opens embedded Postgres or talks
to `mcp.tokito.dev` for CAD.

See [../memory/architecture.md](../memory/architecture.md) for crate details.
