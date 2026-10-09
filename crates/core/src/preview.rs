//! Fetch and verify an exact canonical schematic revision, never a legacy artifact.
use crate::{conversations::origin, CloudError};
use futures_util::StreamExt;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::time::Duration;

#[derive(Debug, Serialize, Deserialize)]
pub struct SchematicPreview {
    pub project_id: String,
    pub design_id: String,
    pub schematic_version: u64,
    pub sheet_id: String,
    pub source_sha256: String,
    pub sha256: String,
    pub svg: String,
}
pub async fn get_preview(
    base: &str,
    token: &str,
    project: &str,
    design: &str,
    version: u64,
    sheet: &str,
) -> Result<SchematicPreview, CloudError> {
    let mut url = reqwest::Url::parse(&origin(base)?).map_err(|_| CloudError::Unexpected)?;
    url.path_segments_mut()
        .map_err(|_| CloudError::Unexpected)?
        .extend(["v1", "projects", project, "designs", design, "preview"]);
    url.query_pairs_mut()
        .append_pair("schematic_version", &version.to_string())
        .append_pair("sheet_id", sheet);
    let client = reqwest::Client::builder()
        .connect_timeout(Duration::from_secs(15))
        .timeout(Duration::from_secs(45))
        .redirect(reqwest::redirect::Policy::none())
        .build()
        .map_err(|_| CloudError::Unexpected)?;
    let response = client
        .get(url)
        .bearer_auth(token)
        .send()
        .await
        .map_err(|_| CloudError::Network("Preview connection failed".into()))?;
    match response.status().as_u16() {
        200 => {}
        401 | 403 => return Err(CloudError::Unauthorized),
        404 | 409 | 422 => return Err(CloudError::Unavailable),
        429 => return Err(CloudError::RateLimited),
        500..=599 => return Err(CloudError::Network("Preview service unavailable".into())),
        _ => return Err(CloudError::Unexpected),
    }
    if response
        .content_length()
        .is_some_and(|n| n > 16 * 1024 * 1024)
    {
        return Err(CloudError::Unexpected);
    }
    let mut stream = response.bytes_stream();
    let mut body = Vec::new();
    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(|_| CloudError::Network("Preview interrupted".into()))?;
        if body.len() + chunk.len() > 16 * 1024 * 1024 {
            return Err(CloudError::Unexpected);
        }
        body.extend_from_slice(&chunk);
    }
    let preview: SchematicPreview =
        serde_json::from_slice(&body).map_err(|_| CloudError::Unexpected)?;
    if preview.project_id != project
        || preview.design_id != design
        || preview.schematic_version != version
        || preview.sheet_id != sheet
        || preview.svg.len() > 8 * 1024 * 1024
        || !preview.svg.contains("<svg")
        || preview.source_sha256.len() != 64
        || !preview.source_sha256.bytes().all(|b| b.is_ascii_hexdigit())
        || preview.sha256 != format!("{:x}", Sha256::digest(preview.svg.as_bytes()))
    {
        return Err(CloudError::Unexpected);
    }
    Ok(preview)
}
#[cfg(test)]
mod tests {
    use super::*;
    use wiremock::{
        matchers::{method, path, query_param},
        Mock, MockServer, ResponseTemplate,
    };
    #[tokio::test]
    async fn verifies_revision_scope_and_digest_before_display() {
        let server = MockServer::start().await;
        let svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"/>";
        let mut body = serde_json::json!({"project_id":"p","design_id":"d","sheet_id":"root","schematic_version":2,"source_sha256":"a".repeat(64),"sha256":format!("{:x}",Sha256::digest(svg.as_bytes())),"svg":svg});
        Mock::given(method("GET"))
            .and(path("/v1/projects/p/designs/d/preview"))
            .and(query_param("schematic_version", "2"))
            .respond_with(ResponseTemplate::new(200).set_body_json(body.clone()))
            .mount(&server)
            .await;
        assert_eq!(
            get_preview(&server.uri(), "token", "p", "d", 2, "root")
                .await
                .unwrap()
                .svg,
            svg
        );
        server.reset().await;
        body["schematic_version"] = serde_json::json!(1);
        Mock::given(method("GET"))
            .respond_with(ResponseTemplate::new(200).set_body_json(body.clone()))
            .mount(&server)
            .await;
        assert!(get_preview(&server.uri(), "token", "p", "d", 2, "root")
            .await
            .is_err());
        server.reset().await;
        body["schematic_version"] = serde_json::json!(2);
        body["svg"] = serde_json::json!("<svg>changed</svg>");
        Mock::given(method("GET"))
            .respond_with(ResponseTemplate::new(200).set_body_json(body))
            .mount(&server)
            .await;
        assert!(get_preview(&server.uri(), "token", "p", "d", 2, "root")
            .await
            .is_err());
    }
}
