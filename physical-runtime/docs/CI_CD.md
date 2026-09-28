# Physical workspace CI within the unified app

The physical workspace has no independent pipeline, runner, release, or
branch policy. [`../../docs/CI_CD.md`](../../docs/CI_CD.md) is the sole CI/CD
authority for `tokito-app`.

Buildkite validates the imported runtime's contracts, Rust crates, Android
unit tests, lint, assembly, and its integration into the single installable
arm64 application. Pull-request jobs never receive release signing material.
Exact-device iQOO evidence remains a separate, explicit hardware gate and may
not be inferred from JVM, emulator, or APK success.

Do not restore the archived repository's GitHub Actions workflows,
self-hosted-runner labels, standalone release pipeline, or second APK. Add any
new runtime check to `tokito-app/.buildkite/` and keep it inside the pinned
mobile build image owned by `tokito-infra`.
