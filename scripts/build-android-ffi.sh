#!/usr/bin/env bash
# Build libtokito_companion_ffi.so for Android ABIs + generate Kotlin UniFFI bindings
# into the Expo local module (Mozilla UniFFI Kotlin path).
#
# Docs:
#   https://mozilla.github.io/uniffi-rs/latest/kotlin/configuration.html
#   https://mozilla.github.io/uniffi-rs/latest/kotlin/gradle.html
# Requires: cargo, cargo-ndk, Android NDK (ANDROID_NDK_HOME or ndk via sdkmanager)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODULE="$ROOT/apps/mobile/modules/tokito-companion-ffi"
JNI_OUT="$MODULE/android/src/main/jniLibs"
KOTLIN_OUT="$MODULE/android/src/main/java"
API_LEVEL="${TOKITO_ANDROID_API_LEVEL:-24}"
# Device tablets are arm64; keep armeabi-v7a for older phones; x86_64 for emulator CI.
TARGETS="${TOKITO_ANDROID_TARGETS:-arm64-v8a,armeabi-v7a,x86_64}"

cd "$ROOT"

if ! command -v cargo-ndk >/dev/null 2>&1; then
  echo "Installing cargo-ndk…"
  cargo install cargo-ndk --locked
fi

if [[ -z "${ANDROID_NDK_HOME:-}" && -z "${NDK_HOME:-}" ]]; then
  # Common CI / local sdk paths
  for candidate in \
    "${ANDROID_HOME:-}/ndk-bundle" \
    "${ANDROID_SDK_ROOT:-}/ndk-bundle" \
    "${ANDROID_HOME:-}/ndk"/* \
    "${ANDROID_SDK_ROOT:-}/ndk"/*; do
    if [[ -d "$candidate" ]]; then
      export ANDROID_NDK_HOME="$candidate"
      break
    fi
  done
fi

if [[ -z "${ANDROID_NDK_HOME:-}" || ! -d "${ANDROID_NDK_HOME}" ]]; then
  echo "error: ANDROID_NDK_HOME not set / NDK not found" >&2
  exit 1
fi

echo "NDK: $ANDROID_NDK_HOME"
echo "Targets: $TARGETS (API $API_LEVEL)"

mkdir -p "$JNI_OUT"
# cargo-ndk writes abi folders under -o
cargo ndk \
  --platform "$API_LEVEL" \
  $(echo "$TARGETS" | tr ',' '\n' | sed 's/^/-t /') \
  -o "$JNI_OUT" \
  build -p tokito-companion-ffi --release

# Pick one built .so for bindgen metadata (host uses the aarch64 android lib)
SO=""
for abi in arm64-v8a armeabi-v7a x86_64; do
  candidate="$JNI_OUT/$abi/libtokito_companion_ffi.so"
  if [[ -f "$candidate" ]]; then
    SO="$candidate"
    break
  fi
done
if [[ -z "$SO" ]]; then
  echo "error: no libtokito_companion_ffi.so under $JNI_OUT" >&2
  find "$JNI_OUT" -type f | head -50 >&2 || true
  exit 1
fi

rm -rf "$ROOT/bindings/kotlin"
mkdir -p "$ROOT/bindings/kotlin"
cargo run -p tokito-companion-ffi --bin uniffi-bindgen -- generate \
  --library "$SO" \
  --language kotlin \
  --out-dir "$ROOT/bindings/kotlin" \
  --config "$ROOT/uniffi.toml" \
  --no-format

# Install generated Kotlin into the Expo module source tree
mkdir -p "$KOTLIN_OUT"
rm -rf "$KOTLIN_OUT/uniffi"
cp -a "$ROOT/bindings/kotlin/." "$KOTLIN_OUT/"

echo "Android FFI ready:"
find "$JNI_OUT" -name '*.so' | sort
find "$KOTLIN_OUT" -name '*.kt' | sort | head -20
