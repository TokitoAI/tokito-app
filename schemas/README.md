# Schemas

This directory contains JSON Schema contracts for Group 3 evidence. The schemas
are deliberately platform-neutral so Android, Python tooling, and release jobs
can generate the same records without hardcoded device assumptions.

Valid examples live in `examples/manifests/`. CI validates schema JSON,
examples, and repository artifact hygiene with `tools/validate_governance.py`.

`reference.source-package-manifest.schema.json` records official board source
artifacts before they become validation or training inputs. It keeps downloaded
internet material separate from physical capture evidence.
