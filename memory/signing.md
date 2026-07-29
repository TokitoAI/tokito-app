# Android upload signing

Play-quality release builds use an **upload keystore** (not Android Debug).

**CI secrets** (GitHub → Settings → Secrets):

- `ANDROID_UPLOAD_KEYSTORE_BASE64`
- `ANDROID_UPLOAD_STORE_PASSWORD`
- `ANDROID_UPLOAD_KEY_ALIAS`
- `ANDROID_UPLOAD_KEY_PASSWORD`

**Operator backup** (this machine only, never commit): `~/.tokito-secrets/`.

**Why:** Google Play identifies the app by signing cert. Losing the upload
keystore blocks updates unless Play App Signing reset is used.

**How to apply:** `configure-android-release.mjs` after `expo prebuild`; workflow
`android-release.yml` verifies the APK is not debug-signed.

**CI disk:** Release job frees runner space, builds UniFFI + RN native for
**arm64-v8a only**, and `cargo clean`s before Gradle — dual-ABI + UniFFI
overflowed `ubuntu-latest` (`No space left on device` on v0.1.2-pre.3).
