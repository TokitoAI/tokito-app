# Integration facts

Tokito Companion remains untouched during runtime bootstrap. It is currently a
read/review/chat/suggestion mobile surface and does not become mobile CAD.

The standalone Android AR application is the primary consumer of a versioned
in-process API with capability discovery, lifecycle, cancellation, typed
observations, local dialogue, and registered overlay events. Desktop/cloud
transport is optional and not owned here.

**How to apply:** prove full offline use with no Tokito installation first. Open
a separate Companion issue only after the standalone application and reusable
Android artifact meet Gate 6.

The production handoff is a signed, versioned assembly context bundle. Tokito
owns PCB/BOM placement truth; DS-ViRe owns source-grounded datasheet evidence;
the physical runtime owns live registration and observations. Datasheet facts
must never be treated as placement coordinates, and visual guesses must never
silently replace authored design truth.

The FRDM-MCXN236 is the first standalone reference-board validation. Its values
must be imported into the generic bundle schema and kept out of runtime control
flow.

Reviewed printed-guide identities and electrical pin tables enter through the
generic board-reference manifest. Guide target percentages are evidence-review
coordinates only; they remain unbound until an authored-design source supplies
the board coordinate frame. Pending-license guide media stays local and is
non-redistributable even when its reviewed facts are tracked.

The digital-twin asset package is the NXP lane's handoff to the AR runtime, and
its acceptance rules are enforced on device. Component nodes must carry the
manifest's declared `componentNodePrefix` (`ref:` today), so a GLB whose nodes
are named by bare reference designator loads but selects nothing. Manifest
bounds must be strictly non-degenerate on every axis: a KiCad GLB export
contains coincident zero-thickness soldermask and silkscreen nodes sharing the
substrate footprint, and choosing one of those as the board plane produces a
package the runtime rejects. `assemblyContextRevision` must equal the value the
app derives from the active board reference, `<manifest_id>@<integrity.
manifest_digest>`; a mismatch is rejected as `CONTEXT_MISMATCH` and the 3D layer
stays invisible without an obvious error. Asset loading is bounded to 64 MiB, so
the AR export omits copper, zone and silkscreen geometry that the full-fidelity
visual master keeps.

Component identity crosses lanes as `component_id`,
`<board_id>:<board_revision>:<reference>`. The vision lane's reviewed board
reference is the naming authority for human phrases: its landmark categories,
not a preference encoded in tooling, decide which of several same-class parts
holds a role such as the board's main processor.
