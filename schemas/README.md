# Schemas

This directory contains JSON Schema contracts for Group 3 evidence. The schemas
are deliberately platform-neutral so Android, Python tooling, and release jobs
can generate the same records without hardcoded device assumptions.

Valid examples live in `examples/manifests/`. CI validates schema JSON,
examples, and repository artifact hygiene with `tools/validate_governance.py`.

`reference.source-package-manifest.schema.json` records official board source
artifacts before they become validation or training inputs. It keeps downloaded
internet material separate from physical capture evidence.

Corpus manifests are additionally governed by `tools/corpus_governance.py`.
See `docs/CORPUS_GOVERNANCE.md` for canonical hashing, cross-manifest binding,
leakage auditing, asset verification, and correction/removal propagation.
Example corpus digests are zero-valued draft sentinels, not frozen evidence.

`board.reference-manifest.schema.json` governs provenance-bound board landmarks,
connector pin maps, and an optional entity/fact knowledge graph with page-level
evidence locators. Its checked-in FRDM instance is validated semantically and
digest-bound by `tools/board_reference.py`; guide-image targets and knowledge
facts cannot be promoted to authored placement coordinates.
