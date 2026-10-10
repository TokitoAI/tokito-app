#!/usr/bin/env bash
set -euo pipefail

required=(
  ANDROID_UPLOAD_KEYSTORE_BASE64
  ANDROID_UPLOAD_STORE_PASSWORD
  ANDROID_UPLOAD_KEY_ALIAS
  ANDROID_UPLOAD_KEY_PASSWORD
  ANDROID_UPLOAD_CERT_SHA256
  GH_TOKEN
)
for name in "${required[@]}"; do
  [ -n "${!name:-}" ] || { echo "missing release secret: $name" >&2; exit 1; }
done

[[ "${ANDROID_UPLOAD_CERT_SHA256}" =~ ^[0-9a-fA-F]{64}$ ]] || {
  echo "ANDROID_UPLOAD_CERT_SHA256 must be a 64-character hex digest" >&2; exit 1;
}
export CI_PASS_ENV="${required[*]} BUILDKITE_TAG BUILDKITE_COMMIT"
exec "$(dirname "$0")/run-in-mobile-image.sh" .buildkite/in-image/release-android.sh
