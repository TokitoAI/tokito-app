//! Bounded, credential-free transport evidence. Never record URLs or bodies.
use crate::CloudError;
use serde::Serialize;
use std::{
    collections::VecDeque,
    sync::{Mutex, OnceLock},
};
#[derive(Serialize)]
struct Diagnostic {
    operation: &'static str,
    status: u16,
    classification: &'static str,
}
static EVENTS: OnceLock<Mutex<VecDeque<Diagnostic>>> = OnceLock::new();
pub(crate) fn record(operation: &'static str, status: u16, classification: &'static str) {
    if let Ok(mut events) = EVENTS.get_or_init(|| Mutex::new(VecDeque::new())).lock() {
        if events.len() == 32 {
            events.pop_front();
        }
        events.push_back(Diagnostic {
            operation,
            status,
            classification,
        });
    }
}
pub fn drain() -> String {
    let Ok(mut events) = EVENTS.get_or_init(|| Mutex::new(VecDeque::new())).lock() else {
        return "[]".into();
    };
    serde_json::to_string(&events.drain(..).collect::<Vec<_>>()).unwrap_or_else(|_| "[]".into())
}
pub(crate) async fn decode<T: serde::de::DeserializeOwned>(
    response: reqwest::Response,
    operation: &'static str,
) -> Result<T, CloudError> {
    let status = response.status().as_u16();
    response.json().await.map_err(|error| {
        if error.is_decode() {
            record(operation, status, "malformed_success_body");
            CloudError::Unexpected
        } else {
            record(operation, status, "interrupted_success_body");
            CloudError::Network("Cloud response interrupted".into())
        }
    })
}
pub(crate) fn transient(status: u16) -> Option<CloudError> {
    match status {
        429 => Some(CloudError::RateLimited),
        500..=599 => Some(CloudError::Unavailable),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use wiremock::{
        matchers::{method, path},
        Mock, MockServer, ResponseTemplate,
    };
    #[tokio::test]
    async fn transient_responses_are_not_malformed_protocol_and_do_not_expose_body() {
        let server = MockServer::start().await;
        for status in [429, 500, 502, 503, 504] {
            server.reset().await;
            Mock::given(method("GET"))
                .and(path("/v1/auth/me"))
                .respond_with(
                    ResponseTemplate::new(status).set_body_string("PRIVATE CREDENTIAL CONTENT"),
                )
                .mount(&server)
                .await;
            let expected = transient(status).unwrap();
            assert_eq!(
                crate::me(&server.uri(), "secret-bearer").await.unwrap_err(),
                expected
            );
            Mock::given(method("GET"))
                .and(path("/v1/companion/bootstrap"))
                .respond_with(ResponseTemplate::new(status))
                .mount(&server)
                .await;
            assert_eq!(
                crate::bootstrap_realtime(&server.uri(), "secret-bearer", None)
                    .await
                    .unwrap_err(),
                transient(status).unwrap()
            );
            Mock::given(method("GET"))
                .and(path("/v1/companion/projects/p"))
                .respond_with(ResponseTemplate::new(status))
                .mount(&server)
                .await;
            assert_eq!(
                crate::get_project(&server.uri(), "secret-bearer", "p")
                    .await
                    .unwrap_err(),
                transient(status).unwrap()
            );
        }
        server.reset().await;
        Mock::given(method("GET"))
            .and(path("/v1/auth/me"))
            .respond_with(ResponseTemplate::new(200).set_body_string("PRIVATE CREDENTIAL CONTENT"))
            .mount(&server)
            .await;
        assert_eq!(
            crate::me(&server.uri(), "secret-bearer").await.unwrap_err(),
            CloudError::Unexpected
        );
        let diagnostics = drain();
        assert!(!diagnostics.contains("PRIVATE"));
        assert!(!diagnostics.contains("secret-bearer"));
        assert!(!diagnostics.contains(&server.uri()));
        let events: Vec<serde_json::Value> = serde_json::from_str(&diagnostics).unwrap();
        assert!(events.len() <= 32);
        assert!(events.iter().any(|e| e["operation"] == "auth_me"
            && e["status"] == 200
            && e["classification"] == "malformed_success_body"));
    }
}
