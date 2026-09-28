# Companion / Desktop product boundary

Tokito Companion and Tokito Desktop are separate products.

Companion owns its mobile runtime, release train, local SQLite state, outbox,
and Cloud client. Desktop owns its native editor runtime, embedded database,
workspace files, and CAD mutation engine. Neither product imports the other's
source, opens the other's local data, or depends on the other's internal event
and resource types.

Desktop must not publish projects to Companion, poll Companion decisions, or
apply Companion state. Companion must not act as a remote control or replication
layer for Desktop. Both products may independently call public, versioned
Tokito Cloud contracts; shared authentication is identity, not project sync.

Any future interoperability requires a tracked cross-repo design, an explicit
versioned Cloud contract, authorization and migration rules, and end-to-end
evidence. It must not be introduced as a local import, compatibility fallback,
or hidden legacy path.

`tokito-companion-core` includes a regression test that rejects direct Desktop
repository/path dependencies. Tokito Desktop has the reciprocal source gate in
`services::desktop_mobile_boundary_tests` (TokitoAI/tokito#793).
