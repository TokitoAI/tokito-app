# CI/CD and branch policy

Tokito Physical Runtime uses pull requests as the only merge path into `main`.
The repository is documentation and contract-first at bootstrap, so CI currently
checks governance, evidence contracts, examples, pull-request hygiene, and
tracked artifact hygiene. Runtime, Android, model, and device jobs must be
added when those artifacts land.

## Required flow

1. Create an issue-scoped branch from `main`.
2. Open a pull request that links the tracked issue with `Closes #N` or
   `Fixes #N`.
3. Let CI run on the pull request.
4. Resolve review conversations and keep history linear.
5. Merge only after required checks and owner review pass.

Direct pushes to `main`, force-pushes to `main`, and branch deletion for `main`
are not part of the accepted workflow. Issue
[#33](https://github.com/TokitoAI/tokito-physical-runtime/issues/33) tracks
turning this policy into GitHub branch protection or repository rules once the
organization plan supports it for this private repository.

## Current CI checks

The `CI` workflow runs on pull requests and pushes to `main` using the
organization-managed `playground-linux-x64` runner selected by the
`self-hosted`, `Linux`, `X64`, `tokito-vps`, and `private-build` labels. The
repository is explicitly authorized in the restricted Tokito VPS runner group;
the job uses the runner's system `python3` and does not depend on GitHub-hosted
runner billing capacity.

- Validates every schema under `schemas/` as JSON.
- Validates every example manifest under `examples/manifests/` against the
  supported JSON Schema subset used by `tools/validate_governance.py`.
- On pull requests, requires the PR body to link a closing issue with
  `Closes #N`, `Fixes #N`, or `Resolves #N`.
- On pushes to `main`, fails commits that do not look like reviewed PR
  merges or squash merges with an issue number.
- Fails if model binaries, Android packages, raw captures, generated reports,
  secrets, keys, or local datasets are tracked in Git.
- Verifies that Group 3 issues #25-#33 and standalone wave issues #37-#40 and
  #44-#45 stay traceable from documentation.
- Verifies that branch and pull-request policy is documented in `AGENTS.md` and
  the pull request template.

## Next jobs

These jobs are intentionally absent until the corresponding implementation
artifacts exist:

- Android build, unit, and instrumented conformance tests for #30.
- Standalone Android AR app build and UI smoke tests for #37.
- Model export and numerical conformance tests for #27.
- Evaluation and target-device benchmark report generation for #28.
- Calibration and abstention report generation for #29.
- Local LLM/VLM schema-constrained evaluation jobs for #38 and #39.
- End-to-end FRDM-MCXN236 hardware validation scenarios for #31 and #45.
- Assembly-context bundle import and verifier tests for #44.

Adding one of those jobs must include the machine-readable manifest it produces
and the exact evidence gate it supports.
