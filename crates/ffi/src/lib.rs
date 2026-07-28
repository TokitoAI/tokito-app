//! UniFFI surface + blocking facade over [`tokito_companion_core`].
//!
//! React Native loads this as a `staticlib`/`cdylib` through UniFFI bindings.
//! Auth methods are real HTTP calls — nothing is stubbed.

#![deny(unsafe_op_in_unsafe_fn)]

use std::sync::{Arc, OnceLock};

use tokito_companion_core::{
    me as core_me, oauth_exchange as core_oauth_exchange, otp_request as core_otp_request,
    otp_verify as core_otp_verify, CloudError,
};

uniffi::setup_scaffolding!();

static RUNTIME: OnceLock<tokio::runtime::Runtime> = OnceLock::new();

fn runtime() -> &'static tokio::runtime::Runtime {
    RUNTIME.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build()
            .expect("tokio runtime")
    })
}

/// Authenticated Tokito Cloud session (30-day JWT).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Session {
    pub token: String,
    pub email: String,
    pub plan: String,
}

/// Account fields from `GET /v1/auth/me`.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Account {
    pub email: String,
    pub plan: String,
}

/// Auth failures mapped for UniFFI / RN UI copy.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error, uniffi::Error)]
pub enum CompanionError {
    #[error("wait a moment before requesting another code")]
    Cooldown,
    #[error("too many code requests; try again later")]
    RateLimited,
    #[error("could not send the code; try again")]
    SendFailed,
    #[error("that code is invalid or expired")]
    InvalidCode,
    #[error("session expired")]
    Unauthorized,
    #[error("this sign-in method is not available right now")]
    Unavailable,
    #[error("network error: {msg}")]
    Network { msg: String },
    #[error("unexpected server response")]
    Unexpected,
}

impl From<CloudError> for CompanionError {
    fn from(value: CloudError) -> Self {
        match value {
            CloudError::Cooldown => Self::Cooldown,
            CloudError::RateLimited => Self::RateLimited,
            CloudError::SendFailed => Self::SendFailed,
            CloudError::InvalidCode => Self::InvalidCode,
            CloudError::Unauthorized => Self::Unauthorized,
            CloudError::Unavailable => Self::Unavailable,
            CloudError::Network(msg) => Self::Network { msg },
            CloudError::Unexpected => Self::Unexpected,
        }
    }
}

/// Client bound to a Tokito Cloud base URL (empty = production default).
#[derive(Debug, uniffi::Object)]
pub struct CompanionClient {
    base_url: String,
}

#[uniffi::export]
impl CompanionClient {
    #[uniffi::constructor]
    pub fn new(base_url: String) -> Arc<Self> {
        Arc::new(Self { base_url })
    }

    pub fn base_url(&self) -> String {
        self.base_url.clone()
    }

    /// `POST /v1/auth/otp/request`
    pub fn otp_request(&self, email: String) -> Result<(), CompanionError> {
        runtime()
            .block_on(core_otp_request(&self.base_url, &email))
            .map_err(CompanionError::from)
    }

    /// `POST /v1/auth/otp/verify`
    pub fn otp_verify(&self, email: String, code: String) -> Result<Session, CompanionError> {
        let session = runtime()
            .block_on(core_otp_verify(&self.base_url, &email, &code))
            .map_err(CompanionError::from)?;
        Ok(Session {
            token: session.token,
            email: session.email,
            plan: session.plan,
        })
    }

    /// `POST /v1/auth/oauth/exchange`
    pub fn oauth_exchange(&self, code: String) -> Result<Session, CompanionError> {
        let session = runtime()
            .block_on(core_oauth_exchange(&self.base_url, &code))
            .map_err(CompanionError::from)?;
        Ok(Session {
            token: session.token,
            email: session.email,
            plan: session.plan,
        })
    }

    /// `GET /v1/auth/me`
    pub fn me(&self, token: String) -> Result<Account, CompanionError> {
        let account = runtime()
            .block_on(core_me(&self.base_url, &token))
            .map_err(CompanionError::from)?;
        Ok(Account {
            email: account.email,
            plan: account.plan,
        })
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
        let session = tokio::task::spawn_blocking(move || {
            client.otp_verify("a@b.dev".into(), "123456".into())
        })
        .await
        .unwrap()
        .unwrap();
        assert_eq!(session.token, "jwt");
        assert_eq!(session.email, "a@b.dev");
    }

    #[test]
    fn cloud_error_maps_network() {
        let err: CompanionError = CloudError::Network("boom".into()).into();
        assert_eq!(err, CompanionError::Network { msg: "boom".into() });
    }
}
