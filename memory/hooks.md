# Cross-repo hooks

| Peer | Role |
|---|---|
| `TokitoAI/tokito-api` | Public Cloud contracts used directly by this client |
| `TokitoAI/tokito` | Separate product; no source, runtime, storage, or project-state hook |
| `TokitoAI/tokito-mcp` | Not called from Companion |

**Auth (live today):** Companion independently uses `/v1/auth/otp/*`,
`/v1/auth/oauth/exchange`, `/v1/auth/me`. See
[auth-contract.md](auth-contract.md).

**Sync (M2+):** `/v1/companion/projects`, artifacts, proposals, decisions, and
the durable resource journal use canonical shapes in `docs/API_CONTRACT.md`.
Realtime transport is between Companion and Cloud. Authorization is defined by
the public server contract, never by Desktop internals.

**Forbidden Desktop hook:** do not publish Desktop archives/SVG/BOM, poll mobile
decisions, or apply mobile state in the editor. Shared public Cloud APIs do not
authorize direct product coupling.

**How to apply:** when changing a wire shape, update `tokito-api/docs/API.md`
and this repo's `docs/API_CONTRACT.md` in the same change set (cross-repo PR
pair), then bump clients.
