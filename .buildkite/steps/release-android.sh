#!/usr/bin/env bash
set -euo pipefail

required=(
  ANDROID_UPLOAD_KEYSTORE_BASE64
  ANDROID_UPLOAD_STORE_PASSWORD
  ANDROID_UPLOAD_KEY_ALIAS
  ANDROID_UPLOAD_KEY_PASSWORD
)
for name in "${required[@]}"; do
  [ -n "${!name:-}" ] || { echo "missing release secret: $name" >&2; exit 1; }
done

export CI_PASS_ENV="${required[*]} BUILDKITE_TAG"
exec "$(dirname "$0")/run-in-mobile-image.sh" .buildkite/in-image/release-android.sh
