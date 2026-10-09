# Missing symbols during board inspection

This optional feature generates private schematic symbol drafts from reviewed
datasheet evidence. It is not a 3D component-model generator or a CAD editor.

1. An operator audits `TokitoAI/tokito-app-dataset`, reviews exact physical board
   and revision correspondence, maps each major component to an exact part and
   package, records current catalog resolution, and installs permitted,
   hash-bound datasheets and complete pin coverage in a tenant-scoped DS-ViRe
   binding. Unknown labels stay unknown; MCXN947 sources cannot silently stand
   in for MCXN236. Bindings are limited to eight components per context.
2. In **Local models → Missing board symbols**, save the operator's HTTPS origin
   and tenant API key. Credentials live in SecureStore and can be removed.
   Saving settings does not claim that the service is healthy or authorized.
3. Load the corresponding reviewed placement-bound reference through the
   existing operator context workflow. Start the bench session and register
   the correct board. The **Generate missing symbols** action is unavailable
   without configuration or reviewed context, and disabled before registration.
4. Confirm the physical model/revision and authorize sending the context to
   DS-ViRe. Camera frames and audio stay local. The phone sends no PDFs, crop
   geometry, inferred part IDs, provider keys or catalog writes.
5. Review returned pin names/types and citation counts. Full JSON/SVG drafts
   are saved in app-private `symbol-drafts/` using the context hash. Existing
   catalog entries, insufficient evidence and unavailable service are explicit.

Cancel, registration loss or leaving the camera cancels transport. Context
mismatches reject results. Drafts never replace accepted assembly evidence or
publish a catalog revision. Native `.tokito_sym` compilation and publication
remain separate validated product paths.

The server endpoint is tenant-authenticated
`POST /v1/platform/board-symbols?context_revision=...`. Its source-bound binding
format and operator commands are documented in DS-ViRe's `docs/BOARD_SYMBOLS.md`.
Both repositories require the feature branch changes together; the existing
deployed service does not acquire this new route merely by updating the phone.

The current dataset has partial component seeds and unresolved exact specimen
mapping. This change creates no production review approval or real-board
accuracy claim. Before release, run the unified Buildkite Rust/UniFFI, mobile,
Physical contracts, Physical Android and APK gates plus exact-device consent,
offline, cancellation, landscape and accessibility checks. Host library tests
alone do not prove phone behavior.
