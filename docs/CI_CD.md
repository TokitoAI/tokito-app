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
The pipeline references the published GHCR image by immutable SHA-256 digest,
not by a mutable tag, and authenticates only long enough to pull it.

## Required pull-request gates

1. Rust format, clippy, workspace tests, and UniFFI generation.
2. TypeScript release identity, Expo dependency validation, audit policy,
   forbidden-JS boundary, typecheck, lint, and tests.
3. Physical-runtime Python contract tests and Rust tests.
4. Physical Android unit tests, lint, and debug assembly.
5. Native Cloud bridge release unit tests and lint, final application release
   lint, unified arm64 APK assembly, and package-content verification.

The unified APK gate depends on the first four. Its standalone unsigned release APK is retained as a
Buildkite artifact for manual QA.

## Release flow

An immutable `v*` tag uploads the release pipeline only after normal CI. A
manual Buildkite block protects publication. The release step requires the
four `ANDROID_UPLOAD_*` secrets, builds an upload-signed arm64 APK and AAB,
rejects Android Debug certificates, uploads Buildkite artifacts, and creates
the matching immutable GitHub release.

The signed step targets the isolated `release` queue, not the PR-capable
`linux` build queue. Only a trusted tag job after the manual block may receive
the four variables. Release secrets are passed only to its release container.
The decoded keystore is temporary and must never enter git, the image, logs,
ordinary build workers, or artifacts.

Exact-device iQOO acceptance remains a separately recorded hardware gate. It
cannot be replaced by CI, an emulator, or a successful package build.

The unified app has optional device acceptance instrumentation under
`apps/mobile/device-tests`. After importing signed bundles in Local models, build
`:app:assembleRelease :app:assembleReleaseAndroidTest` with
`-Ptokito.deviceTests=true`, install both APKs, and run
`adb shell am instrument -w -r -e class dev.tokito.app.LocalModelsDeviceTest
dev.tokito.app.test/androidx.test.runner.AndroidJUnitRunner`.
This runs model resolution, multiple actual NPU generations, and speech playback
inside `dev.tokito.app`. Model files stay in that app's private storage; the
instrumentation APK has no production launcher. Local candidate models may
also require `-Ptokito.models.allowEvaluation=true` as documented by the voice
runtime. Do not set that property in publication builds.

Keep the device awake and unlocked during instrumentation. Tests foreground the
launcher; an OEM may suspend background NPU work while locked. Restore any temporary
USB stay-awake setting afterward. Inspect the instrumentation summary, not only the
ADB exit code: ADB can exit zero when tests failed. These checks do not certify
held-out physical-board recognition or authorize candidate-model redistribution.
