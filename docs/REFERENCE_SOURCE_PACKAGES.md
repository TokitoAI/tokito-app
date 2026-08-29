# Reference Source Packages

Reference source packages describe internet-accessible board documents,
restricted vendor files, and visual references before they become runtime or
training inputs. They support the FRDM-MCXN236 validation path without treating
downloaded web material as physical iQOO capture evidence.

## Contract

The manifest schema is
[`schemas/reference.source-package-manifest.schema.json`](../schemas/reference.source-package-manifest.schema.json).
It records:

- board vendor, product, and physical revision status;
- retrieval policy for network downloads and ignored output paths;
- capture-evidence policy that keeps physical iQOO sessions separate;
- source URLs, artifact codes, access requirements, revisions, license status,
  and trainability status.

The FRDM-MCXN236 example lives in
[`examples/manifests/reference.source-package-manifest.example.json`](../examples/manifests/reference.source-package-manifest.example.json).
NXP-specific names, artifact codes, dates, and access notes are manifest data.
The validator and downloader do not branch on the board name.

## Physical Capture Boundary

Internet photos, product pages, user guides, schematics, design archives, and
SDK pages are reference artifacts. Public board images may be recorded as
visual references, and may be downloaded into ignored local output only when
the manifest explicitly allows fetching and the vendor URL supports direct
retrieval. They become training inputs only after license review or explicit
permission. They are not held-out physical evidence and cannot satisfy the
iQOO capture requirements in
[`FRDM_MCXN236_VALIDATION.md`](FRDM_MCXN236_VALIDATION.md).

The default output path is under `build/`, which is ignored by Git. Raw
captures, local datasets, model weights, generated reports, and downloaded
archives remain outside source control.

## Commands

Validate the manifest without network access:

```bash
python -m tools.reference_package.cli validate \
  --manifest examples/manifests/reference.source-package-manifest.example.json
```

Fetch only public sources explicitly marked `fetch_allowed`. The manifest may
record public pages as source-manifest-only when a vendor site blocks direct
scripted retrieval:

```bash
python -m tools.reference_package.cli fetch \
  --manifest examples/manifests/reference.source-package-manifest.example.json
```

Account-required artifacts such as vendor schematics and design ZIP files are
recorded but not automatically downloaded. They require a signed-in NXP account
and manual terms/license review before any training or validation use.
