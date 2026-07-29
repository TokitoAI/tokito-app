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

**CI disk:** `android-release.yml` splits UniFFI and Gradle onto **two runners**
(each gets a fresh disk). Do **not** set `free-disk-space` `android: true` —
that deletes `/usr/local/lib/android` and `setup-android` fails with EACCES.
Native builds are **arm64-v8a only**.
