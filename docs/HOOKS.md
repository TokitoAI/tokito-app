# Hooks — how Companion plugs into the org

## Auth

1. Companion calls the public `/v1/auth/*` routes directly.
2. Mobile stores its JWT in secure storage.
3. The client uses `https://api.tokito.dev` unless explicitly overridden.

## Realtime workspace sync

1. The contract is [API_CONTRACT.md](API_CONTRACT.md).
2. `tokito-api` owns device registration, versioned materialized resources,
   command idempotency, the durable event journal, and resumable SSE.
3. Companion registers its own stable installation id, bootstraps once, then
   resumes its ordered SSE journal from its committed cursor.
4. Every permitted Companion mutation is committed to a local SQLite outbox before its authenticated
   HTTP command is attempted. A 409 is quarantined for an explicit keep-local
   or use-server decision.
5. Project/design metadata, BOM, chat, suggestions, and canonical schematics are
   independent resources. Companion writes only `chat` and `suggestions`;
   project/design, BOM, and schematic data are live read-only views. Physical
   pick-and-place is not synced.

## Product boundary

- Desktop never receives Companion SSE events or applies Companion decisions.
- Companion never reads Desktop local storage or internal events.
- Future interoperability requires a new, versioned public Cloud contract and
  explicit security, migration, and end-to-end validation.

## Non-hooks

- Do not call `tokito-mcp` directly from Companion for symbol search.
- Do not duplicate the Rust schematic engine in the app. Mobile renders a
  supported Cloud document as a network-sealed read-only view.
- Do not use `tokito-backend` (legacy); cloud is `tokito-api`.
