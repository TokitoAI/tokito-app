# Schemas

This directory contains JSON Schema contracts for Group 3 evidence. The schemas
are deliberately platform-neutral so Android, Python tooling, and release jobs
can generate the same records without hardcoded device assumptions.

Valid examples live in `examples/manifests/`. CI validates schema JSON,
examples, and repository artifact hygiene with `tools/validate_governance.py`.

Corpus manifests are additionally governed by `tools/corpus_governance.py`.
See `docs/CORPUS_GOVERNANCE.md` for canonical hashing, cross-manifest binding,
leakage auditing, asset verification, and correction/removal propagation.
Example corpus digests are zero-valued draft sentinels, not frozen evidence.
