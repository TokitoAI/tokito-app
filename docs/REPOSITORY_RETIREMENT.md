# Former mobile repository retirement

`TokitoAI/tokito-app` is the only active mobile application repository. The
former Companion and Physical Runtime repositories are retained as read-only
archives so commit ancestry, release assets, issue redirects, and Android
signing provenance remain auditable.

## Preserved history

- Companion history is the Git base of this repository.
- Physical Runtime `main` was merged without squashing under
  `physical-runtime/`.
- Every observed old Physical local and remote branch tip is retained by an
  immutable `migration/physical/*` tag.
- Existing Companion and Physical GitHub releases remain on their archived
  repositories. They are historical artifacts, not current download channels.
- The production package remains `dev.tokito.companion` to preserve Android
  upgrade identity. Release signing must continue with the existing Play upload
  key; a newly generated key is not an acceptable fallback.

## Work migration

All open Physical Runtime issues were transferred to `TokitoAI/tokito-app` on
2026-09-29. GitHub keeps redirects from the former issue URLs, preserving the
discussion and attribution. The organization project is the sole roadmap.

Former open pull requests were closed only after their branch tips were
preserved. Remaining work is tracked here:

| Former PR | Canonical issue |
|---|---|
| `tokito-physical-runtime#113` AR insets and pose stability | `tokito-app#20` |
| `#97` FRDM reference package | `tokito-app#25` |
| `#92` partial populated 3D model | `tokito-app#22` |
| `#88` component locator | `tokito-app#43` |
| `#68` assembly-context importer | `tokito-app#26` |
| `#66` archive safety | `tokito-app#37` |
| `#65` resource-governor evidence | `tokito-app#47` |
| `#58` NXP source package validator | `tokito-app#25` |
| `#54` wake/VAD/audio interruption | `tokito-app#40` |

Porting is deliberate: do not merge a historical PR wholesale or resurrect its
old CI/application boundary. Apply the preserved source to the unified
architecture and validate it through Buildkite.

## Active delivery

- Buildkite pipeline: `tokito/tokito-app`
- Toolchain image: digest-pinned `ghcr.io/tokitoai/tokito-ci-mobile`
- Pull requests run Rust/UniFFI, mobile, Physical contracts, Physical Android,
  and a standalone-installable unified arm64 release APK gate.
- Tag builds create upload-signed APK/AAB artifacts and immutable GitHub
  releases after the release block is approved.

Exact-device iQOO validation remains visibly deferred while the phone is not
available. It is never inferred from JVM, emulator, or APK assembly success.
