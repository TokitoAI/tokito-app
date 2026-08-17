# Mobile dependency audit policy

CI evaluates `npm audit` through `apps/mobile/scripts/audit-gate.mjs`. The gate
fails on every critical advisory and every high advisory that is not explicitly
identified, package-bound, justified, and time-bounded in that script. Tests
prove that new and expired advisories fail closed.

## Temporary upstream exception

Two high-severity `image-size` denial-of-service advisories are temporarily
accepted through 2026-09-30. Metro uses this parser during local application
asset builds; the mobile runtime does not accept arbitrary files through it.
As of 2026-08-18, npm publishes `image-size` 2.0.2 as latest and no patched
release exists, while npm's proposed forced remediation downgrades Expo from SDK
57 to incompatible SDK 53.

The exception is not a blanket severity waiver: it matches the two GHSA IDs and
the `image-size` package exactly. A patched upstream release should replace the
exception immediately; otherwise CI begins failing after the expiry date.
