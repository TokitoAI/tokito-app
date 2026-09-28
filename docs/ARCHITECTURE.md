# Architecture

```
RN TypeScript UI ──UniFFI──▶ tokito-companion-ffi ──▶ tokito-companion-core
                                                         │
                                                         ▼
                                              https://api.tokito.dev (tokito-api)
```

Companion registers its own installation with Cloud, bootstraps its own
Cloud-visible resources, and consumes ordered updates through the public API.
It keeps a local SQLite cache, committed cursor, and offline outbox.

The server journal is authoritative for resources exposed to Companion.
Companion never opens Desktop's embedded Postgres/workspaces, imports Desktop
source, or calls Desktop mutation paths. Desktop does not publish, poll, or
apply Companion state. See [PRODUCT_BOUNDARY.md](PRODUCT_BOUNDARY.md).

See [../memory/architecture.md](../memory/architecture.md) for crate details.
