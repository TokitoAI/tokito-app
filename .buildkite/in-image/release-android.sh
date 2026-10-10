#!/usr/bin/env bash
set -euo pipefail

tag="${BUILDKITE_TAG:?tag build required}"
[[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z.-]+)?$ ]]
test "$(git rev-parse HEAD)" = "$(git rev-parse "refs/tags/${tag}^{commit}")"

cd apps/mobile
npm ci
RELEASE_TAG="$tag" npm run release:validate
npx expo install --check
npx expo-doctor
npm run audit
TOKITO_ANDROID_TARGETS=arm64-v8a ../../scripts/build-android-ffi.sh
npx expo prebuild --platform android --clean --non-interactive
node scripts/configure-android-release.mjs
./android/gradlew -p android :tokito-companion-ffi:testReleaseUnitTest :tokito-companion-ffi:lintRelease assembleRelease bundleRelease \
  -PreactNativeArchitectures=arm64-v8a --no-daemon --stacktrace

apk="$(find android/app/build/outputs/apk/release -name '*.apk' -print -quit)"
aab="$(find android/app/build/outputs/bundle/release -name '*.aab' -print -quit)"
test -n "$apk" && test -n "$aab"
mkdir -p ../../ci-out
apk_out="../../ci-out/Tokito-${tag}.apk"
aab_out="../../ci-out/Tokito-${tag}.aab"
cp "$apk" "$apk_out"
cp "$aab" "$aab_out"

unzip -l "$apk_out" | grep -E 'index.android.bundle|assets/index'
unzip -l "$apk_out" | grep 'libtokito_companion_ffi.so'
build_tools="$(ls "$ANDROID_HOME/build-tools" | sort -V | tail -1)"
"$ANDROID_HOME/build-tools/$build_tools/apksigner" verify --print-certs "$apk_out" | tee /tmp/apk-certs.txt
! grep -qi 'CN=Android Debug' /tmp/apk-certs.txt

gh release create "$tag" \
  --title "Tokito ${tag}" \
  --notes "Unified Tokito Android release ${tag}. Package: dev.tokito.companion. Upload-signed arm64 APK and Play AAB." \
  "$apk_out" "$aab_out"
