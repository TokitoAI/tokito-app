//! Optional tenant-authenticated DS-ViRe drafts. This client never sends media.

use futures_util::StreamExt;
use serde_json::Value;
use std::time::Duration;

use crate::CloudError;

const MAX_RESULT_BYTES: usize = 4 * 1024 * 1024;

pub fn validate_endpoint(base: &str) -> Result<reqwest::Url, CloudError> {
    let mut url = reqwest::Url::parse(base).map_err(|_| CloudError::Unexpected)?;
    if url.scheme() != "https"
        || url.host_str().is_none()
        || !url.username().is_empty()
        || url.password().is_some()
        || url.query().is_some()
        || url.fragment().is_some()
        || url.path() != "/"
    {
        return Err(CloudError::Unexpected);
    }
    url.set_path("/v1/platform/board-symbols");
    Ok(url)
}

pub async fn generate(
    base: &str,
    token: &str,
    context_revision: &str,
) -> Result<String, CloudError> {
    if token.len() < 32
        || token.len() > 512
        || context_revision.is_empty()
        || context_revision.len() > 512
    {
        return Err(CloudError::Unexpected);
    }
    let mut url = validate_endpoint(base)?;
    url.query_pairs_mut()
        .append_pair("context_revision", context_revision);
    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(90))
        .redirect(reqwest::redirect::Policy::none())
        .build()
        .map_err(|_| CloudError::Unexpected)?;
    let response = client
        .post(url)
        .bearer_auth(token)
        .send()
        .await
        .map_err(|_| CloudError::Network("Symbol service could not be reached".into()))?;
    match response.status().as_u16() {
        200 => {}
        401 | 403 => return Err(CloudError::Unauthorized),
        409 | 404 | 503 => return Err(CloudError::Unavailable),
        _ => return Err(CloudError::Unexpected),
    }
    let mut stream = response.bytes_stream();
    let mut bytes = Vec::new();
    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(|_| CloudError::Network("Symbol response interrupted".into()))?;
        if bytes.len().saturating_add(chunk.len()) > MAX_RESULT_BYTES {
            return Err(CloudError::Unexpected);
        }
        bytes.extend_from_slice(&chunk);
    }
    let value: Value = serde_json::from_slice(&bytes).map_err(|_| CloudError::Unexpected)?;
    validate_result(&value, context_revision)?;
    String::from_utf8(bytes).map_err(|_| CloudError::Unexpected)
}

pub fn validate_result(value: &Value, context: &str) -> Result<(), CloudError> {
    if value["schema_version"] != "dsvire.board-symbols.v1"
        || value["context_revision"] != context
        || value["publication_allowed"] != false
    {
        return Err(CloudError::Unexpected);
    }
    let components = value["components"]
        .as_array()
        .ok_or(CloudError::Unexpected)?;
    if components.is_empty() || components.len() > 8 {
        return Err(CloudError::Unexpected);
    }
    let mut references = std::collections::HashSet::new();
    for part in components {
        let reference = part["reference"]
            .as_str()
            .filter(|s| !s.is_empty())
            .ok_or(CloudError::Unexpected)?;
        if !references.insert(reference) {
            return Err(CloudError::Unexpected);
        }
        match part["status"].as_str() {
            Some("draft") => {
                let symbol = &part["symbol"];
                let pins = symbol["pins"].as_array().ok_or(CloudError::Unexpected)?;
                let citations = symbol["citations"]
                    .as_array()
                    .ok_or(CloudError::Unexpected)?;
                if symbol["schema_version"] != "dsvire.symbol-result.v1"
                    || symbol["identity"] != part["identity"]
                    || pins.len() < 4
                    || pins.len() > 2048
                    || citations.is_empty()
                {
                    return Err(CloudError::Unexpected);
                }
            }
            Some("existing" | "abstained" | "unavailable") => {}
            _ => return Err(CloudError::Unexpected),
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn credentials_and_redirect_targets_cannot_enter_the_endpoint() {
        for endpoint in [
            "http://example.com",
            "https://key@example.com",
            "https://example.com?x=1",
            "https://example.com/private",
            "https://example.com#secret",
        ] {
            assert!(validate_endpoint(endpoint).is_err());
        }
        assert_eq!(
            validate_endpoint("https://dsvire.tokito.dev")
                .unwrap()
                .path(),
            "/v1/platform/board-symbols"
        );
    }

    #[test]
    fn stale_context_and_publication_claims_are_rejected() {
        let mut result = json!({"schema_version":"dsvire.board-symbols.v1", "context_revision":"board@1", "publication_allowed":false, "components":[{"reference":"U1","status":"abstained"}]});
        assert!(validate_result(&result, "board@1").is_ok());
        assert!(validate_result(&result, "board@2").is_err());
        result["publication_allowed"] = json!(true);
        assert!(validate_result(&result, "board@1").is_err());
    }
}
