//! Tokito Cloud auth client (`/v1/auth/*`).
//!
//! Wire contract: `tokito-api/docs/API.md` §Auth.
//! `tokito::services::cloud` so both clients stay interchangeable against
//! the same gateway.

use std::time::Duration;

use serde::Deserialize;

use crate::base_url::resolve_base_url;
use crate::error::CloudError;

/// An authenticated Tokito Cloud session. `token` is a 30-day HS256 JWT.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CloudSession {
    pub token: String,
    pub email: String,
    pub plan: String,
}

/// Account fields returned by `GET /v1/auth/me`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CloudAccount {
    pub email: String,
    pub plan: String,
}

#[derive(Deserialize)]
struct SessionBody {
    token: String,
    email: String,
    #[serde(default = "default_plan")]
    plan: String,
}

#[derive(Deserialize)]
struct MeBody {
    email: String,
    #[serde(default = "default_plan")]
    plan: String,
}

#[derive(Deserialize, Default)]
struct ErrorBody {
    #[serde(default)]
    status: String,
    #[serde(default)]
    reason: Option<String>,
    #[serde(default)]
    retry_after_secs: Option<u64>,
}

/// Which route failed: a verify 429 (per-IP) and a request 429 (hourly) differ.
#[derive(Clone, Copy, PartialEq, Eq)]
enum Route {
    OtpRequest,
    OtpVerify,
}

/// Body `retry_after_secs`, falling back to a delta-seconds `Retry-After`.
fn retry_after(headers: &reqwest::header::HeaderMap, body: &ErrorBody) -> Option<u64> {
    body.retry_after_secs.or_else(|| {
        headers
            .get(reqwest::header::RETRY_AFTER)?
            .to_str()
            .ok()?
            .trim()
            .parse()
            .ok()
    })
}

fn default_plan() -> String {
    "free".into()
}

fn client() -> Result<reqwest::Client, CloudError> {
    reqwest::Client::builder()
        .timeout(Duration::from_secs(20))
        .build()
        .map_err(|e| CloudError::Network(e.to_string()))
}

/// `POST /v1/auth/otp/request` — ask the gateway to email a sign-in code.
pub async fn otp_request(base: &str, email: &str) -> Result<(), CloudError> {
    let base = resolve_base_url(base);
    let url = format!("{base}/v1/auth/otp/request");
    let resp = client()?
        .post(url)
        .json(&serde_json::json!({ "email": email.trim() }))
        .send()
        .await
        .map_err(|e| CloudError::Network(e.to_string()))?;
    match resp.status().as_u16() {
        200 => Ok(()),
        429 => Err(classify_429(resp, Route::OtpRequest).await),
        502 => Err(CloudError::SendFailed),
        503 => Err(CloudError::Unavailable),
        _ => Err(CloudError::Unexpected),
    }
}

/// `POST /v1/auth/otp/verify` — swap `(email, code)` for a session.
pub async fn otp_verify(base: &str, email: &str, code: &str) -> Result<CloudSession, CloudError> {
    let base = resolve_base_url(base);
    let url = format!("{base}/v1/auth/otp/verify");
    let resp = client()?
        .post(url)
        .json(&serde_json::json!({ "email": email.trim(), "code": code.trim() }))
        .send()
        .await
        .map_err(|e| CloudError::Network(e.to_string()))?;
    session_from_response(resp, Route::OtpVerify).await
}

/// `POST /v1/auth/oauth/exchange` — swap an OAuth pairing code for a session.
pub async fn oauth_exchange(base: &str, code: &str) -> Result<CloudSession, CloudError> {
    let base = resolve_base_url(base);
    let url = format!("{base}/v1/auth/oauth/exchange");
    let resp = client()?
        .post(url)
        .json(&serde_json::json!({ "code": code.trim() }))
        .send()
        .await
        .map_err(|e| CloudError::Network(e.to_string()))?;
    session_from_response(resp, Route::OtpVerify).await
}

/// `GET /v1/auth/me` — probe the account behind a bearer token.
pub async fn me(base: &str, token: &str) -> Result<CloudAccount, CloudError> {
    let base = resolve_base_url(base);
    let url = format!("{base}/v1/auth/me");
    let resp = client()?
        .get(url)
        .bearer_auth(token)
        .send()
        .await
        .map_err(|e| CloudError::Network(e.to_string()))?;
    match resp.status().as_u16() {
        200 => {
            let body: MeBody = resp.json().await.map_err(|_| CloudError::Unexpected)?;
            Ok(CloudAccount {
                email: body.email,
                plan: body.plan,
            })
        }
        401 => Err(CloudError::Unauthorized),
        403 => Err(CloudError::Forbidden),
        503 => Err(CloudError::Unavailable),
        _ => Err(CloudError::Unexpected),
    }
}

async fn session_from_response(
    resp: reqwest::Response,
    route: Route,
) -> Result<CloudSession, CloudError> {
    match resp.status().as_u16() {
        200 => {
            let body: SessionBody = resp.json().await.map_err(|_| CloudError::Unexpected)?;
            Ok(CloudSession {
                token: body.token,
                email: body.email,
                plan: body.plan,
            })
        }
        401 => {
            let body: ErrorBody = resp.json().await.unwrap_or_default();
            if body.reason.as_deref() == Some("too_many_attempts") {
                Err(CloudError::CodeBurned)
            } else {
                Err(CloudError::InvalidCode)
            }
        }
        403 => Err(CloudError::Forbidden),
        429 => Err(classify_429(resp, route).await),
        503 => Err(CloudError::Unavailable),
        _ => Err(CloudError::Unexpected),
    }
}

async fn classify_429(resp: reqwest::Response, route: Route) -> CloudError {
    let headers = resp.headers().clone();
    let body: ErrorBody = resp.json().await.unwrap_or_default();
    let retry_after_secs = retry_after(&headers, &body);
    if body.reason.as_deref() == Some("too_many_failed_attempts") {
        CloudError::DailyCapReached { retry_after_secs }
    } else if body.status == "cooldown" {
        CloudError::Cooldown { retry_after_secs }
    } else if route == Route::OtpVerify {
        CloudError::NetworkRateLimited { retry_after_secs }
    } else {
        CloudError::RateLimited { retry_after_secs }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use wiremock::matchers::{body_partial_json, header, method, path};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    fn fixture(name: &str) -> String {
        let path = format!("{}/tests/fixtures/{name}", env!("CARGO_MANIFEST_DIR"));
        std::fs::read_to_string(&path).unwrap_or_else(|e| panic!("read {path}: {e}"))
    }

    #[tokio::test]
    async fn otp_request_ok() {
        let server = MockServer::start().await;
        Mock::given(method("POST"))
            .and(path("/v1/auth/otp/request"))
            .and(body_partial_json(serde_json::json!({"email": "u@lab.dev"})))
            .respond_with(
                ResponseTemplate::new(200).set_body_string(fixture("otp_request_ok.json")),
            )
            .mount(&server)
            .await;

        otp_request(&server.uri(), "  u@lab.dev  ").await.unwrap();
    }

    #[tokio::test]
    async fn otp_request_cooldown() {
        let server = MockServer::start().await;
        Mock::given(method("POST"))
            .and(path("/v1/auth/otp/request"))
            .respond_with(
                ResponseTemplate::new(429).set_body_string(fixture("otp_request_cooldown.json")),
            )
            .mount(&server)
            .await;

        assert_eq!(
            otp_request(&server.uri(), "u@lab.dev").await.unwrap_err(),
            CloudError::Cooldown {
                retry_after_secs: Some(60)
            }
        );
    }

    #[tokio::test]
    async fn otp_request_rate_limited() {
        let server = MockServer::start().await;
        Mock::given(method("POST"))
            .and(path("/v1/auth/otp/request"))
            .respond_with(
                ResponseTemplate::new(429)
                    .set_body_string(fixture("otp_request_rate_limited.json")),
            )
            .mount(&server)
            .await;

        assert_eq!(
            otp_request(&server.uri(), "u@lab.dev").await.unwrap_err(),
            CloudError::RateLimited {
                retry_after_secs: Some(3600)
            }
        );
    }

    #[tokio::test]
    async fn otp_verify_ok() {
        let server = MockServer::start().await;
        Mock::given(method("POST"))
            .and(path("/v1/auth/otp/verify"))
            .respond_with(ResponseTemplate::new(200).set_body_string(fixture("session_ok.json")))
            .mount(&server)
            .await;

        let session = otp_verify(&server.uri(), "u@lab.dev", "123456")
            .await
            .unwrap();
        assert_eq!(session.token, "jwt-test-token");
        assert_eq!(session.email, "u@lab.dev");
        assert_eq!(session.plan, "free");
    }

    #[tokio::test]
    async fn otp_verify_invalid_code() {
        let server = MockServer::start().await;
        Mock::given(method("POST"))
            .and(path("/v1/auth/otp/verify"))
            .respond_with(
                ResponseTemplate::new(401).set_body_string(fixture("otp_verify_invalid.json")),
            )
            .mount(&server)
            .await;

        assert_eq!(
            otp_verify(&server.uri(), "u@lab.dev", "000000")
                .await
                .unwrap_err(),
            CloudError::InvalidCode
        );
    }

    #[tokio::test]
    async fn oauth_exchange_ok() {
        let server = MockServer::start().await;
        Mock::given(method("POST"))
            .and(path("/v1/auth/oauth/exchange"))
            .respond_with(ResponseTemplate::new(200).set_body_string(fixture("session_ok.json")))
            .mount(&server)
            .await;

        let session = oauth_exchange(&server.uri(), "ABCD-1234").await.unwrap();
        assert_eq!(session.email, "u@lab.dev");
    }

    #[tokio::test]
    async fn me_ok() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/v1/auth/me"))
            .and(header("authorization", "Bearer jwt-test-token"))
            .respond_with(ResponseTemplate::new(200).set_body_string(fixture("me_ok.json")))
            .mount(&server)
            .await;

        let account = me(&server.uri(), "jwt-test-token").await.unwrap();
        assert_eq!(account.email, "u@lab.dev");
        assert_eq!(account.plan, "pro");
    }

    #[tokio::test]
    async fn me_unauthorized() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/v1/auth/me"))
            .respond_with(
                ResponseTemplate::new(401).set_body_string(fixture("me_unauthorized.json")),
            )
            .mount(&server)
            .await;

        assert_eq!(
            me(&server.uri(), "bad").await.unwrap_err(),
            CloudError::Unauthorized
        );
    }

    #[tokio::test]
    async fn me_unavailable() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/v1/auth/me"))
            .respond_with(
                ResponseTemplate::new(503).set_body_string(fixture("auth_unavailable.json")),
            )
            .mount(&server)
            .await;

        assert_eq!(
            me(&server.uri(), "jwt").await.unwrap_err(),
            CloudError::Unavailable
        );
    }

    async fn verify_with(resp: ResponseTemplate) -> CloudError {
        let server = MockServer::start().await;
        Mock::given(method("POST"))
            .and(path("/v1/auth/otp/verify"))
            .respond_with(resp)
            .mount(&server)
            .await;
        otp_verify(&server.uri(), "u@lab.dev", "1234 5678")
            .await
            .unwrap_err()
    }

    #[tokio::test]
    async fn otp_verify_burned_code() {
        let resp = ResponseTemplate::new(401)
            .set_body_string(r#"{"status":"invalid_code","reason":"too_many_attempts"}"#);
        assert_eq!(verify_with(resp).await, CloudError::CodeBurned);
    }

    #[tokio::test]
    async fn otp_verify_per_ip_429_uses_body_then_header() {
        let resp = ResponseTemplate::new(429)
            .set_body_string(r#"{"status":"rate_limited","retry_after_secs":90}"#);
        assert_eq!(
            verify_with(resp).await,
            CloudError::NetworkRateLimited {
                retry_after_secs: Some(90)
            }
        );
        let resp = ResponseTemplate::new(429)
            .insert_header("Retry-After", "42")
            .set_body_string(r#"{"status":"rate_limited"}"#);
        assert_eq!(
            verify_with(resp).await,
            CloudError::NetworkRateLimited {
                retry_after_secs: Some(42)
            }
        );
    }

    #[tokio::test]
    async fn otp_verify_daily_cap() {
        let resp = ResponseTemplate::new(429).set_body_string(
            r#"{"status":"rate_limited","reason":"too_many_failed_attempts","retry_after_secs":7200}"#,
        );
        assert_eq!(
            verify_with(resp).await,
            CloudError::DailyCapReached {
                retry_after_secs: Some(7200)
            }
        );
    }

    #[tokio::test]
    async fn me_403_is_forbidden_not_unauthorized() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/v1/auth/me"))
            .respond_with(ResponseTemplate::new(403))
            .mount(&server)
            .await;
        assert_eq!(
            me(&server.uri(), "tok").await.unwrap_err(),
            CloudError::Forbidden
        );
    }

    #[tokio::test]
    async fn unknown_plan_decodes_verbatim() {
        let server = MockServer::start().await;
        Mock::given(method("POST"))
            .and(path("/v1/auth/otp/verify"))
            .respond_with(ResponseTemplate::new(200).set_body_string(
                r#"{"token":"t","email":"u@lab.dev","plan":"team_enterprise_2027"}"#,
            ))
            .mount(&server)
            .await;
        let s = otp_verify(&server.uri(), "u@lab.dev", "12345678")
            .await
            .unwrap();
        assert_eq!(s.plan, "team_enterprise_2027");
    }
}
