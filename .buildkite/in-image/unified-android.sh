#!/usr/bin/env bash
set -euo pipefail
cd apps/mobile
npm ci
TOKITO_ANDROID_TARGETS=arm64-v8a ../../scripts/build-android-ffi.sh
npx expo prebuild --platform android --clean --non-interactive
./android/gradlew -p android :app:assembleDebug \
  -PreactNativeArchitectures=arm64-v8a --no-daemon --stacktrace
mkdir -p ../../ci-out
apk="$(find android/app/build/outputs/apk/debug -name '*.apk' -print -quit)"
test -n "$apk"
cp "$apk" ../../ci-out/tokito-app-debug-arm64.apk
unzip -l ../../ci-out/tokito-app-debug-arm64.apk | grep -E 'index.android.bundle|assets/index'
unzip -l ../../ci-out/tokito-app-debug-arm64.apk | grep 'libtokito_companion_ffi.so'
