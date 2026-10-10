#!/usr/bin/env bash
set -euo pipefail

: "${GH_TOKEN:?isolated release credential required}"
: "${ANDROID_UPLOAD_CERT_SHA256:?expected upload certificate required}"
[[ "$ANDROID_UPLOAD_CERT_SHA256" =~ ^[0-9a-fA-F]{64}$ ]]

tag="${BUILDKITE_TAG:?tag build required}"
[[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z.-]+)?$ ]]
test "$(git rev-parse HEAD)" = "$(git rev-parse "refs/tags/${tag}^{commit}")"
test "$(git rev-parse HEAD)" = "${BUILDKITE_COMMIT:?build commit required}"
gh auth status >/dev/null
# A trusted release container may hold credentials, but neither its shared
# checkout nor persistent cache volumes may retain decoded signing material.
export TOKITO_RELEASE_SIGNING_DIR="$(mktemp -d /tmp/tokito-signing.XXXXXX)"
trap 'rm -rf -- "$TOKITO_RELEASE_SIGNING_DIR"' EXIT

cd apps/mobile
npm ci
RELEASE_TAG="$tag" npm run release:validate
npx expo install --check
npx expo-doctor
npm run audit
TOKITO_ANDROID_TARGETS=arm64-v8a ../../scripts/build-android-ffi.sh
npx expo prebuild --platform android --clean --non-interactive
node scripts/configure-android-release.mjs
./android/gradlew -p android :tokito-companion-ffi:testReleaseUnitTest :tokito-companion-ffi:lintRelease :app:lintRelease assembleRelease bundleRelease \
  -PreactNativeArchitectures=arm64-v8a --no-daemon --stacktrace

apk="$(find android/app/build/outputs/apk/release -name '*.apk' -print -quit)"
aab="$(find android/app/build/outputs/bundle/release -name '*.aab' -print -quit)"
test -n "$apk" && test -n "$aab"
mkdir -p ../../ci-out
test -s android/app/build/outputs/mapping/release/mapping.txt
cp android/app/build/outputs/mapping/release/mapping.txt "../../ci-out/Tokito-${tag}-r8-mapping.txt"
apk_out="../../ci-out/Tokito-${tag}.apk"
aab_out="../../ci-out/Tokito-${tag}.aab"
cp "$apk" "$apk_out"
cp "$aab" "$aab_out"

unzip -l "$apk_out" | grep -E 'index.android.bundle|assets/index'
unzip -l "$apk_out" | grep 'libtokito_companion_ffi.so'
build_tools="$(ls "$ANDROID_HOME/build-tools" | sort -V | tail -1)"
"$ANDROID_HOME/build-tools/$build_tools/zipalign" -c -P 16 -v 4 "$apk_out"
"$ANDROID_HOME/build-tools/$build_tools/apksigner" verify --print-certs "$apk_out" > "$TOKITO_RELEASE_SIGNING_DIR/apk-certs.txt"
! grep -qi 'CN=Android Debug' "$TOKITO_RELEASE_SIGNING_DIR/apk-certs.txt"
actual_cert="$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$TOKITO_RELEASE_SIGNING_DIR/apk-certs.txt")"
test "${actual_cert,,}" = "${ANDROID_UPLOAD_CERT_SHA256,,}"
# Verify the Play bundle is also signed by the expected upload certificate.
LC_ALL=C jarsigner -J-Duser.language=en -verify "$aab_out" > "$TOKITO_RELEASE_SIGNING_DIR/aab-verification.txt"
grep -q '^jar verified\.' "$TOKITO_RELEASE_SIGNING_DIR/aab-verification.txt"
! grep -q 'unsigned entries' "$TOKITO_RELEASE_SIGNING_DIR/aab-verification.txt"
keytool -J-Duser.language=en -J-Duser.country=US -printcert -jarfile "$aab_out" > "$TOKITO_RELEASE_SIGNING_DIR/aab-certs.txt"
actual_aab_cert="$(sed -n 's/^[[:space:]]*SHA256: //p' "$TOKITO_RELEASE_SIGNING_DIR/aab-certs.txt" | tr -d ':')"
test "${actual_aab_cert,,}" = "${ANDROID_UPLOAD_CERT_SHA256,,}"

gh release create "$tag" --verify-tag --repo TokitoAI/tokito-app \
  --title "Tokito ${tag}" \
  --notes "Unified Tokito Android release ${tag}. Package: dev.tokito.app. Upload-signed arm64 APK and Play AAB." \
  "$apk_out" "$aab_out"
