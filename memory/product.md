# Product framing

**Tokito Companion** is Tokito's phone-first realtime review surface: sign in
once, browse projects/designs, inspect live schematics and BOMs, continue design
chat, and approve or reject AI proposals. It is comparable to Figma mobile, not
a second desktop CAD application.

**Why:** A project is not a one-shot phone review export. The server journal is
the shared ordering point and both devices keep durable local state, so work
continues across disconnects without repeated Share/Pull actions.

**How to apply:**

- Desktop is the only writer for project/design metadata, BOM, schematic, PCB,
  and pick-and-place resources. Companion must not expose direct CAD mutation.
- Companion may author chat messages and explicit proposal decisions. All
  other supported resources are inbound, live, and read-only.
- Default to the same cloud account as desktop (`api.tokito.dev` JWT).
- SVG/PDF artifacts remain useful immutable previews; live inspection uses the
  versioned `schematic` resource.
- Phone and tablet are both acceptance targets. Phone uses compact navigation
  and 44dp+ controls; tablet uses master/detail plus wide schematic/BOM, chat,
  and proposal layouts. Neither form factor may be treated as a stretched copy.
- "AI proposes, you approve" still holds: suggestions require an explicit
  approve/reject decision and are applied through desktop's typed operations.
