# Milestones

| Milestone | Scope | Status |
|---|---|---|
| **M0** | Repo bootstrap, CI, core auth client + fixtures, docs, board epic | Done |
| **M1** | RN+TS auth shell, secure token store, UniFFI bindings | Next |
| **M2** | `tokito-ai` `/v1/companion/*` + matching core client | Blocked on M1 optional; can parallel after contract freeze |
| **M3** | Desktop “Share to Companion” publish path | After M2 |
| **M4** | Project list + BOM review UI | After M2 |
| **M5** | Proposal approve/reject + desktop apply loop | After M2–M3 |
| **M6** | Read-only SVG schematic viewer | After M2 |

**How to apply:** one concern per PR; CI green before merge; no megacommits.
Land library/server before UI when adding a new route.
