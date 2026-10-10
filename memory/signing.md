# Android upload signing

Play-quality release builds use an **upload keystore** (not Android Debug).

**Buildkite release environment secrets** (injected only by the isolated
`release` queue into the blocked tag release step):

- `ANDROID_UPLOAD_KEYSTORE_BASE64`
- `ANDROID_UPLOAD_STORE_PASSWORD`
- `ANDROID_UPLOAD_KEY_ALIAS`
- `ANDROID_UPLOAD_KEY_PASSWORD`

**Operator backup** (this machine only, never commit): `~/.tokito-secrets/`.

The former Companion/Physical keys have no production lineage and are not used.
Tokito App owns a fresh key for package `dev.tokito.app`.

**Why:** Google Play identifies an application by package and signing cert.
Once Tokito App is released, this key becomes permanent release infrastructure.

**How to apply:** `configure-android-release.mjs` after `expo prebuild`;
`.buildkite/in-image/release-android.sh` verifies the APK is not debug-signed.

Final application release builds use the current Expo properties
`android.enableMinifyInReleaseBuilds=true` and
`android.enableShrinkResourcesInReleaseBuilds=true`, with Android's optimized
default ProGuard file. The source integration plugin enables the same settings
for ordinary Buildkite APK QA; the signing script enforces them again after
prebuild. An obsolete `enableProguardInReleaseBuilds` replacement is not proof
that R8 ran. Verify the actual `minifyReleaseWithR8` task and mapping output;
native libraries retain their consumer keep rules and do not shrink independently.

JNA's Android AAR retains desktop AWT adapter references. Its consumer rules
exclude only the four absent AWT types reported by R8, following upstream JNA
Android guidance. Do not suppress all missing classes or add AWT dependencies
to Android; UniFFI/JNA reflection symbols still need their explicit keep rules.

The ordinary `linux` worker executes repository-controlled pull-request code
and must never receive these values. A missing `release` agent or secret is a
hard publication failure, not permission to reuse the build worker.

**CI disk:** Buildkite uses named Cargo, npm, and Gradle cache volumes outside
the checkout. The release step builds only **arm64-v8a** and publishes artifacts
from `ci-out/`. Never persist decoded keystores or passwords in the checkout,
Docker image, artifacts, or a persistent global agent environment.
