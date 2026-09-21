# Auth wire contract

Source of truth: `tokito-api/docs/API.md` §Auth. Companion fixtures under
`crates/core/tests/fixtures/` are recorded response bodies from that contract.

| Call | Success | Notable errors |
|---|---|---|
| `POST /v1/auth/otp/request` | `200 {"status":"ok"}` | `429 cooldown` / `rate_limited`, `502 send_failed`, `503 auth_unavailable` |
| `POST /v1/auth/otp/verify` | `200 {token,email,plan}` | `401 invalid_code`, `503` |
| `POST /v1/auth/oauth/exchange` | same session body | `401`, `503` |
| `GET /v1/auth/me` | `200 {email,plan}` | `401 unauthorized`, `503` |

JWT: 30-day HS256; claims include email + plan. Plan for entitlement checks
must be re-read from the account store on the server (desktop already does
this for metering); companion should refresh via `/v1/auth/me` or billing
status when showing plan chrome.

**How to apply:** map status codes in `CloudError` exactly as desktop
`tokito::services::cloud`. Prefer wiremock + fixtures over live network in CI.
