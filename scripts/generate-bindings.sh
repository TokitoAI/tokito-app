#!/usr/bin/env bash
# Generate UniFFI Kotlin (and optional Swift) bindings from the ffi cdylib.
# Output is gitignored — CI runs this to prove the UniFFI surface builds.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

cargo build -p tokito-companion-ffi
LIB="$ROOT/target/debug/libtokito_companion_ffi.so"
if [[ ! -f "$LIB" ]]; then
  # macOS
  LIB="$ROOT/target/debug/libtokito_companion_ffi.dylib"
fi
if [[ ! -f "$LIB" ]]; then
  echo "error: ffi shared library not found after build" >&2
  exit 1
fi

rm -rf "$ROOT/bindings"
cargo run -p tokito-companion-ffi --bin uniffi-bindgen -- generate \
  --library "$LIB" \
  --language kotlin \
  --out-dir "$ROOT/bindings/kotlin" \
  --config "$ROOT/uniffi.toml" \
  --no-format

echo "Generated Kotlin bindings under bindings/kotlin"
