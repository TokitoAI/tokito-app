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
- **AR is registered presentation, not an independent geometry system.** Every
  positional primitive uses the canonical frame graph with a current revision,
  timestamp, expiry, and uncertainty. Missing or stale registration clears the
  geometry. Call this product AR; `XR` is an umbrella term and is not used for
  modules, APIs, issues, or capability names.
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
| AR presentation or camera transforms | `docs/AR_RUNTIME.md`, `memory/device-runtime.md` |

## Repository hygiene

- Keep changes small and issue-scoped.
- Update the Technical Bible or add an ADR when behavior or boundaries change.
- Update `memory/` in the same commit when a verified project fact changes.
- Do not commit local datasets, captures, model weights, generated reports, APKs,
  AARs, or device logs.
- Preserve evidence manifests and benchmark summaries; do not hand-edit results.

## Memory index

| File | Contents |
|---|---|
| [memory/product.md](memory/product.md) | Product framing and non-goals |
| [memory/architecture.md](memory/architecture.md) | Runtime boundary and planned artifacts |
| [memory/device-runtime.md](memory/device-runtime.md) | Hardware/backend constraints and Python policy |
| [memory/integration.md](memory/integration.md) | Future Tokito/Companion contract boundary |
| [memory/evaluation.md](memory/evaluation.md) | Dataset, metric, and release-evidence rules |
