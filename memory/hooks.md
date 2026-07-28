# Cross-repo hooks

| Peer | Role |
|---|---|
| `VtronTokito/tokito-ai` | Auth (live), billing (live), companion sync API (M2) |
| `VtronTokito/tokito` | Desktop cloud session + publish/poll companion decisions (M3+) |
| `VtronTokito/tokito-mcp` | Not called from companion — catalog stays desktop/server |

**Auth (live today):** Companion and desktop both use `/v1/auth/otp/*`,
`/v1/auth/oauth/exchange`, `/v1/auth/me`. Same JWT. See
[auth-contract.md](auth-contract.md).

**Sync (M2+):** `/v1/companion/projects`, artifacts, proposals, decisions —
canonical shapes in `docs/API_CONTRACT.md`. Implement server + client in
coordinated PRs; never stub.

**Desktop publish (M3):** Reuse `project_archive` / export SVG+BOM; push with
`CloudSettings.cloud_token` to companion routes.

**How to apply:** when changing a wire shape, update `tokito-ai/docs/API.md`
and this repo's `docs/API_CONTRACT.md` in the same change set (cross-repo PR
pair), then bump clients.
