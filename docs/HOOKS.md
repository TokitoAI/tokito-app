# Hooks — how companion plugs into the org

## Auth (ready)

1. Companion `tokito-companion-core` calls the same `/v1/auth/*` routes as
   [tokito/src/services/cloud.rs](https://github.com/VtronTokito/tokito/blob/master/src/services/cloud.rs).
2. Store the JWT in the mobile secure store (M1); desktop stores it in
   `CloudSettings.cloud_token`.
3. Both clients hit `https://api.tokito.dev` unless overridden.

## Project review sync (M2–M3)

1. Spec: [API_CONTRACT.md](API_CONTRACT.md) `/v1/companion/*`.
2. Implement handlers + storage in `tokito-ai`.
3. Add core client methods in this repo in the same milestone.
4. Desktop: “Share to Companion” builds BOM + SVG from existing export /
   `project_archive` paths and `PUT`s with the cloud JWT.

## Decisions loop (M5)

1. Companion posts approve/reject on a proposal.
2. Desktop polls `GET .../proposals` (or a dedicated decisions feed) and
   applies through the existing AI apply / BOM paths — never mutate CAD from
   the phone directly.

## Non-hooks

- Do not call `tokito-mcp` from the companion for symbol search.
- Do not embed `tokito-schematic-core` for editing.
- Do not use `tokito-backend` (legacy stub); cloud is `tokito-ai`.
