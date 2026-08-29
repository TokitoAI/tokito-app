# Product facts

Tokito Physical Runtime is a reusable on-device perception/speech layer, not a
mobile application and not a reasoning agent. Its output is typed physical
evidence that a Tokito client can compare with design context.

**Why:** Keeping the runtime separate prevents camera/model/audio concerns from
changing Companion's mobile review boundary or coupling model releases to UI
releases.

**How to apply:** implement and validate the runtime standalone. Any future
Companion consumption requires its own cross-repository tracked work.
