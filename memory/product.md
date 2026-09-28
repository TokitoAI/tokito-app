# Product framing

**Tokito Companion** is an independent, phone-first client in the Tokito
ecosystem. It owns its runtime, release, local storage, and Cloud integration.
It is not a second desktop CAD application and is not a replication or remote
control layer for Tokito Desktop.

**Why:** Earlier architecture coupled the products through Desktop publication,
mobile decisions, and a shared journal. That boundary was removed by
TokitoAI/tokito#793. Independent products can evolve and fail independently.

**How to apply:**

- Never import Desktop source, read its embedded database/workspaces, or rely on
  its internal resource and event shapes.
- Never introduce a Desktop publish/poll/apply path. Any future cross-product
  feature requires a tracked, versioned public Cloud contract and explicit
  migration, security, and end-to-end evidence first.
- Authenticate directly with Cloud (`api.tokito.dev` JWT); shared identity does
  not imply shared project state.
- SVG/PDF artifacts remain useful immutable previews; live inspection uses the
  versioned `schematic` resource.
- Phone and tablet are both acceptance targets. Phone uses compact navigation
  and 44dp+ controls; tablet uses master/detail plus wide schematic/BOM, chat,
  and proposal layouts. Neither form factor may be treated as a stretched copy.
- Mobile actions must complete within Companion/Cloud ownership; they are not
  applied through Desktop's internal typed operations.
