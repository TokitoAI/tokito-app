# Architecture

```
RN TypeScript UI ──UniFFI──▶ tokito-companion-ffi ──▶ tokito-companion-core
                                                         │
                                                         ▼
                                              https://api.tokito.dev (tokito-ai)
```

Desktop (`tokito-native`) and Companion register independent installations,
bootstrap a materialized workspace once, and then exchange ordered resource
updates through the same durable SSE journal and idempotent command endpoint.
Each client keeps a local SQLite cache, committed cursor, and offline outbox.

The server journal is the ordering authority; neither client polls. Companion
never opens desktop's embedded Postgres or calls `mcp.tokito.dev` directly.
Pick-and-place remains desktop-only, while project/design metadata, BOM, chat,
suggestions, and canonical schematics sync independently in both directions.

See [../memory/architecture.md](../memory/architecture.md) for crate details.
