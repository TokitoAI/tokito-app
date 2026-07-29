# Product framing

**Tokito Companion** is Tokito's mobile realtime workspace: sign in once, then
create, rename, and delete projects/designs; edit BOMs; continue design chat;
review AI proposals; and edit canonical schematics while desktop is online or
temporarily disconnected.

**Why:** A project is not a one-shot phone review export. The server journal is
the shared ordering point and both devices keep durable local state, so work
continues across disconnects without repeated Share/Pull actions.

**How to apply:**

- Keep physical pick-and-place desktop-only. Canonical schematic edits,
  including pin-snapped wires and anchored-symbol movement, are in scope.
- Default to the same cloud account as desktop (`api.tokito.dev` JWT).
- SVG/PDF artifacts remain useful immutable previews; live editing uses the
  versioned `schematic` resource.
- "AI proposes, you approve" still holds: suggestions require an explicit
  approve/reject decision and are applied through desktop's typed operations.
