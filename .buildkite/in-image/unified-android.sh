#!/usr/bin/env bash
set -euo pipefail
cd apps/mobile
npm ci
TOKITO_ANDROID_TARGETS=arm64-v8a ../../scripts/build-android-ffi.sh
npx expo prebuild --platform android --clean --non-interactive
./android/gradlew -p android :app:assembleRelease \
  -PreactNativeArchitectures=arm64-v8a --no-daemon --stacktrace
mkdir -p ../../ci-out
apk="$(find android/app/build/outputs/apk/release -name '*.apk' -print -quit)"
test -n "$apk"
cp "$apk" ../../ci-out/tokito-app-release-unsigned-arm64.apk
unzip -l ../../ci-out/tokito-app-release-unsigned-arm64.apk | grep -E 'index.android.bundle|assets/index'
unzip -l ../../ci-out/tokito-app-release-unsigned-arm64.apk | grep 'libtokito_companion_ffi.so'
unzip -l ../../ci-out/tokito-app-release-unsigned-arm64.apk | grep 'libgeniex_plugin_qairt.so'
unzip -l ../../ci-out/tokito-app-release-unsigned-arm64.apk | grep 'libsherpa-onnx-jni.so'
"$ANDROID_HOME/cmdline-tools/latest/bin/apkanalyzer" manifest print "$apk" | grep 'android:extractNativeLibs="true"'
