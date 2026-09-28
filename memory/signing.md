# Android upload signing

Play-quality release builds use an **upload keystore** (not Android Debug).

**Buildkite release environment secrets** (injected only by the isolated
`release` queue into the blocked tag release step):

- `ANDROID_UPLOAD_KEYSTORE_BASE64`
- `ANDROID_UPLOAD_STORE_PASSWORD`
- `ANDROID_UPLOAD_KEY_ALIAS`
- `ANDROID_UPLOAD_KEY_PASSWORD`

**Operator backup** (this machine only, never commit): `~/.tokito-secrets/`.

**Why:** Google Play identifies the app by signing cert. Losing the upload
keystore blocks updates unless Play App Signing reset is used.

**How to apply:** `configure-android-release.mjs` after `expo prebuild`;
`.buildkite/in-image/release-android.sh` verifies the APK is not debug-signed.

The ordinary `linux` worker executes repository-controlled pull-request code
and must never receive these values. A missing `release` agent or secret is a
hard publication failure, not permission to reuse the build worker or generate
a replacement key.

**CI disk:** Buildkite uses named Cargo, npm, and Gradle cache volumes outside
the checkout. The release step builds only **arm64-v8a** and publishes artifacts
from `ci-out/`. Never persist decoded keystores or passwords in the checkout,
Docker image, artifacts, or a persistent global agent environment.
