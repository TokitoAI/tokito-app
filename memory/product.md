# Product framing

**Tokito Companion** is the mobile **review** app for Tokito: sign in with a
Tokito Cloud account, browse projects the desktop has shared, review BOM and
AI proposals, approve/reject decisions, and view read-only schematic artifacts
(SVG/PDF).

**Why:** Desktop owns precision CAD (egui canvas, embedded Postgres, MCP
catalog). Phones/tablets are for awareness and decisions away from the desk —
not for wire routing.

**How to apply:**

- Never add CAD edit tools, pin snapping, or a local schematic database.
- Default to the same cloud account as desktop (`api.tokito.dev` JWT).
- Prefer SVG artifacts for the viewer; PDF is secondary.
- "AI proposes, you approve" still holds — companion decisions are explicit
  approve/reject, never silent mutation of the desktop document.
