# Corpus governance

## Boundary

`tools/corpus_governance.py` governs corpus metadata before training, model
selection, calibration, or release evaluation. It does not download datasets,
commit raw media, label data automatically, or turn synthetic fixtures into
release evidence. Raw media stays in approved external or local storage.

The implementation uses only the Python standard library so the same checks run
locally and on the restricted Tokito VPS runner without an unpinned package
environment.

## Manifest set

A frozen corpus revision consists of four cross-bound records:

| Manifest | Responsibility |
|---|---|
| `corpus.dataset-card.json` | Origin, license review, consent basis, intended uses, restrictions, annotation contract, retention, exclusions, and revision history |
| `corpus.inventory-manifest.json` | Stable item/board/session IDs, physical-board leakage group, source, media digest, capture conditions, fingerprints, lineage, and inclusion state |
| `corpus.annotation-manifest.json` | Reviewed identity, geometry, state, uncertainty, ambiguity, and unobservable-region records |
| `corpus.split-manifest.json` | Item membership, the deterministic leakage audit, frozen timestamp, and inventory/annotation digests |

The dataset card binds the other three manifest digests. Annotation and split
manifests bind the inventory digest, and the split manifest also binds the
annotation digest. An evaluation manifest must bind all four digests, not only
human-readable revision names.

Files under `examples/manifests/` are schema-valid drafts with zero digest
sentinels. They are templates and are not accepted as frozen evidence.

## Freeze a revision

Prepare schema-valid draft JSON and run:

```text
python tools/corpus_governance.py freeze \
  --dataset-card path/to/dataset-card.json \
  --inventory path/to/inventory.json \
  --annotations path/to/annotations.json \
  --splits path/to/splits.json \
  --frozen-at 2026-08-29T00:00:00Z \
  --output-dir path/to/frozen-r1
```

The timestamp is caller-supplied so identical input and timestamp produce
identical output. The command refuses to freeze when any active item is missing
from a split, an excluded item is included, a source license is not approved,
an annotation violates its declared contract, or leakage is found. It also
refuses to overwrite a non-empty output directory.

Validate a frozen revision and, optionally, verify every local media byte:

```text
python tools/corpus_governance.py validate \
  --dataset-card path/to/frozen-r1/corpus.dataset-card.json \
  --inventory path/to/frozen-r1/corpus.inventory-manifest.json \
  --annotations path/to/frozen-r1/corpus.annotation-manifest.json \
  --splits path/to/frozen-r1/corpus.split-manifest.json \
  --asset-root path/to/untracked/media \
  --evaluation path/to/evaluation.run-manifest.json
```

`--asset-root` rejects missing files, path traversal, byte-size differences,
and SHA-256 differences. Remote URIs cannot be byte-verified with this option.

## Leakage audit

The auditor treats each physical board as exclusive to one split. It also
requires each board/session pair, declared visual leakage group, and complete
parent/derived lineage to remain in one split. This is deliberately stronger
than frame-level random splitting.

Two content checks run in addition to grouping, including an explicit
near-duplicate audit:

- exact SHA-256 collisions may not cross splits;
- same-algorithm, same-width hexadecimal fingerprints are searched with an
  exact Hamming-distance BK-tree and may not cross splits within the recorded
  threshold.

All exact and near-duplicate groups are recorded in the audit. Duplicates
within one split remain visible for curation, while cross-split duplicates fail
the freeze.

Fingerprint generation is an upstream, versioned preprocessing responsibility.
The algorithm name and value live on each item; the auditor never silently
mixes different fingerprint algorithms or widths.

The standalone audit command emits machine-readable JSON and exits with status
2 for detected leakage:

```text
python tools/corpus_governance.py audit --dataset-card ... --inventory ... --annotations ... --splits ...
```

## Corrections and removals

Corrections use `corpus.change-set.schema.json`. Begin with a zero digest
sentinel and seal the change set without editing the digest manually:

```text
python tools/corpus_governance.py seal-change-set \
  --change-set path/to/change-set.draft.json \
  --output path/to/change-set.json
```

Apply it only to the exact frozen dataset digest named by the change set:

```text
python tools/corpus_governance.py apply-changes \
  --dataset-card ... --inventory ... --annotations ... --splits ... \
  --change-set path/to/change-set.json \
  --frozen-at 2026-08-30T00:00:00Z \
  --output-dir path/to/frozen-r2
```

A removal propagates through the inventory, split membership, annotations, and
all lineage descendants. Every correction explicitly invalidates annotations,
and that invalidation propagates to descendants. A correction may include
replacement annotations for its target item; those replacements pass the full
annotation contract before the revision is frozen. Every resulting manifest
receives a new digest, so prior evaluations remain immutable and cannot be
mistaken for evidence about the revised corpus. The dataset card records the
sealed change set digest and base/target revisions.

The tool changes metadata only. Storage deletion must be executed by the
approved data store within the dataset card's removal SLA and independently
audited; silently deleting arbitrary paths from a manifest is intentionally not
part of this command.

## Digest convention

Manifest digests are SHA-256 over UTF-8 canonical JSON with keys sorted, no
insignificant whitespace, non-ASCII text preserved, and non-finite numbers
rejected. The manifest's own `manifest_digest` member is omitted from the hash
input. Digests are encoded as `sha256:<lowercase hex>`.

This convention avoids self-referential hashing while ensuring every other
field, including cross-manifest digests and change history, is immutable.
