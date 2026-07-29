# API contract — Tokito Companion ↔ tokito-ai

Auth and billing routes already exist; companion reuses them. Companion sync
routes are **specified here** and must be implemented in `tokito-ai` before
any companion client methods for them merge (no stubs).

## Existing (implemented in tokito-ai)

| Method | Path | Notes |
|---|---|---|
| POST | `/v1/auth/otp/request` | Email OTP |
| POST | `/v1/auth/otp/verify` | → `{token,email,plan}` |
| POST | `/v1/auth/oauth/exchange` | Pairing code → session |
| GET | `/v1/auth/me` | Bearer JWT |
| GET | `/v1/billing/status` | Plan + subscription |

Default base: `https://api.tokito.dev`.

## Companion sync (**implemented** in `tokito-ai` + this client)

All routes require `Authorization: Bearer <jwt>` unless noted.

### `PUT /v1/companion/projects/{project_id}`

Publish or replace a project review snapshot from desktop.

Request body (JSON):

```json
{
  "name": "Arduino Shield v2",
  "slug": "arduino-shield-v2",
  "updated_at": "2026-07-28T12:00:00Z",
  "designs": [
    {
      "id": "uuid",
      "name": "Main",
      "sheet_count": 1,
      "bom_lines": [
        {"ref": "U1", "value": "ATmega328P", "footprint": "DIP-28", "qty": 1}
      ]
    }
  ],
  "artifact_manifest": [
    {"name": "main.svg", "content_type": "image/svg+xml", "sha256": hex, "bytes": 12345}
  ]
}
```

`project_id` is the desktop project UUID (stable across republish).

### `PUT /v1/companion/projects/{project_id}/artifacts/{name}`

Raw body; `Content-Type` as appropriate. Server verifies size limits and
optional `X-Content-SHA256` against the manifest. Max size:
**16 MiB** per artifact (`tokito-ai` `MAX_ARTIFACT_BYTES`).

### `GET /v1/companion/projects`

List projects for the caller. Response: `{"projects":[{id,name,slug,updated_at},…]}`.

### `GET /v1/companion/projects/{project_id}`

Full snapshot including BOM lines and artifact manifest.

### `GET /v1/companion/projects/{project_id}/artifacts/{name}`

Artifact bytes.

### `POST /v1/companion/projects/{project_id}/proposals`

Desktop (or future cloud agent) posts a pending proposal card:

```json
{
  "id": "uuid",
  "kind": "schematic_edits" | "bom_edits",
  "title": "Add decoupling caps",
  "summary": "…",
  "created_at": "…",
  "payload": {}
}
```

### `GET /v1/companion/projects/{project_id}/proposals?status=pending`

### `POST /v1/companion/projects/{project_id}/proposals/{proposal_id}/decision`

```json
{"decision":"approve"|"reject","note":"optional"}
```

Desktop polls decisions and applies locally.

Canonical server docs: `tokito-ai/docs/API.md`. Keep both documents aligned.
