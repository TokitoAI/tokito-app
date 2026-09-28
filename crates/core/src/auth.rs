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

#[derive(Deserialize)]
struct StatusBody {
    status: String,
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
        429 => Err(classify_429(resp).await),
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
    session_from_response(resp).await
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
    session_from_response(resp).await
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
        503 => Err(CloudError::Unavailable),
        _ => Err(CloudError::Unexpected),
    }
}

async fn session_from_response(resp: reqwest::Response) -> Result<CloudSession, CloudError> {
    match resp.status().as_u16() {
        200 => {
            let body: SessionBody = resp.json().await.map_err(|_| CloudError::Unexpected)?;
            Ok(CloudSession {
                token: body.token,
                email: body.email,
                plan: body.plan,
            })
        }
        401 => Err(CloudError::InvalidCode),
        503 => Err(CloudError::Unavailable),
        _ => Err(CloudError::Unexpected),
    }
}

async fn classify_429(resp: reqwest::Response) -> CloudError {
    match resp.json::<StatusBody>().await {
        Ok(body) if body.status == "rate_limited" => CloudError::RateLimited,
        _ => CloudError::Cooldown,
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
            CloudError::Cooldown
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
            CloudError::RateLimited
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
}
