# Mobile / Desktop product boundary

Tokito mobile is one application. Project review and physical bench assistance
are connected surfaces in that application, not Companion and Physical modes.
The home screen always permits a projectless local bench session. Tokito Cloud
sign-in is optional and gates only Cloud resources; a Cloud project or design
may provide context when starting the same bench session.

The mobile application owns its native physical runtime, release train, local
session/evidence state, Cloud cache, and outbox. Tokito Desktop owns its native
editor runtime, embedded database, workspace files, and CAD mutation engine.
Neither product imports the other's source, opens the other's local data, or
depends on the other's internal event and resource types.

Desktop must not directly publish into mobile storage, poll mobile decisions,
or apply mobile state. Mobile must not act as a remote control or hidden
replication layer for Desktop. Both products may independently call public,
versioned Tokito Cloud contracts. Any cross-product data exchange must use an
explicitly versioned Cloud contract with authorization and migration rules.

Raw camera frames, microphone buffers, tensors, and high-rate tracking never
cross the JavaScript bridge or upload implicitly. Structured observations sync
only through an explicit, visible action. There is no compatibility fallback,
second launcher, mode chooser, or fake-success path.
