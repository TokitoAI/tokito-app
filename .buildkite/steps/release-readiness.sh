#!/usr/bin/env bash
set -euo pipefail

required=(
  ANDROID_UPLOAD_KEYSTORE_BASE64
  ANDROID_UPLOAD_STORE_PASSWORD
  ANDROID_UPLOAD_KEY_ALIAS
  ANDROID_UPLOAD_KEY_PASSWORD
)
missing=()
for name in "${required[@]}"; do
  [ -n "${!name:-}" ] || missing+=("$name")
done
if [ "${#missing[@]}" -ne 0 ]; then
  printf 'release worker is missing required secret: %s\n' "${missing[@]}" >&2
  exit 1
fi
echo "Android upload-signing environment is ready (values not exposed)."
