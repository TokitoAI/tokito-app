# AGENTS.md

Read this file first, then relevant `memory/` and `docs/` files. Inspect the
Tokito organization board before starting work.

## Product

`tokito-app` is the single Tokito mobile application. It combines Cloud-backed
project review with an offline-first native physical-intelligence workspace.
There is one application identity, navigation system, release train, and user
experience.

The application shell is React Native/TypeScript. CameraX, AR, audio, local
models, physical tools, and latency-sensitive lifecycle work remain native
Android components. Raw camera frames and microphone buffers never cross the
React Native bridge. The bridge carries bounded commands, capability state,
typed observations, and lifecycle events only.

Local bench workflows work without authentication or networking. Cloud
projects and synchronization use public, versioned `tokito-api` contracts.
Unsupported capabilities are absent; never ship a stub or fake success.

## Board and pull requests

The board is the roadmap. The consolidation epic is
<https://github.com/TokitoAI/tokito-app/issues/1>.

- Every change has an issue and board card.
- Move work through In progress -> In review -> Done.
- PR descriptions use `Closes #N` or `Fixes #N`.
- Merge only when required checks pass and conversations are resolved.
- Record unavailable exact-device checks as deferred; never mark them passed.

## Architecture rules

- One launcher and final package/signing lineage.
- React Native owns ordinary navigation and Cloud/product surfaces.
- Native Android owns camera, AR, audio, local inference, and physical-session lifecycle.
- Never stream frames, PCM, tensors, or high-rate tracking over JS/UniFFI boundaries.
- Physical use cannot require sign-in or silently fall back to remote models.
- Raw media stays on-device and ephemeral by default.
- Cloud sync is limited to explicitly authorized structured data.
- Context and observations are revision-bound; mismatch is a conflict.
- Capability discovery precedes use. Missing capability means unavailable.
- Mobile is not a schematic or PCB CAD editor.

## Repository shape

- `apps/mobile/` - unified Expo/React Native shell.
- `crates/` - Cloud core and UniFFI boundary.
- `physical-runtime/` - embedded native physical workspace and preserved design history.
- `docs/decisions/` - accepted architecture decisions.
- `memory/` - verified agent facts.

The Physical subtree is not a second product. Duplicate launchers and obsolete
standalone packaging must not be restored.

## Verification

Buildkite is the only CI/CD authority. Pull requests must pass the pinned-image
Rust/UniFFI, mobile, Physical contracts, Physical Android, and unified APK gates.

Run all checks relevant to changed layers: Rust formatting/clippy/tests,
TypeScript lint/typecheck/tests, Android unit/emulator tests, Gradle lint and
assembly, contract tests, package/signature checks, and security audits.
Hardware-only iQOO evidence remains explicit but may be deferred while the
device is unavailable.

## Memory index

| File | Contents |
|---|---|
| [memory/product.md](memory/product.md) | Unified product framing |
| [memory/architecture.md](memory/architecture.md) | Shell/native/data boundaries |
| [memory/migration.md](memory/migration.md) | Consolidation and retirement gates |
| [memory/auth-contract.md](memory/auth-contract.md) | Cloud authentication |
| [memory/signing.md](memory/signing.md) | Android signing lineage |

