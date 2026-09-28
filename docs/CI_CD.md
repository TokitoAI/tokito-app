# CI and Android releases

Buildkite is the only CI/CD system for `tokito-app`. The pipeline runs on the
organization-managed `linux` queue and reports commit statuses to GitHub.
GitHub Actions workflows are intentionally absent so account billing cannot
block validation or releases.

## Reproducible build environment

`tokito-infra/ci/runner/Dockerfile.mobile` defines the mobile build image. It
pins Node 22.14.0, Rust 1.92.0, cargo-ndk 4.1.2, JDK 17, Android platform and
build tools 35, NDK 27.1.12297006, and CMake 3.22.1. Buildkite runs repository
scripts inside that image; agent-host toolchains are never used.

## Required pull-request gates

1. Rust format, clippy, workspace tests, and UniFFI generation.
2. TypeScript release identity, Expo dependency validation, audit policy,
   forbidden-JS boundary, typecheck, lint, and tests.
3. Physical-runtime Python contract tests and Rust tests.
4. Physical Android unit tests, lint, and debug assembly.
5. Unified arm64 APK assembly and package-content verification.

The unified APK gate depends on the first four. Its debug APK is retained as a
Buildkite artifact for manual QA.

## Release flow

An immutable `v*` tag uploads the release pipeline only after normal CI. A
manual Buildkite block protects publication. The release step requires the
four `ANDROID_UPLOAD_*` secrets, builds an upload-signed arm64 APK and AAB,
rejects Android Debug certificates, uploads Buildkite artifacts, and creates
the matching immutable GitHub release.

Release secrets are passed only to the release container. The decoded
keystore is temporary and must never enter git, the image, logs, or artifacts.

Exact-device iQOO acceptance remains a separately recorded hardware gate. It
cannot be replaced by CI, an emulator, or a successful package build.
