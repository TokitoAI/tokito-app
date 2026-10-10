//! UniFFI surface + blocking facade over [`tokito_companion_core`].
//!
//! React Native (Expo module) loads `libtokito_companion_ffi.so` through
//! generated Kotlin bindings. All methods are real HTTP via core — nothing stubbed.

#![deny(unsafe_op_in_unsafe_fn)]

mod conversations;
mod identity;
mod preview;
pub use identity::ResolvedIdentity;
mod reviews;
pub use conversations::{ConversationListener, ConversationSession, RunSubscription};
pub use reviews::ReviewSession;

use std::path::Path;
use std::sync::{Arc, OnceLock};

use tokio::sync::{mpsc, watch};
use tokito_companion_core::{
    bootstrap_realtime, decide_proposal as core_decide_proposal, get_artifact as core_get_artifact,
    get_project as core_get_project, list_projects as core_list_projects,
    list_proposals as core_list_proposals, me as core_me, oauth_exchange as core_oauth_exchange,
    otp_request as core_otp_request, otp_verify as core_otp_verify, register_device,
    run_event_stream, submit_sync_command, CloudError, RealtimeStatus, SyncCommand, SyncStateStore,
};

uniffi::setup_scaffolding!();

/// Bounded status/classification only; never credentials or response bodies.
#[uniffi::export]
pub fn drain_http_diagnostics() -> String {
    tokito_companion_core::http_diagnostics::drain()
}

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

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct BomLine {
    pub r#ref: String,
    pub value: String,
    pub footprint: String,
    pub qty: f64,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
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
    pub last_activity_at: Option<i64>,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
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
    #[error("resource changed on another device (version {current_version})")]
    Conflict {
        current_version: u64,
        current_payload: String,
    },
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
            CloudError::Conflict {
                current_version,
                current_payload,
            } => Self::Conflict {
                current_version,
                current_payload,
            },
            CloudError::Unexpected => Self::Unexpected,
        }
    }
}

#[uniffi::export(with_foreign)]
pub trait SyncListener: Send + Sync + std::fmt::Debug {
    fn on_status(&self, status: String);
    fn on_bootstrap(&self, cursor: u64, resources_json: String);
    fn on_event(&self, event_json: String);
    fn on_conflict(&self, command_json: String, current_version: u64, current_payload_json: String);
    fn on_error(&self, message: String);
}

#[derive(uniffi::Object)]
pub struct SyncSubscription {
    stop: watch::Sender<bool>,
    wake_outbox: mpsc::UnboundedSender<()>,
    store: Arc<SyncStateStore>,
    device_id: String,
}

#[uniffi::export]
impl SyncSubscription {
    pub fn stop(&self) {
        let _ = self.stop.send(true);
    }

    /// Persist a Companion-authored chat or review action, then wake the
    /// network worker. CAD and workspace-management resources are read-only.
    pub fn enqueue_command(
        &self,
        project_id: String,
        design_id: Option<String>,
        resource: String,
        payload_json: String,
    ) -> Result<String, CompanionError> {
        validate_resource_scope(&resource, design_id.as_deref())?;
        let payload =
            serde_json::from_str(&payload_json).map_err(|_| CompanionError::Unexpected)?;
        let command_id = uuid::Uuid::new_v4().to_string();
        self.store.enqueue_next(&SyncCommand {
            device_id: self.device_id.clone(),
            command_id: command_id.clone(),
            project_id,
            design_id,
            resource,
            base_version: 0,
            payload,
        })?;
        let _ = self.wake_outbox.send(());
        Ok(command_id)
    }

    /// Resolve a surfaced version conflict. `keep_local=true` rebases the
    /// queued local payload onto the reported server version; false discards it.
    pub fn resolve_conflict(
        &self,
        command_id: String,
        keep_local: bool,
    ) -> Result<(), CompanionError> {
        if keep_local {
            self.store.rebase_conflict(&command_id)?;
            let _ = self.wake_outbox.send(());
        } else {
            self.store.discard_command(&command_id)?;
        }
        Ok(())
    }
}

impl Drop for SyncSubscription {
    fn drop(&mut self) {
        let _ = self.stop.send(true);
    }
}

fn validate_resource_scope(resource: &str, design_id: Option<&str>) -> Result<(), CompanionError> {
    let valid = match resource {
        "chat" | "suggestions" => design_id.is_some(),
        _ => false,
    };
    if valid {
        Ok(())
    } else {
        Err(CompanionError::Unexpected)
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
        last_activity_at: p.last_activity_at,
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

    /// Register this installation, bootstrap a consistent materialized view,
    /// then run the durable outbox and resumable SSE stream until stopped.
    #[allow(clippy::too_many_arguments)]
    pub fn start_sync(
        &self,
        token: String,
        device_id: String,
        device_label: String,
        platform: String,
        project_id: Option<String>,
        state_path: String,
        listener: Arc<dyn SyncListener>,
    ) -> Result<Arc<SyncSubscription>, CompanionError> {
        if state_path.trim().is_empty() || device_id.trim().is_empty() {
            return Err(CompanionError::Unexpected);
        }
        let base_url = tokito_companion_core::conversations::origin(&self.base_url)?;
        let verified = identity::identity_store(&state_path)?;
        let account = runtime()
            .block_on(verified.resolve(&base_url, &token))?
            .account;
        // Never reopen an unbound legacy outbox under a different account.
        // Project-filtered journals need independent cursors as well.
        use sha2::{Digest, Sha256};
        let owner = format!(
            "{base_url}|{}|{}",
            account.email.to_lowercase(),
            project_id.as_deref().unwrap_or("workspace")
        );
        let key = format!("{:x}", Sha256::digest(owner.as_bytes()));
        let state_path = Path::new(&state_path).with_file_name(format!("sync-{key}.sqlite3"));
        listener.on_status("connecting".into());
        let store = Arc::new(SyncStateStore::open(&state_path)?);
        let cached = store.resources(project_id.as_deref())?;
        let resources_json =
            serde_json::to_string(&cached).map_err(|_| CompanionError::Unexpected)?;
        listener.on_bootstrap(store.cursor()?, resources_json);
        replay_sync_conflicts(&store, listener.as_ref())?;

        let (stop_tx, stop_rx) = watch::channel(false);
        let (wake_tx, wake_rx) = mpsc::unbounded_channel();
        let subscription = Arc::new(SyncSubscription {
            stop: stop_tx.clone(),
            wake_outbox: wake_tx,
            store: Arc::clone(&store),
            device_id: device_id.clone(),
        });

        runtime().spawn(run_sync_session(
            base_url,
            token,
            state_path.to_string_lossy().into_owned(),
            project_id,
            (device_id, device_label, platform),
            store,
            listener,
            stop_tx,
            stop_rx,
            wake_rx,
        ));
        Ok(subscription)
    }
}

fn replay_sync_conflicts(
    store: &SyncStateStore,
    listener: &dyn SyncListener,
) -> Result<(), CloudError> {
    for conflict in store.conflicts()? {
        let command_json =
            serde_json::to_string(&conflict.command).map_err(|_| CloudError::Unexpected)?;
        listener.on_conflict(
            command_json,
            conflict.current_version,
            conflict.current_payload_json,
        );
    }
    Ok(())
}

#[allow(clippy::too_many_arguments)]
async fn run_sync_session(
    base_url: String,
    token: String,
    identity_path: String,
    project_id: Option<String>,
    device: (String, String, String),
    store: Arc<SyncStateStore>,
    listener: Arc<dyn SyncListener>,
    stop_tx: watch::Sender<bool>,
    mut stop_rx: watch::Receiver<bool>,
    wake_rx: mpsc::UnboundedReceiver<()>,
) {
    // Reconnection must finish registration and a consistent bootstrap before
    // any queued writes or SSE replay. Cached reads remain available meanwhile.
    let cursor = loop {
        if *stop_rx.borrow() {
            return;
        }
        let connect = async {
            register_device(&base_url, &token, &device.0, &device.1, &device.2).await?;
            let snapshot = bootstrap_realtime(&base_url, &token, project_id.as_deref()).await?;
            store.apply_bootstrap(&snapshot, project_id.as_deref())?;
            let json =
                serde_json::to_string(&snapshot.resources).map_err(|_| CloudError::Unexpected)?;
            listener.on_bootstrap(snapshot.cursor, json);
            Ok::<u64, CloudError>(snapshot.cursor)
        };
        let result = tokio::select! {
            result = connect => result,
            _ = stop_rx.changed() => return,
        };
        match result {
            Ok(cursor) => break cursor,
            Err(CloudError::Network(_) | CloudError::Unavailable | CloudError::RateLimited) => {
                listener.on_status("reconnecting".into())
            }
            Err(error) => {
                if matches!(error, CloudError::Unauthorized) {
                    let _ = identity::identity_store(&identity_path)
                        .and_then(|store| store.revoke(&base_url, &token).map_err(Into::into));
                }
                let _ = stop_tx.send(true);
                listener.on_error(error.to_string());
                listener.on_status("stopped".into());
                return;
            }
        }
        tokio::select! {
            _ = tokio::time::sleep(std::time::Duration::from_secs(3)) => {},
            _ = stop_rx.changed() => return,
        }
    };
    let stream_store = Arc::clone(&store);
    let stream_listener = Arc::clone(&listener);
    let status_listener = Arc::clone(&listener);
    let stream_stop = stop_rx.clone();
    let stream = run_event_stream(
        &base_url,
        &token,
        project_id.as_deref(),
        cursor,
        stream_stop.clone(),
        move |event| {
            stream_store.apply_event(&event)?;
            let event_json = serde_json::to_string(&event).map_err(|_| CloudError::Unexpected)?;
            stream_listener.on_event(event_json);
            Ok(())
        },
        move |status| status_listener.on_status(realtime_status_name(status).into()),
    );
    let outbox = run_outbox(
        &base_url,
        &token,
        store,
        Arc::clone(&listener),
        stop_rx,
        wake_rx,
    );

    tokio::pin!(stream);
    tokio::pin!(outbox);
    let result = tokio::select! {
        result = &mut stream => result,
        result = &mut outbox => result,
    };
    let _ = stop_tx.send(true);
    if let Err(error) = result {
        if matches!(error, CloudError::Unauthorized) {
            let _ = identity::identity_store(&identity_path)
                .and_then(|store| store.revoke(&base_url, &token).map_err(Into::into));
        }
        listener.on_error(error.to_string());
    }
    listener.on_status("stopped".into());
}

async fn run_outbox(
    base_url: &str,
    token: &str,
    store: Arc<SyncStateStore>,
    listener: Arc<dyn SyncListener>,
    mut stop: watch::Receiver<bool>,
    mut wake: mpsc::UnboundedReceiver<()>,
) -> Result<(), CloudError> {
    let mut interval = tokio::time::interval(std::time::Duration::from_secs(2));
    interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    loop {
        for command in store.pending()? {
            if *stop.borrow() {
                return Ok(());
            }
            // SSE acknowledgements and conflicts may invalidate this batch
            // while the preceding command was in flight.
            if !store.is_pending(&command.command_id)? {
                continue;
            }
            match submit_sync_command(base_url, token, &command).await {
                Ok(event) => store.apply_ack(&event)?,
                Err(CloudError::Conflict {
                    current_version,
                    current_payload,
                }) => {
                    store.mark_conflict(&command.command_id, current_version, &current_payload)?;
                    let command_json =
                        serde_json::to_string(&command).map_err(|_| CloudError::Unexpected)?;
                    listener.on_conflict(command_json, current_version, current_payload);
                    break;
                }
                Err(CloudError::Unauthorized) => return Err(CloudError::Unauthorized),
                Err(
                    CloudError::Network(_)
                    | CloudError::Unavailable
                    | CloudError::RateLimited
                    | CloudError::Unexpected,
                ) => {
                    break;
                }
                Err(error) => return Err(error),
            }
        }
        tokio::select! {
            _ = interval.tick() => {}
            wakeup = wake.recv() => {
                if wakeup.is_none() { return Ok(()); }
            }
            changed = stop.changed() => {
                if changed.is_err() || *stop.borrow() { return Ok(()); }
            }
        }
    }
}

fn realtime_status_name(status: RealtimeStatus) -> &'static str {
    match status {
        RealtimeStatus::Connecting => "connecting",
        RealtimeStatus::Live => "live",
        RealtimeStatus::Reconnecting => "reconnecting",
        RealtimeStatus::Stopped => "stopped",
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use wiremock::matchers::{method, path};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    #[derive(Debug, Default)]
    struct RecoveryListener(std::sync::Mutex<Vec<(String, u64, String)>>);

    impl SyncListener for RecoveryListener {
        fn on_status(&self, _: String) {}
        fn on_bootstrap(&self, _: u64, _: String) {}
        fn on_event(&self, _: String) {}
        fn on_conflict(&self, command: String, version: u64, payload: String) {
            self.0.lock().unwrap().push((command, version, payload));
        }
        fn on_error(&self, _: String) {}
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn offline_sync_does_not_transmit_outbox_before_registration_and_is_cancellable() {
        let server = MockServer::start().await;
        Mock::given(method("POST"))
            .respond_with(ResponseTemplate::new(503))
            .mount(&server)
            .await;
        let store = Arc::new(SyncStateStore::open(Path::new(":memory:")).unwrap());
        store
            .enqueue_next(&SyncCommand {
                device_id: "device".into(),
                command_id: "pending".into(),
                project_id: "project".into(),
                design_id: Some("design".into()),
                resource: "chat".into(),
                base_version: 0,
                payload: serde_json::json!({"text":"keep me"}),
            })
            .unwrap();
        let (stop, receiver) = watch::channel(false);
        let (_wake, wake_rx) = mpsc::unbounded_channel();
        let task = tokio::spawn(run_sync_session(
            server.uri(),
            "token".into(),
            String::new(),
            None,
            ("device".into(), "Test".into(), "android".into()),
            store.clone(),
            Arc::new(RecoveryListener::default()),
            stop.clone(),
            receiver,
            wake_rx,
        ));
        tokio::time::timeout(std::time::Duration::from_secs(2), async {
            loop {
                if !server.received_requests().await.unwrap().is_empty() {
                    break;
                }
                tokio::task::yield_now().await;
            }
        })
        .await
        .unwrap();
        stop.send(true).unwrap();
        tokio::time::timeout(std::time::Duration::from_secs(1), task)
            .await
            .unwrap()
            .unwrap();
        assert_eq!(store.pending().unwrap().len(), 1);
        assert!(server
            .received_requests()
            .await
            .unwrap()
            .iter()
            .all(|request| request.url.path().ends_with("/devices")));
    }

    #[test]
    fn startup_replays_persisted_conflicts_to_the_ui() {
        let path = std::env::temp_dir().join(format!(
            "tokito-sync-replay-{}.sqlite3",
            uuid::Uuid::new_v4()
        ));
        {
            let store = SyncStateStore::open(&path).unwrap();
            store
                .enqueue_next(&SyncCommand {
                    device_id: "device".into(),
                    command_id: "edit".into(),
                    project_id: "project".into(),
                    design_id: Some("design".into()),
                    resource: "chat".into(),
                    base_version: 0,
                    payload: serde_json::json!({}),
                })
                .unwrap();
            store.mark_conflict("edit", 8, "{\"remote\":true}").unwrap();
        }
        {
            let store = SyncStateStore::open(&path).unwrap();
            let listener = RecoveryListener::default();
            replay_sync_conflicts(&store, &listener).unwrap();
            let events = listener.0.lock().unwrap();
            assert_eq!(events.len(), 1);
            let command: SyncCommand = serde_json::from_str(&events[0].0).unwrap();
            assert_eq!(command.command_id, "edit");
            assert_eq!(events[0].1, 8);
            assert_eq!(events[0].2, "{\"remote\":true}");
        }
        std::fs::remove_file(path).unwrap();
    }

    #[tokio::test]
    async fn sync_authorization_rejection_revokes_offline_identity() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/v1/auth/me"))
            .respond_with(
                ResponseTemplate::new(200)
                    .set_body_json(serde_json::json!({"email":"qa@example.test","plan":"free"})),
            )
            .mount(&server)
            .await;
        let dir = std::env::temp_dir().join(format!("identity-sync-{}", uuid::Uuid::new_v4()));
        let state_path = dir.join("sync.sqlite3").to_string_lossy().into_owned();
        let token = "header.eyJleHAiOjQxMDI0NDQ4MDB9.signature";
        let identities = identity::identity_store(&state_path).unwrap();
        assert!(
            identities
                .resolve(&server.uri(), token)
                .await
                .unwrap()
                .online
        );
        assert!(identities
            .cached_account(&server.uri(), token)
            .unwrap()
            .is_some());
        server.reset().await;
        Mock::given(method("POST"))
            .respond_with(ResponseTemplate::new(401))
            .mount(&server)
            .await;
        let store = Arc::new(SyncStateStore::open(Path::new(":memory:")).unwrap());
        let (stop, receiver) = watch::channel(false);
        let (_wake, wake_rx) = mpsc::unbounded_channel();
        run_sync_session(
            server.uri(),
            token.into(),
            state_path,
            None,
            ("device".into(), "Test".into(), "android".into()),
            store,
            Arc::new(RecoveryListener::default()),
            stop.clone(),
            receiver,
            wake_rx,
        )
        .await;
        assert!(*stop.borrow());
        assert!(identities
            .cached_account(&server.uri(), token)
            .unwrap()
            .is_none());
        drop(identities);
        std::fs::remove_dir_all(dir).unwrap();
    }

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

    #[test]
    fn companion_can_only_author_chat_and_review_decisions() {
        assert!(validate_resource_scope("chat", Some("design-id")).is_ok());
        assert!(validate_resource_scope("suggestions", Some("design-id")).is_ok());
        for resource in ["project", "design", "schematic", "bom", "pcb"] {
            assert_eq!(
                validate_resource_scope(resource, Some("design-id")),
                Err(CompanionError::Unexpected),
                "{resource} must remain read-only on Companion"
            );
        }
        assert_eq!(
            validate_resource_scope("chat", None),
            Err(CompanionError::Unexpected)
        );
    }
}
