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

report_status() {
  local state="$1" description="$2"
  if [ -x /usr/local/bin/tokito-gh-token ] && [ -n "${BUILDKITE_COMMIT:-}" ]; then
    local token payload
    token="$(/usr/local/bin/tokito-gh-token)"
    payload="$(printf '{"state":"%s","context":"buildkite/tokito-app-release-readiness","description":"%s","target_url":"https://buildkite.com/tokito/tokito-app/builds/%s"}' \
      "$state" "$description" "${BUILDKITE_BUILD_NUMBER:-}")"
    curl --fail --silent --show-error \
      -X POST \
      -H "Authorization: Bearer $token" \
      -H "Accept: application/vnd.github+json" \
      -H "Content-Type: application/json" \
      --data "$payload" \
      "https://api.github.com/repos/TokitoAI/tokito-app/statuses/$BUILDKITE_COMMIT" >/dev/null || true
    unset token payload
  fi
}

if [ "${#missing[@]}" -ne 0 ]; then
  report_status failure "missing Buildkite signing environment: ${missing[*]}"
  printf 'release worker is missing required secret: %s\n' "${missing[@]}" >&2
  exit 1
fi
report_status success "Android upload-signing environment is ready"
echo "Android upload-signing environment is ready (values not exposed)."
