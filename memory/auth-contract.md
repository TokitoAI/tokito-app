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
must be re-read from the account store on the server (the Cloud contract does
this for metering); Companion should refresh via `/v1/auth/me` or billing
status when showing plan chrome.

**How to apply:** map status codes in `CloudError` from the public API contract.
Prefer wiremock + fixtures over live network in CI.

Offline account restore uses the Rust verified-identity store, not the email or
plan in JavaScript secure-session state. Only a successful `/v1/auth/me` response
creates the origin/token-fingerprint binding. The local retention window is at
most 24 hours and never exceeds JWT expiry; decoding expiry is not signature
verification. Raw bearer tokens are not stored in this SQLite cache.

**Why:** an outage must not turn an unverified UI account into permission to open
another account's project cache. An explicit 401 revokes the binding; malformed
responses fail closed. Sign-out clears both the binding and secure session.
Cached identity permits private local reads, not server authorization or a claim
that the update transport is live.

JavaScript authentication operations carry an in-memory generation. Sign-out,
new OTP verification, and a new restore invalidate older responses; superseded
responses cannot publish UI state, refresh the secure session, or clear it on a
late 401. Secure-store operations are serialized independently of network calls.
Sign-out clears the secure session before forgetting its old native identity,
so delayed native completion cannot clear a newer sign-in.
**Why:** authorization is temporal as well as account-scoped; a valid response
to a retired operation is not permission to replace the current session.
