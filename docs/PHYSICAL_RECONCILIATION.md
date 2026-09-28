# Physical Runtime reconciliation

Tracking: [#3](https://github.com/TokitoAI/tokito-app/issues/3)

## Preserved baseline

Physical Runtime `main` was imported with full Git ancestry under
`physical-runtime/`. Every observed local and remote branch tip was additionally
published as an immutable `migration/physical/...` tag before cleanup decisions.
These tags are recovery anchors, not active development branches.

The imported `main` already contains the unified camera application, dialogue,
voice, measurement, AR, native runtime, vision foundation, model and evidence
contracts present at migration time. The original repositories remain readable
until the final retirement gate.

## Source classifications

| Source | Treatment |
|---|---|
| `physical/main` | Canonical imported baseline |
| Remote feature/fix/wave branches | Preserved by `migration/physical/remote/*` tags; audit against baseline before porting |
| Local worktree branches | Preserved by `migration/physical/local/*` tags; audit against baseline before porting |
| `dialogue-runtime/.cxx` in the old checkout | Generated Android/CMake output; not application source |
| Old checkout `third_party/llama.cpp` | Separate upstream checkout; canonical dependency remains a pinned submodule |

## Open pull-request disposition

The old repository PRs are not merged blindly into the unified application.
Each is compared against the imported baseline and then either ported through a
new `tokito-app` issue/PR or closed as superseded. This avoids importing stale
CI configuration or branch ancestry while preserving every commit through the
migration tags.

## Required checks before closing reconciliation

- Submodule paths resolve from the new repository root.
- Physical governance and contract tests run from `physical-runtime/`.
- Android unit/lint/assembly tasks run with the supported SDK toolchain.
- All old open PRs have an explicit port/superseded disposition.
- No unique tracked source exists only in an unpreserved worktree.
- Exact-device tests are recorded as deferred while hardware is unavailable.
