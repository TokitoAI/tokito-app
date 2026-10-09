# Mobile dependency audit policy

`npm run audit` rejects unresolved high and critical advisories. The former
time-limited image-size exceptions have been removed. Do not use forced Expo
downgrades or blanket advisory exemptions to make the gate pass.

Two upstream packages currently need local source repairs:

- `braces` 3.0.3: bound recursive parse/compile/expand/stringify depth, including
  direct AST input (GHSA-vfj7-8cjw-p6xm).
- `node-forge` 1.4.0: reject extra DigestAlgorithm fields during RSA PKCS#1 v1.5
  signature verification (GHSA-86w9-cpqp-85rv).

`patch-package` applies the tracked patches during `npm ci`. Postinstall runs
regressions for deeply nested and cyclic inputs and malformed RSA signatures.
The audit gate verifies exact installed versions and SHA-256 source hashes
before recognizing either repair. A nested unverified installation fails the
gate. The raw npm report still counts these published versions as vulnerable;
the gate reports repairs separately and does not rewrite that report.

When upstream fixes are available, replace the dependency, remove the patch and
attestation together, and retain the behavioral regressions where practical.
