# Architecture

```text
React Native product shell
  |-- home, Cloud sign-in, project review, navigation
  |-- UniFFI -> Cloud cache/outbox -> https://api.tokito.dev
  +-- Expo control module -> native physical session activity
                               |-- CameraX / AR
                               |-- local voice and measurement parsing
                               +-- local LLM/VLM and physical tools
```

The React Native shell owns the single application flow. Signed-out users land
on the home surface and can start a local bench session. Signed-in users can
also choose a Cloud project/design and pass those identifiers through the
typed, low-rate native boundary. The native activity owns the session lifecycle
and high-rate hardware work.

The application registers its installation with Cloud, bootstraps Cloud-visible
resources, consumes ordered public-API updates, and keeps a local SQLite cache,
committed cursor, and offline outbox. The Cloud journal is authoritative only
for resources exposed through that contract.

Mobile never opens Desktop's embedded Postgres/workspaces, imports Desktop
source, or calls Desktop mutation paths. Desktop does not directly publish,
poll, or apply mobile-local state. See [PRODUCT_BOUNDARY.md](PRODUCT_BOUNDARY.md).

See [../memory/architecture.md](../memory/architecture.md) for crate details.
