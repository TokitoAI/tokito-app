# Assembly context bundle boundary

The assembly context is an optional, immutable local import. It carries
authored board-placement truth and separately sourced part evidence into the
physical runtime. It must not be required for generic board discovery or the
standalone baseline product.

`native/runtime-core` provides the language-neutral validation boundary that
the Android importer must use. The production importer is responsible for
parsing a declared source format into this canonical form; it must not branch
on a board name or assume a particular CAD or datasheet provider.

## Canonical content

Each bundle supplies a versioned schema, board identity and revision, outline,
coordinate-frame identifier, units, handedness, component placements,
landmarks, source evidence, licensing, and integrity metadata. A component
placement includes its reference designator, footprint, centroid, rotation,
side, optional polarity or pin-one rule, alternates, and the source identifier
for its placement truth.

Source evidence has an explicit role. Only `AuthoredPlacement` evidence can be
named as a component's placement source. `Datasheet` and supporting evidence
may provide package, polarity, pin, or licensing facts, but cannot supply or
overwrite physical coordinates. Camera observations are separate runtime
observations and cannot mutate imported authored truth.

## Board reference manifests

Reviewed guides and datasheets can describe a board before an authored
assembly-context bundle is available. That identity and electrical evidence is
stored in `reference/boards/*.board-reference.json` under
`schemas/board.reference-manifest.schema.json`. It records named landmarks,
connector pin tables, signal aliases, functions, and typed electrical roles
such as ground, power rail, power input, reset, analog, bus, GPIO, and not
connected.

A `guide_target` is only a percentage point in a cited guide image. It answers
"which depicted part does this callout name?" It is not a millimetre position,
camera pose, component centroid, or AR anchor. The manifest therefore remains
`placement_binding.status: unbound` until an authored-design source supplies a
coordinate frame. Binding a printed guide or datasheet as authored placement
is rejected by `tools/board_reference.py`.

Every source carries its media digest, locator, review state, license state,
and redistribution decision. Pending source media may support locally reviewed
facts, but it is not redistributed by this repository. Validate the tracked
FRDM reference with:

```bash
python tools/board_reference.py validate \
  --manifest reference/boards/frdm-mcxn236.board-reference.json
```

## Import checks

Before activation, the runtime validates:

- the supported schema major version and all required identifiers;
- finite geometry, a non-degenerate declared outline, and declared coordinate
  conventions;
- duplicate reference designators, landmarks, and source identities;
- component placement provenance and source licensing;
- a canonical SHA-256 payload digest plus a verifier supplied by the platform
  integration; and
- the configured local storage quota.

Activation is atomic: a candidate is fully validated and integrity-checked
before it replaces the active context. A failed import preserves the previous
active context. The platform integration owns the concrete signature scheme and
trusted key policy; the native contract accepts it only through a verifier.

## Compatibility

Schema-major differences are rejected. A minor revision is represented in the
canonical type so an Android serializer can preserve compatible optional data
under the contract's forward-compatibility policy. Unknown or malformed source
formats must fail before they reach the canonical importer.
