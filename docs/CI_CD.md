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

Release APK QA enables final-application code/resource shrinking and Android's
optimized R8 defaults through the source Expo integration plugin. This is the
same optimization mode enforced by publication signing configuration. Native
libraries remain unshrunk independently and supply consumer keep rules.
Inspect the actual R8 task and mapping artifacts; a release build name alone
does not demonstrate that optimization ran. On-device bridge and local-model
checks are still required after an optimized build succeeds.
Both APK gates fail if the R8 mapping is absent or empty and retain it as a
Buildkite artifact for deobfuscating that exact build's crash stack traces.

## Release flow

An immutable `v*` tag uploads CI and publication steps into the same Buildkite
build. Publication explicitly depends on both the manual approval block and
`unified-android`, whose dependencies include all four preceding CI gates.
Uploading a pipeline is not waiting for it: never remove that dependency.
The release step requires the four signing `ANDROID_UPLOAD_*` secrets plus
`ANDROID_UPLOAD_CERT_SHA256` (64 hex characters) and `GH_TOKEN`, builds an
upload-signed arm64 APK and AAB, verifies their expected upload certificate,
rejects Android Debug certificates, checks APK 16 KiB alignment, and creates
the matching immutable GitHub release.

The signed step targets the isolated `release` queue, not the PR-capable
`linux` build queue. Only a trusted tag job after the manual block may receive
the signing variables and GitHub publication credential. Release secrets are
passed only to its release container; GitHub authentication is checked before
the expensive build. Signing material is written to a private temporary
container directory with an EXIT cleanup trap, not the shared checkout or
cache volumes. Gradle reads passwords from the release environment rather
than generated properties. The decoded keystore must never enter git, the
image, logs, ordinary build workers, or artifacts. Configure the expected
certificate alongside the trusted upload key; absence fails closed rather
than accepting whichever non-debug key happened to sign the output.

Exact-device iQOO acceptance remains a separately recorded hardware gate. It
cannot be replaced by CI, an emulator, or a successful package build.

The unified app has optional device acceptance instrumentation under
`apps/mobile/device-tests`. After importing signed bundles in Local models, build
`:app:assembleRelease :app:assembleReleaseAndroidTest` with
`-Ptokito.deviceTests=true`, install both APKs, and run
the installed runner using the explicit packaged baseline method selection in
`docs/RELEASE_DEVICE_QA.md`.
This runs model resolution, multiple actual NPU generations, and speech playback
inside `dev.tokito.app`. Model files stay in that app's private storage; the
instrumentation APK has no production launcher. Local candidate models may
also require `-Ptokito.models.allowEvaluation=true` as documented by the voice
runtime. Do not set that property in publication builds.

Keep the device unlocked with the actual target Activity RESUMED during instrumentation.
Raw runtime tests bind to that owner; losing foreground closes/cancels the runtime.
An awake display or instrumentation importance does not prevent process suspension.
Do not add hidden keepalives or override OEM power policy to claim a deadline pass.
Inspect the instrumentation summary, not only the
ADB exit code: ADB can exit zero when tests failed. These checks do not certify
held-out physical-board recognition or authorize candidate-model redistribution.
