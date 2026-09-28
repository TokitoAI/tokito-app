# Consolidation facts

Companion Git history is the base of `tokito-app`. Physical Runtime `main`
history is preserved through a non-squashed subtree merge under
`physical-runtime/`.

The subtree is a migration boundary, not a permanent second application.
Promote reusable native modules into the unified Android build, then remove
duplicate launchers and packaging after parity.

As of 2026-09-29, navigation, persisted physical sessions, signed offline model
admission, and the unified APK build are owned by `tokito-app`. Every former
Physical branch tip is retained under `migration/physical/*`; open Physical
issues were transferred into this repository and old PRs were closed with an
explicit destination issue. Both former GitHub repositories are archived and
their local checkouts were removed on 2026-09-29. They are never active build,
source, or release locations.

Exact iQOO evidence is deferred because the device is not available. All other
checks remain required; documentation must never represent hardware checks as
passed.
