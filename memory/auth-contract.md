# Auth wire contract

Source of truth: `tokito-api/docs/API.md` §Auth. Companion fixtures under
`crates/core/tests/fixtures/` are recorded response bodies from that contract.

| Call | Success | Notable errors |
|---|---|---|
| `POST /v1/auth/otp/request` | `200 {"status":"ok"}` | `429 cooldown` / `rate_limited` (both carry `retry_after_secs`), daily-cap 429 (`reason: too_many_failed_attempts`), `502 send_failed`, `503 auth_unavailable` |
| `POST /v1/auth/otp/verify` | `200 {token,email,plan}` | `401 invalid_code` (`reason: too_many_attempts` = burned code), `429 rate_limited` (per-IP) or daily-cap 429, `503` |
| `POST /v1/auth/oauth/exchange` | same session body | `401`, `503` |
| `GET /v1/auth/me` | `200 {email,plan}` | `401 unauthorized`, `503` |

JWT: 30-day HS256; claims include email + plan. Plan for entitlement checks
must be re-read from the account store on the server (the Cloud contract does
this for metering); Companion should refresh via `/v1/auth/me` or billing
status when showing plan chrome.

**How to apply:** map status codes in `CloudError` from the public API contract.
Prefer wiremock + fixtures over live network in CI.

Codes are 8 digits (server also accepts 6 for one release) and the server
normalizes pasted spaces/hyphens, so the client only trims. 429 delays come
from body `retry_after_secs`, falling back to `Retry-After`. 403 is
`Forbidden` (not an org member), distinct from 401 `Unauthorized` (sign in
again). `plan` is an open string: never narrow it to a union. On device the
UniFFI Kotlin message carries the delay as `retryAfterSecs=N`;
`native/nativeErrors.ts` parses it (exact-device check deferred).
