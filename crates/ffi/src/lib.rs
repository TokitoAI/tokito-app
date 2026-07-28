//! Blocking facade over [`tokito_companion_core`] for UniFFI / React Native.
//!
//! M1 adds UniFFI UDL + generated bindings. This crate already exposes the
//! sync methods the bindings will wrap — no stub auth methods.

#![forbid(unsafe_code)]

use std::sync::OnceLock;

use tokito_companion_core::{
    me as core_me, oauth_exchange as core_oauth_exchange, otp_request as core_otp_request,
    otp_verify as core_otp_verify, CloudAccount, CloudError, CloudSession,
};

static RUNTIME: OnceLock<tokio::runtime::Runtime> = OnceLock::new();

fn runtime() -> &'static tokio::runtime::Runtime {
    RUNTIME.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build()
            .expect("tokio runtime")
    })
}

/// Client bound to a Tokito Cloud base URL (empty = production default).
#[derive(Debug, Clone)]
pub struct CompanionClient {
    base_url: String,
}

impl CompanionClient {
    pub fn new(base_url: impl Into<String>) -> Self {
        Self {
            base_url: base_url.into(),
        }
    }

    pub fn base_url(&self) -> &str {
        &self.base_url
    }

    /// `POST /v1/auth/otp/request`
    pub fn otp_request(&self, email: &str) -> Result<(), CloudError> {
        runtime().block_on(core_otp_request(&self.base_url, email))
    }

    /// `POST /v1/auth/otp/verify`
    pub fn otp_verify(&self, email: &str, code: &str) -> Result<CloudSession, CloudError> {
        runtime().block_on(core_otp_verify(&self.base_url, email, code))
    }

    /// `POST /v1/auth/oauth/exchange`
    pub fn oauth_exchange(&self, code: &str) -> Result<CloudSession, CloudError> {
        runtime().block_on(core_oauth_exchange(&self.base_url, code))
    }

    /// `GET /v1/auth/me`
    pub fn me(&self, token: &str) -> Result<CloudAccount, CloudError> {
        runtime().block_on(core_me(&self.base_url, token))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use wiremock::matchers::{method, path};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    #[tokio::test(flavor = "multi_thread")]
    async fn blocking_otp_verify_round_trip() {
        let server = MockServer::start().await;
        Mock::given(method("POST"))
            .and(path("/v1/auth/otp/verify"))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "token": "jwt",
                "email": "a@b.dev",
                "plan": "free"
            })))
            .mount(&server)
            .await;

        let client = CompanionClient::new(server.uri());
        let session = tokio::task::spawn_blocking(move || client.otp_verify("a@b.dev", "123456"))
            .await
            .unwrap()
            .unwrap();
        assert_eq!(session.token, "jwt");
        assert_eq!(session.email, "a@b.dev");
    }
}
