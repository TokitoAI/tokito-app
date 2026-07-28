//! Companion sync client (`/v1/companion/*`) against Tokito Cloud.

use std::time::Duration;

use serde::{Deserialize, Serialize};

use crate::base_url::resolve_base_url;
use crate::error::CloudError;

/// BOM line in a published project snapshot.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct BomLine {
    pub r#ref: String,
    pub value: String,
    #[serde(default)]
    pub footprint: String,
    pub qty: i64,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct DesignSummary {
    pub id: String,
    pub name: String,
    pub sheet_count: i64,
    #[serde(default)]
    pub bom_lines: Vec<BomLine>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ArtifactMeta {
    pub name: String,
    pub content_type: String,
    pub sha256: String,
    pub bytes: i64,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ProjectSnapshot {
    pub name: String,
    pub slug: String,
    pub updated_at: String,
    #[serde(default)]
    pub designs: Vec<DesignSummary>,
    #[serde(default)]
    pub artifact_manifest: Vec<ArtifactMeta>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ProjectListItem {
    pub id: String,
    pub name: String,
    pub slug: String,
    pub updated_at: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ProjectDetail {
    pub id: String,
    #[serde(flatten)]
    pub snapshot: ProjectSnapshot,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Proposal {
    pub id: String,
    pub kind: String,
    pub title: String,
    pub summary: String,
    pub created_at: String,
    #[serde(default)]
    pub payload: serde_json::Value,
    pub status: String,
    #[serde(default)]
    pub decision: Option<String>,
    #[serde(default)]
    pub note: Option<String>,
}

#[derive(Deserialize)]
struct ListBody {
    projects: Vec<ProjectListItem>,
}

#[derive(Deserialize)]
struct ProposalsBody {
    proposals: Vec<Proposal>,
}

#[derive(Deserialize)]
struct StatusBody {
    status: String,
}

fn client() -> Result<reqwest::Client, CloudError> {
    reqwest::Client::builder()
        .timeout(Duration::from_secs(30))
        .build()
        .map_err(|e| CloudError::Network(e.to_string()))
}

fn map_status(status: u16, body_status: Option<&str>) -> CloudError {
    match status {
        401 => CloudError::Unauthorized,
        503 => CloudError::Unavailable,
        _ => {
            let _ = body_status;
            CloudError::Unexpected
        }
    }
}

/// `PUT /v1/companion/projects/{project_id}`
pub async fn put_project(
    base: &str,
    token: &str,
    project_id: &str,
    snapshot: &ProjectSnapshot,
) -> Result<(), CloudError> {
    let base = resolve_base_url(base);
    let url = format!("{base}/v1/companion/projects/{project_id}");
    let resp = client()?
        .put(url)
        .bearer_auth(token)
        .json(snapshot)
        .send()
        .await
        .map_err(|e| CloudError::Network(e.to_string()))?;
    match resp.status().as_u16() {
        200 => Ok(()),
        code => {
            let body = resp.json::<StatusBody>().await.ok();
            Err(map_status(code, body.as_ref().map(|b| b.status.as_str())))
        }
    }
}

/// `GET /v1/companion/projects`
pub async fn list_projects(base: &str, token: &str) -> Result<Vec<ProjectListItem>, CloudError> {
    let base = resolve_base_url(base);
    let url = format!("{base}/v1/companion/projects");
    let resp = client()?
        .get(url)
        .bearer_auth(token)
        .send()
        .await
        .map_err(|e| CloudError::Network(e.to_string()))?;
    match resp.status().as_u16() {
        200 => {
            let body: ListBody = resp.json().await.map_err(|_| CloudError::Unexpected)?;
            Ok(body.projects)
        }
        code => {
            let body = resp.json::<StatusBody>().await.ok();
            Err(map_status(code, body.as_ref().map(|b| b.status.as_str())))
        }
    }
}

/// `GET /v1/companion/projects/{project_id}`
pub async fn get_project(
    base: &str,
    token: &str,
    project_id: &str,
) -> Result<ProjectDetail, CloudError> {
    let base = resolve_base_url(base);
    let url = format!("{base}/v1/companion/projects/{project_id}");
    let resp = client()?
        .get(url)
        .bearer_auth(token)
        .send()
        .await
        .map_err(|e| CloudError::Network(e.to_string()))?;
    match resp.status().as_u16() {
        200 => resp.json().await.map_err(|_| CloudError::Unexpected),
        code => {
            let body = resp.json::<StatusBody>().await.ok();
            Err(map_status(code, body.as_ref().map(|b| b.status.as_str())))
        }
    }
}

/// `POST .../proposals/{id}/decision`
pub async fn decide_proposal(
    base: &str,
    token: &str,
    project_id: &str,
    proposal_id: &str,
    decision: &str,
    note: Option<&str>,
) -> Result<Proposal, CloudError> {
    let base = resolve_base_url(base);
    let url = format!("{base}/v1/companion/projects/{project_id}/proposals/{proposal_id}/decision");
    let resp = client()?
        .post(url)
        .bearer_auth(token)
        .json(&serde_json::json!({ "decision": decision, "note": note }))
        .send()
        .await
        .map_err(|e| CloudError::Network(e.to_string()))?;
    match resp.status().as_u16() {
        200 => resp.json().await.map_err(|_| CloudError::Unexpected),
        code => {
            let body = resp.json::<StatusBody>().await.ok();
            Err(map_status(code, body.as_ref().map(|b| b.status.as_str())))
        }
    }
}

/// `GET .../proposals?status=`
pub async fn list_proposals(
    base: &str,
    token: &str,
    project_id: &str,
    status: Option<&str>,
) -> Result<Vec<Proposal>, CloudError> {
    let base = resolve_base_url(base);
    let mut url = format!("{base}/v1/companion/projects/{project_id}/proposals");
    if let Some(status) = status {
        url.push_str(&format!("?status={status}"));
    }
    let resp = client()?
        .get(url)
        .bearer_auth(token)
        .send()
        .await
        .map_err(|e| CloudError::Network(e.to_string()))?;
    match resp.status().as_u16() {
        200 => {
            let body: ProposalsBody = resp.json().await.map_err(|_| CloudError::Unexpected)?;
            Ok(body.proposals)
        }
        code => {
            let body = resp.json::<StatusBody>().await.ok();
            Err(map_status(code, body.as_ref().map(|b| b.status.as_str())))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use wiremock::matchers::{method, path};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    #[tokio::test]
    async fn list_projects_ok() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/v1/companion/projects"))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "projects": [{"id":"p1","name":"N","slug":"n","updated_at":"t"}]
            })))
            .mount(&server)
            .await;
        let items = list_projects(&server.uri(), "jwt").await.unwrap();
        assert_eq!(items[0].id, "p1");
    }
}
