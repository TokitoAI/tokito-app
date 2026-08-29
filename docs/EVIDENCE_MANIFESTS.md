# Evidence manifests

These schemas define the machine-readable records required before Tokito can
claim a model, device, Android artifact, standalone AR workflow, reference
board package, or integration is ready. They are not roadmaps and do not
replace the GitHub project board. They make the issue acceptance criteria
verifiable by CI and later release tooling.

## Issue coverage

| Issue | Evidence contract |
|---|---|
| [#25](https://github.com/TokitoAI/tokito-physical-runtime/issues/25) | `schemas/security.review-manifest.schema.json` and `schemas/model.bundle-manifest.schema.json` |
| [#26](https://github.com/TokitoAI/tokito-physical-runtime/issues/26) | `schemas/corpus.dataset-card.schema.json` and `schemas/corpus.split-manifest.schema.json` |
| [#27](https://github.com/TokitoAI/tokito-physical-runtime/issues/27) | `schemas/model.bundle-manifest.schema.json` |
| [#28](https://github.com/TokitoAI/tokito-physical-runtime/issues/28) | `schemas/evaluation.run-manifest.schema.json` |
| [#29](https://github.com/TokitoAI/tokito-physical-runtime/issues/29) | `schemas/calibration.policy.schema.json` |
| [#30](https://github.com/TokitoAI/tokito-physical-runtime/issues/30) | `schemas/android.artifact-manifest.schema.json` |
| [#31](https://github.com/TokitoAI/tokito-physical-runtime/issues/31) | `schemas/release.evidence-manifest.schema.json` |
| [#32](https://github.com/TokitoAI/tokito-physical-runtime/issues/32) | `schemas/release.evidence-manifest.schema.json` blocks Companion integration until Gate 6 is accepted |
| [#33](https://github.com/TokitoAI/tokito-physical-runtime/issues/33) | `docs/CI_CD.md`, `AGENTS.md`, and `.github/workflows/ci.yml` |
| [#37](https://github.com/TokitoAI/tokito-physical-runtime/issues/37) | `schemas/android.artifact-manifest.schema.json` and `schemas/release.evidence-manifest.schema.json` |
| [#38](https://github.com/TokitoAI/tokito-physical-runtime/issues/38) | `schemas/model.bundle-manifest.schema.json`, `schemas/evaluation.run-manifest.schema.json`, and `schemas/calibration.policy.schema.json` |
| [#39](https://github.com/TokitoAI/tokito-physical-runtime/issues/39) | `schemas/model.bundle-manifest.schema.json`, `schemas/evaluation.run-manifest.schema.json`, and `schemas/calibration.policy.schema.json` |
| [#40](https://github.com/TokitoAI/tokito-physical-runtime/issues/40) | `schemas/evaluation.run-manifest.schema.json` and `schemas/release.evidence-manifest.schema.json` |
| [#44](https://github.com/TokitoAI/tokito-physical-runtime/issues/44) | `schemas/assembly.context-bundle.schema.json` |
| [#45](https://github.com/TokitoAI/tokito-physical-runtime/issues/45) | `schemas/frdm.validation-manifest.schema.json` |

## Contract rules

- Examples under `examples/manifests/` are schema-valid templates, not release
  evidence.
- Unknown device properties remain explicit strings such as
  `not-yet-measured`; they must be replaced by captured target-device evidence
  before a release gate can pass.
- Hash fields use named digest strings such as `sha256:<hex>`.
- Model and Android binaries are release artifacts, not Git-tracked files.
- Evaluation reports are generated from immutable manifests; hand-edited metric
  values are invalid.
- Board-specific values belong in imported bundles and fixtures, not runtime
  branches or hardcoded preview coordinates.
