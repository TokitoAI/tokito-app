# Contributing

Tokito Physical Runtime is evidence-gated because mistakes can misidentify a
physical component or guide an engineer to the wrong measurement point.

## Start with an issue

Use an existing issue on the
[Tokito project board](https://github.com/orgs/TokitoAI/projects/1). If the work
is untracked, create an issue in this repository with exactly one type label and
add it to the board before opening a PR.

## Design changes

Behavioral changes must update the Technical Bible or add an ADR. Contract
changes must document compatibility, capability negotiation, and migration.
Model changes must include a reproducible evaluation manifest and comparison
against the current accepted baseline.

## Pull requests

A complete PR:

1. links and closes one tracked issue;
2. states the user-visible/runtime effect and explicit non-goals;
3. includes tests appropriate to the change;
4. includes device evidence when changing latency, memory, thermal behavior,
   camera/audio lifecycle, model output, or inference backend;
5. updates documentation and agent memory when facts change;
6. contains no unlicensed data, model weights, raw private captures, secrets, or
   generated build products.

## Evidence rules

- Report hardware model, SoC, Android build, runtime/backend, thread policy,
  power mode, model digest, preprocessing digest, and corpus revision.
- Use board-level train/validation/test separation. Adjacent frames from the
  same board session cannot cross splits.
- Report confidence intervals or per-board distributions, not only one average.
- Record abstentions and failures; do not silently remove difficult samples.
- Do not claim a metric from synthetic or training data as held-out evidence.

## Commit style

Use imperative subjects and reference the issue, for example:

```text
feat(vision): add calibrated ROI registration (#42)
docs: define observation provenance contract (#17)
```
