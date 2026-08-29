# AGENTS.md

**For AI coding assistants working in this repository.** Read this file first,
then the relevant `memory/` files and the normative documents under `docs/`.
Inspect the Tokito project board before starting work:
<https://github.com/orgs/TokitoAI/projects/1>.

## Product

Tokito Physical Runtime is a standalone, offline-first Android AR assistant for
electronics bench work. It owns local perception, local text reasoning, local
speech, AR guidance, typed observations, and bounded physical tools. It must
complete its baseline workflows without Tokito, Cloud, or Companion.

The project is separate from `tokito-companion`. Do not modify Companion while
working here unless a separately approved cross-repository issue explicitly
requires it. A future integration consumes a versioned Android interface; it
does not host the standalone product or merge runtime internals into Companion.

## Authority order

1. [`docs/TECHNICAL_BIBLE.md`](docs/TECHNICAL_BIBLE.md) - normative behavior
2. Accepted ADRs in [`docs/decisions/`](docs/decisions/)
3. Typed contracts in [`docs/RUNTIME_CONTRACT.md`](docs/RUNTIME_CONTRACT.md)
4. Other human documentation
5. `memory/` - verified project facts for agents

If documents disagree, stop and reconcile them in the same change that resolves
the implementation conflict.

## Board workflow

All work is tracked on Tokito's organization project. Issues belong in this
repository and use exactly one type label: `roadmap`, `epic`, `feature`, `task`,
`bug`, or `chore`.

- Set a card to **In progress** before implementation.
- Record decisions and meaningful evidence on the issue.
- Every PR contains `Closes #N` or `Fixes #N`.
- Move to **In review** when the PR is ready.
- Merge only with required evidence green; close and mark **Done** afterward.
- The board is the roadmap. Do not create a competing roadmap file.

## Main branch policy

`main` is PR-only by project policy. Require pull requests before merge, require
conversation resolution, preserve linear history, block force-push, block branch
deletion, and bind required checks once CI workflows exist. GitHub branch
protection for this private repository is tracked by issue #33 because the
current organization plan does not yet support the needed repository rules.
Do not make the repository public solely to work around that limitation.

All repository changes, including CI, documentation, and agent-maintenance
changes, go through an issue-scoped branch and pull request. An agent must not
push a change directly to `main`. When an agent owns or has been explicitly
delegated a pull request, it must merge the PR without waiting for another
human confirmation once all of the following are true: the PR is not a draft,
all required checks are successful, GitHub reports a clean merge state, and no
review conversation or requested change remains unresolved. After merging, the
agent must verify the `main` push workflow, close the issue if GitHub did not,
and set its project card to **Done**. A user instruction to hold or not merge
always overrides this default.

## CI runner policy

Every GitHub Actions job in this repository runs on the organization-managed
`playground-linux-x64` runner using the complete label set
`[self-hosted, Linux, X64, tokito-vps, private-build]`. Do not use
`ubuntu-latest`, `windows-latest`, `macos-latest`, another GitHub-hosted label,
or a partial VPS label set. This repository is authorized in the restricted
Tokito VPS runner group. `tools/validate_governance.py` enforces the invariant
across every workflow so a pull request cannot silently move CI back to billed
GitHub-hosted capacity.

A job that truly requires another operating system must not weaken or bypass
this rule. Track the requirement in an issue, provision an organization-managed
self-hosted runner with explicit labels, and update the validator and this
policy together through a separately reviewed PR.

## Non-negotiable rules

- **No stubs presented as capability.** Unimplemented tools are absent from the
  capability manifest, not fake-success responses.
- **No continuous whole-frame VLM.** Continuous work is lightweight;
  heavyweight inference is explicit, event-driven, bounded, and cancellable.
- **On-device by default.** Raw camera frames and audio do not leave the phone.
  Core inference, reasoning, speech, and AR operation also remain on-device.
  Any future off-device mode requires a new ADR and explicit user consent.
- **Abstain over guessing.** Identity-sensitive output must carry confidence and
  evidence; below threshold, return a typed abstention.
- **No model binaries in Git.** Use signed, hash-addressed release artifacts or
  the approved model registry with complete provenance and license metadata.
- **No frame-level data leakage.** Dataset splits are by physical board and
  acquisition session, never adjacent frames from the same session.
- **Python is tooling, not the shipping hot path.** Training/export/evaluation
  may use Python. Android production code uses a stable native interface unless
  an ADR backed by device measurements changes the decision.
- **Standalone means standalone.** Tokito design context may improve identity
  mapping, but absence of Tokito cannot disable generic board discovery,
  inspection, local conversation, or AR guidance.
- **No Companion drift.** This product cannot add CAD editing or broaden
  Companion's read/review product boundary.
- **No hardcoded device assumptions.** Hardware features are discovered through
  a capability profile and unsupported paths fail closed.
- **Every release is reproducible.** Model digest, preprocessing, runtime,
  device profile, benchmark corpus revision, and results are recorded together.

## Before implementation

Read, in full:

| Work | Required reading |
|---|---|
| Any runtime code | `docs/TECHNICAL_BIBLE.md`, `docs/ARCHITECTURE.md` |
| Tool/schema changes | `docs/RUNTIME_CONTRACT.md`, `memory/integration.md` |
| Vision/model work | `docs/MODEL_STRATEGY.md`, `docs/EVALUATION.md` |
| Audio work | `docs/TECHNICAL_BIBLE.md` voice section, `docs/EVALUATION.md` |
| Camera/audio persistence or telemetry | `docs/SECURITY_AND_PRIVACY.md` |
| Android packaging/backend work | ADR 0003, `memory/device-runtime.md` |

## Repository hygiene

- Keep changes small and issue-scoped.
- Update the Technical Bible or add an ADR when behavior or boundaries change.
- Update `memory/` in the same commit when a verified project fact changes.
- Do not commit local datasets, captures, model weights, generated reports, APKs,
  AARs, or device logs.
- Preserve evidence manifests and benchmark summaries; do not hand-edit results.
- Keep `.github/workflows/ci.yml` and `tools/validate_governance.py` green for
  documentation, schema, and evidence-contract changes.
- Never weaken the VPS-only workflow validation to work around runner or billing
  failures; repair the self-hosted runner or its repository authorization.

## Memory index

| File | Contents |
|---|---|
| [memory/product.md](memory/product.md) | Product framing and non-goals |
| [memory/architecture.md](memory/architecture.md) | Runtime boundary and planned artifacts |
| [memory/device-runtime.md](memory/device-runtime.md) | Hardware/backend constraints and Python policy |
| [memory/integration.md](memory/integration.md) | Future Tokito/Companion contract boundary |
| [memory/evaluation.md](memory/evaluation.md) | Dataset, metric, and release-evidence rules |
