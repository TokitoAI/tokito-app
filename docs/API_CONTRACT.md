# API contract — Tokito Companion ↔ tokito-api

Auth and billing routes already exist; companion reuses them. Companion sync
routes are implemented by `tokito-api`; the durable realtime protocol below is
the primary workspace path. Snapshot routes remain a migration/bootstrap
compatibility surface and are not used for ongoing synchronization.

## Existing (implemented in tokito-api)

| Method | Path | Notes |
|---|---|---|
| POST | `/v1/auth/otp/request` | Email OTP |
| POST | `/v1/auth/otp/verify` | → `{token,email,plan}` |
| POST | `/v1/auth/oauth/exchange` | Pairing code → session |
| GET | `/v1/auth/me` | Bearer JWT |
| GET | `/v1/billing/status` | Plan + subscription |

Default base: `https://api.tokito.dev`.

## Durable realtime workspace

One signed-in device registration replaces repeated "Share to Companion".
The client performs a consistent bootstrap once, persists the returned cursor,
and then consumes ordered SSE events. Permitted local actions enter a SQLite
outbox before network submission and are idempotent by `command_id`. Companion
commands are restricted to `chat` and `suggestions`; desktop remains the writer
for project/design metadata, BOM, and schematic resources.

| Method | Path | Purpose |
|---|---|---|
| POST | `/v1/companion/devices` | Register/heartbeat an installation ID |
| GET | `/v1/companion/bootstrap?project_id=` | Consistent resources plus journal cursor |
| POST | `/v1/companion/commands` | Optimistic, idempotent resource mutation |
| GET | `/v1/companion/events?cursor=&project_id=` | Durable SSE replay and live tail |

SSE clients send both `cursor` and `Last-Event-ID`. Each `event: sync` frame
contains a globally ordered `seq`. The client commits the materialized resource
and cursor atomically before delivering the event to React Native.

Commands contain `device_id`, `command_id`, `project_id`, optional `design_id`,
`resource`, `base_version`, and `payload`. A stale base returns HTTP 409 with
the current version and payload; the client quarantines that outbox row until
the user explicitly chooses the server copy or rebases their local edit.

Resource scopes and payload contracts:

| Resource | Scope | Companion access | Payload |
|---|---|---|---|
| `project` | project, no design | read-only | `{id,name,slug,updated_at,design_ids,deleted?}` |
| `design` | project + design | read-only | `{id,name,sheet_count,updated_at,deleted?}` |
| `schematic` | project + design | read-only | Canonical serialized Tokito schematic document |
| `bom` | project + design | read-only | `{lines:[{ref,value,footprint,qty,...}]}` |
| `chat` | project + design | read/write | `{messages:[{id,role,content,created_at,...}]}` |
| `suggestions` | project + design | decision write | `{items:[{id,kind,title,summary,status,...}]}` |

Payloads are whole versioned resources, capped at 2 MiB. Transporting a resource
does not grant every client write authority. Pick-and-place is not synchronized.

Creation uses a stable UUID assigned by the originating client. Deletion is a
versioned `deleted: true` tombstone rather than an absent resource, ensuring an
offline device learns the deletion when it resumes its cursor.

## Companion sync (**implemented** in `tokito-api` + this client)

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
**16 MiB** per artifact (`tokito-api` `MAX_ARTIFACT_BYTES`).

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

Legacy desktop clients poll decisions and apply locally. Realtime clients use
the versioned `suggestions` resource instead.

Canonical server docs: `tokito-api/docs/API.md`. Keep both documents aligned.
