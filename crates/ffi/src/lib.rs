//! UniFFI surface + blocking facade over [`tokito_companion_core`].
//!
//! React Native (Expo module) loads `libtokito_companion_ffi.so` through
//! generated Kotlin bindings. All methods are real HTTP via core — nothing stubbed.

#![deny(unsafe_op_in_unsafe_fn)]

use std::sync::{Arc, OnceLock};

use tokito_companion_core::{
    decide_proposal as core_decide_proposal, get_artifact as core_get_artifact,
    get_project as core_get_project, list_projects as core_list_projects,
    list_proposals as core_list_proposals, me as core_me, oauth_exchange as core_oauth_exchange,
    otp_request as core_otp_request, otp_verify as core_otp_verify, CloudError,
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

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct BomLine {
    pub r#ref: String,
    pub value: String,
    pub footprint: String,
    pub qty: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct DesignSummary {
    pub id: String,
    pub name: String,
    pub sheet_count: i64,
    pub bom_lines: Vec<BomLine>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ArtifactMeta {
    pub name: String,
    pub content_type: String,
    pub sha256: String,
    pub bytes: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ProjectListItem {
    pub id: String,
    pub name: String,
    pub slug: String,
    pub updated_at: String,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ProjectDetail {
    pub id: String,
    pub name: String,
    pub slug: String,
    pub updated_at: String,
    pub designs: Vec<DesignSummary>,
    pub artifact_manifest: Vec<ArtifactMeta>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Proposal {
    pub id: String,
    pub kind: String,
    pub title: String,
    pub summary: String,
    pub created_at: String,
    /// JSON-encoded proposal payload from the wire.
    pub payload_json: String,
    pub status: String,
    pub decision: Option<String>,
    pub note: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ArtifactBytes {
    pub content_type: String,
    pub body: Vec<u8>,
}

/// Auth / sync failures mapped for UniFFI / RN UI copy.
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

fn map_bom(line: tokito_companion_core::BomLine) -> BomLine {
    BomLine {
        r#ref: line.r#ref,
        value: line.value,
        footprint: line.footprint,
        qty: line.qty,
    }
}

fn map_design(d: tokito_companion_core::DesignSummary) -> DesignSummary {
    DesignSummary {
        id: d.id,
        name: d.name,
        sheet_count: d.sheet_count,
        bom_lines: d.bom_lines.into_iter().map(map_bom).collect(),
    }
}

fn map_artifact_meta(a: tokito_companion_core::ArtifactMeta) -> ArtifactMeta {
    ArtifactMeta {
        name: a.name,
        content_type: a.content_type,
        sha256: a.sha256,
        bytes: a.bytes,
    }
}

fn map_project_list(p: tokito_companion_core::ProjectListItem) -> ProjectListItem {
    ProjectListItem {
        id: p.id,
        name: p.name,
        slug: p.slug,
        updated_at: p.updated_at,
    }
}

fn map_project_detail(p: tokito_companion_core::ProjectDetail) -> ProjectDetail {
    ProjectDetail {
        id: p.id,
        name: p.snapshot.name,
        slug: p.snapshot.slug,
        updated_at: p.snapshot.updated_at,
        designs: p.snapshot.designs.into_iter().map(map_design).collect(),
        artifact_manifest: p
            .snapshot
            .artifact_manifest
            .into_iter()
            .map(map_artifact_meta)
            .collect(),
    }
}

fn map_proposal(p: tokito_companion_core::Proposal) -> Proposal {
    Proposal {
        id: p.id,
        kind: p.kind,
        title: p.title,
        summary: p.summary,
        created_at: p.created_at,
        payload_json: p.payload.to_string(),
        status: p.status,
        decision: p.decision,
        note: p.note,
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

    /// `GET /v1/companion/projects`
    pub fn list_projects(&self, token: String) -> Result<Vec<ProjectListItem>, CompanionError> {
        let items = runtime()
            .block_on(core_list_projects(&self.base_url, &token))
            .map_err(CompanionError::from)?;
        Ok(items.into_iter().map(map_project_list).collect())
    }

    /// `GET /v1/companion/projects/{id}`
    pub fn get_project(
        &self,
        token: String,
        project_id: String,
    ) -> Result<ProjectDetail, CompanionError> {
        let detail = runtime()
            .block_on(core_get_project(&self.base_url, &token, &project_id))
            .map_err(CompanionError::from)?;
        Ok(map_project_detail(detail))
    }

    /// `GET .../proposals`
    pub fn list_proposals(
        &self,
        token: String,
        project_id: String,
        status: Option<String>,
    ) -> Result<Vec<Proposal>, CompanionError> {
        let items = runtime()
            .block_on(core_list_proposals(
                &self.base_url,
                &token,
                &project_id,
                status.as_deref(),
            ))
            .map_err(CompanionError::from)?;
        Ok(items.into_iter().map(map_proposal).collect())
    }

    /// `POST .../proposals/{id}/decision`
    pub fn decide_proposal(
        &self,
        token: String,
        project_id: String,
        proposal_id: String,
        decision: String,
        note: Option<String>,
    ) -> Result<Proposal, CompanionError> {
        let proposal = runtime()
            .block_on(core_decide_proposal(
                &self.base_url,
                &token,
                &project_id,
                &proposal_id,
                &decision,
                note.as_deref(),
            ))
            .map_err(CompanionError::from)?;
        Ok(map_proposal(proposal))
    }

    /// `GET .../artifacts/{name}`
    pub fn get_artifact(
        &self,
        token: String,
        project_id: String,
        name: String,
    ) -> Result<ArtifactBytes, CompanionError> {
        let (content_type, body) = runtime()
            .block_on(core_get_artifact(
                &self.base_url,
                &token,
                &project_id,
                &name,
            ))
            .map_err(CompanionError::from)?;
        Ok(ArtifactBytes { content_type, body })
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
