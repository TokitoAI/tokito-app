# AGENTS.md

**For AI coding assistants working in this subtree.** Root `AGENTS.md` is the
controlling instruction set. Read it first, then this file, the relevant
`memory/` files, and the normative documents under `docs/`.
Inspect the Tokito project board before starting work:
<https://github.com/orgs/TokitoAI/projects/1>.

## Product

This directory is the native physical-intelligence boundary embedded inside the
single Tokito mobile application. It owns local perception, local text
reasoning, local speech, AR guidance, typed observations, and bounded physical
tools. Local bench workflows remain available without an account or network,
but there is no second product, launcher, repository roadmap, or release train.

React Native owns ordinary application navigation. This subtree owns the
latency-sensitive Android implementation. Integrate through the versioned
in-process boundary; never move camera frames, PCM, tensors, or high-rate
tracking through the JavaScript bridge.

## Authority order

1. [`docs/TECHNICAL_BIBLE.md`](docs/TECHNICAL_BIBLE.md) - normative behavior
2. Accepted ADRs in [`docs/decisions/`](docs/decisions/)
3. Typed contracts in [`docs/RUNTIME_CONTRACT.md`](docs/RUNTIME_CONTRACT.md)
4. Other human documentation
5. `memory/` - verified project facts for agents

If documents disagree, stop and reconcile them in the same change that resolves
the implementation conflict.

## Board workflow

All work is tracked on Tokito's organization project. Issues belong in
`TokitoAI/tokito-app` and use exactly one type label: `roadmap`, `epic`, `feature`, `task`,
`bug`, or `chore`.

- Set a card to **In progress** before implementation.
- Record decisions and meaningful evidence on the issue.
- Every PR contains `Closes #N` or `Fixes #N`.
- Move to **In review** when the PR is ready.
- Merge only with required evidence green; close and mark **Done** afterward.
- The board is the roadmap. Do not create a competing roadmap file.

## Delivery policy

All changes go through issue-scoped branches and pull requests in
`TokitoAI/tokito-app`. The root Buildkite pipeline is authoritative; it runs
this subtree's governance, Rust, Android, and unified-APK gates in the pinned
mobile image. Do not add a second GitHub Actions or subtree release pipeline.

## Non-negotiable rules

- **No stubs presented as capability.** Unimplemented tools are absent from the
  capability manifest, not fake-success responses.
- **No continuous whole-frame VLM.** Continuous work is lightweight;
  heavyweight inference is explicit, event-driven, bounded, and cancellable.
- **On-device by default.** Raw camera frames and audio do not leave the phone.
  Core inference, reasoning, speech, and AR operation also remain on-device.
- Spoken measurements are normalized only by `measurement-runtime`; never use
  an LLM to parse decimal separators, SI prefixes, units, ranges, or board
  relationships. Partial STT hypotheses cannot mutate measurement state, and
  an unconfirmed draft is never evidence.
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
- **Offline means offline.** Tokito design context may improve identity
  mapping, but absence of Cloud authentication or networking cannot disable generic board discovery,
  inspection, local conversation, or AR guidance.
- **One product boundary.** Mobile cannot add CAD editing or become a desktop
  workspace-management client.
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
