$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
$Main = Join-Path $Root "apps/mobile/modules/tokito-companion-ffi/android/src/main"
$JniOut = Join-Path $Main "jniLibs"
$Bindings = Join-Path $Root "bindings/kotlin"
$KotlinOut = Join-Path $Main "java/uniffi/tokito_companion_ffi"
$ApiLevel = if ($env:TOKITO_ANDROID_API_LEVEL) { $env:TOKITO_ANDROID_API_LEVEL } else { "24" }

if (-not $env:ANDROID_NDK_HOME) {
  $Sdk = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA "Android/Sdk" }
  $NdkRoot = Join-Path $Sdk "ndk"
  $Ndk = Get-ChildItem -LiteralPath $NdkRoot -Directory | Sort-Object Name -Descending | Select-Object -First 1
  if (-not $Ndk) { throw "Android NDK not found under $NdkRoot" }
  $env:ANDROID_NDK_HOME = $Ndk.FullName
}

Push-Location $Root
try {
  cargo ndk --platform $ApiLevel -t arm64-v8a -o $JniOut build -p tokito-companion-ffi --release
  if ($LASTEXITCODE -ne 0) { throw "cargo ndk failed" }

  $Library = Join-Path $JniOut "arm64-v8a/libtokito_companion_ffi.so"
  cargo run -p tokito-companion-ffi --bin uniffi-bindgen -- generate --library $Library --language kotlin --out-dir $Bindings --config (Join-Path $Root "uniffi.toml") --no-format
  if ($LASTEXITCODE -ne 0) { throw "UniFFI binding generation failed" }

  New-Item -ItemType Directory -Force -Path $KotlinOut | Out-Null
  Copy-Item -LiteralPath (Join-Path $Bindings "uniffi/tokito_companion_ffi/tokito_companion_ffi.kt") -Destination (Join-Path $KotlinOut "tokito_companion_ffi.kt") -Force
  node (Join-Path $Root "scripts/write-android-ffi-manifest.mjs")
  if ($LASTEXITCODE -ne 0) { throw "UniFFI manifest generation failed" }
} finally {
  Pop-Location
}
