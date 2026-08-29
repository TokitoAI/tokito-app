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
coordinate-frame identifier, millimetre units, declared left- or right-handed
coordinates, component placements,
landmarks, source evidence, licensing, and integrity metadata. A component
placement includes its reference designator, footprint, centroid, rotation,
side, optional polarity or pin-one rule, alternates, and the source identifier
for its placement truth.

Source evidence has an explicit role. Only `AuthoredPlacement` evidence can be
named as a component's placement source. `Datasheet` and supporting evidence
may provide package, polarity, pin, or licensing facts, but cannot supply or
overwrite physical coordinates. Camera observations are separate runtime
observations and cannot mutate imported authored truth.

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

The JSON format uses normalized rotations in the half-open range `[0, 360)`
and the canonical `top` / `bottom` side values. Every top-level collection is
required even when empty. Unknown fields are accepted for compatible minor
schema revisions, but remain covered by integrity verification.

`integrity.payload_sha256` is the digest of the deterministic compact JSON
encoding of all top-level fields except `integrity`, with object keys sorted
lexicographically. The platform verifier checks that same byte sequence using
`integrity.signature_id`. This excludes the self-referential integrity object
without leaving compatible unknown fields unsigned.

Activation is atomic: a candidate is fully validated and integrity-checked
before it replaces the active context. A failed import preserves the previous
active context. The platform integration owns the concrete signature scheme and
trusted key policy; the native contract accepts it only through a verifier.

## Compatibility

Schema-major differences are rejected. A minor revision is represented in the
canonical type so an Android serializer can preserve compatible optional data
under the contract's forward-compatibility policy. Unknown or malformed source
formats must fail before they reach the canonical importer.
