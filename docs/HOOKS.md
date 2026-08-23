# Hooks — how Companion plugs into the org

## Auth

1. Companion calls the same `/v1/auth/*` routes as desktop.
2. Mobile stores the JWT in secure storage; desktop stores it in
   `CloudSettings.cloud_token`.
3. Both clients use `https://api.tokito.dev` unless explicitly overridden.

## Realtime workspace sync

1. The contract is [API_CONTRACT.md](API_CONTRACT.md).
2. `tokito-ai` owns device registration, versioned materialized resources,
   command idempotency, the durable event journal, and resumable SSE.
3. Both clients register a stable installation id, bootstrap once, then resume
   the ordered SSE journal from their committed cursor.
4. Every permitted Companion mutation is committed to a local SQLite outbox before its authenticated
   HTTP command is attempted. A 409 is quarantined for an explicit keep-local
   or use-server decision.
5. Project/design metadata, BOM, chat, suggestions, and canonical schematics are
   independent resources. Companion writes only `chat` and `suggestions`;
   project/design, BOM, and schematic data are live read-only views. Physical
   pick-and-place is not synced.

## Suggestions

1. Companion changes the versioned `suggestions` resource.
2. Desktop receives the SSE event and applies approved typed operations on the
   egui owner thread; rejects mark the card discarded.

## Non-hooks

- Do not call `tokito-mcp` directly from Companion for symbol search.
- Do not duplicate the Rust schematic engine in the app. Mobile renders the
  canonical document as a network-sealed read-only view; desktop owns CAD edits.
- Do not use `tokito-backend` (legacy); cloud is `tokito-ai`.
