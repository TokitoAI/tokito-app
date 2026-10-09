//! Durable Cloud conversations. Local inference never participates in this path.
use crate::{runtime, CompanionClient, CompanionError};
use sha2::{Digest, Sha256};
use std::{path::Path, sync::Arc, time::Duration};
use tokio::sync::{mpsc, watch};
use tokito_companion_core::{
    conversation_store::{ConversationStore, PendingMessage},
    conversations::{self as api, RunKind, SendMessage},
    CloudError,
};

#[uniffi::export(with_foreign)]
pub trait ConversationListener: Send + Sync + std::fmt::Debug {
    /// Low-rate lifecycle or committed event JSON; never media/model buffers.
    fn on_change(&self, payload_json: String);
}

fn emit(listener: &dyn ConversationListener, value: serde_json::Value) {
    listener.on_change(value.to_string());
}

#[derive(uniffi::Object)]
pub struct ConversationSession {
    base: String,
    token: String,
    device: String,
    store: Arc<ConversationStore>,
    stop: watch::Sender<bool>,
    wake: mpsc::Sender<()>,
    listener: Arc<dyn ConversationListener>,
    identity_path: String,
}

#[uniffi::export]
impl CompanionClient {
    /// Caller supplies private application storage. Account identity is verified
    /// by the server, not trusted from a JS-selected cache name or JWT parsing.
    pub fn open_conversations(
        &self,
        token: String,
        device_id: String,
        storage_dir: String,
        listener: Arc<dyn ConversationListener>,
    ) -> Result<Arc<ConversationSession>, CompanionError> {
        let base = api::origin(&self.base_url)?;
        let identity = crate::identity::identity_store(&storage_dir)?;
        let account = runtime().block_on(identity.resolve(&base, &token))?.account;
        let owner = format!("{base}|{}", account.email.to_lowercase());
        let key = format!("{:x}", Sha256::digest(owner.as_bytes()));
        let path = Path::new(&storage_dir).join(format!("chat-{key}.sqlite3"));
        let store = Arc::new(ConversationStore::open(&path, &owner)?);
        let (stop, _) = watch::channel(false);
        let (wake, wake_rx) = mpsc::channel(1);
        runtime().spawn(outbox(
            base.clone(),
            token.clone(),
            (device_id.clone(), storage_dir.clone()),
            store.clone(),
            stop.clone(),
            wake_rx,
            listener.clone(),
        ));
        Ok(Arc::new(ConversationSession {
            base,
            token,
            device: device_id,
            store,
            stop,
            wake,
            listener,
            identity_path: storage_dir,
        }))
    }
}

impl ConversationSession {
    fn failure(&self, error: CloudError) -> CompanionError {
        if matches!(error, CloudError::Unauthorized) {
            revoke_identity(&self.identity_path, &self.base, &self.token);
            let _ = self.stop.send(true);
        }
        error.into()
    }
    fn ensure_open(&self) -> Result<(), CompanionError> {
        if *self.stop.borrow() {
            Err(CompanionError::Unauthorized)
        } else {
            Ok(())
        }
    }
}

#[uniffi::export]
impl ConversationSession {
    pub fn shutdown(&self) {
        let _ = self.stop.send(true);
    }

    pub fn list_threads(&self, project: String, design: String) -> Result<String, CompanionError> {
        self.ensure_open()?;
        let result = runtime().block_on(api::list_threads(
            &self.base,
            &self.token,
            &project,
            &design,
        ));
        let mut threads = match result {
            Ok(threads) => {
                self.store.cache_threads(&project, &design, &threads)?;
                threads
            }
            Err(CloudError::Network(_)) => {
                let threads = self
                    .store
                    .cached_threads(&project, &design)?
                    .unwrap_or_default();
                emit(
                    self.listener.as_ref(),
                    serde_json::json!({"type":"offline"}),
                );
                threads
            }
            Err(error) => return Err(self.failure(error)),
        };
        for local in self.store.pending_threads()? {
            if local.project_id == project
                && local.design_id == design
                && !threads.iter().any(|t| t.id == local.id)
            {
                threads.push(local);
            }
        }
        serde_json::to_string(&threads).map_err(|_| CompanionError::Unexpected)
    }

    pub fn create_thread(
        &self,
        project: String,
        design: String,
        title: String,
    ) -> Result<String, CompanionError> {
        self.ensure_open()?;
        let id = uuid::Uuid::new_v4().to_string();
        if title.len() > 512 || project.is_empty() || design.is_empty() {
            return Err(CompanionError::Unexpected);
        }
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs() as i64;
        let thread = api::Thread {
            id,
            project_id: project,
            design_id: design,
            title,
            created_at: now,
            updated_at: now,
        };
        self.store.enqueue_thread(&thread)?;
        let _ = self.wake.try_send(());
        serde_json::to_string(&thread).map_err(|_| CompanionError::Unexpected)
    }

    pub fn history(
        &self,
        project: String,
        design: String,
        thread: String,
        before: Option<u64>,
    ) -> Result<String, CompanionError> {
        self.ensure_open()?;
        if self.store.thread_pending(&thread)? {
            return serde_json::to_string(
                &self
                    .store
                    .cached_tail(&project, &design, &thread, before)?
                    .ok_or(CompanionError::Unexpected)?,
            )
            .map_err(|_| CompanionError::Unexpected);
        }
        let result = runtime().block_on(api::get_thread_tail(
            &self.base,
            &self.token,
            &project,
            &design,
            &thread,
            before,
        ));
        let page = match result {
            Ok(page) => page,
            Err(error @ CloudError::Network(_)) => {
                let Some(page) = self.store.cached_tail(&project, &design, &thread, before)? else {
                    return Err(error.into());
                };
                emit(
                    self.listener.as_ref(),
                    serde_json::json!({"type":"offline"}),
                );
                return serde_json::to_string(&page).map_err(|_| CompanionError::Unexpected);
            }
            Err(error) => return Err(self.failure(error)),
        };
        self.store.cache_page(&page)?;
        if let Some(run) = &page.latest_run {
            self.store.acknowledge(run)?;
        }
        serde_json::to_string(&page).map_err(|_| CompanionError::Unexpected)
    }

    /// Explicit offline history access; the UI must label cached data as offline.
    pub fn cached_history(&self, thread: String, after: u64) -> Result<String, CompanionError> {
        self.ensure_open()?;
        serde_json::to_string(&self.store.cached_page(&thread, after)?)
            .map_err(|_| CompanionError::Unexpected)
    }

    // Flat scalar arguments keep the Expo/UniFFI boundary bounded and typed.
    #[allow(clippy::too_many_arguments)]
    pub fn send(
        &self,
        project: String,
        design: String,
        thread: String,
        content: String,
        base_version: u64,
        kind: String,
        active_sheet_id: Option<String>,
    ) -> Result<String, CompanionError> {
        self.ensure_open()?;
        if content.trim().is_empty() || content.len() > 64 * 1024 {
            return Err(CompanionError::Unexpected);
        }
        let kind = match kind.as_str() {
            "planner" => RunKind::Planner,
            "schematic" => RunKind::Schematic,
            "bom" => RunKind::Bom,
            _ => return Err(CompanionError::Unexpected),
        };
        let message = PendingMessage {
            project_id: project,
            design_id: design,
            thread_id: thread,
            request: SendMessage {
                message_id: uuid::Uuid::new_v4().to_string(),
                run_id: uuid::Uuid::new_v4().to_string(),
                device_id: self.device.clone(),
                content,
                base_schematic_version: base_version,
                active_sheet_id,
                kind,
                snapshot: None,
                references: vec![],
                selection: None,
                budget: serde_json::json!({"max_iterations":10,"max_llm_tokens":0,"max_scrapes":12,"max_catalog_reads":16,"max_wall_clock_ms":60000,"max_mcp_context_tokens":4000,"max_wall_clock_extensions":1,"wall_clock_extension_ms":45000}),
            },
        };
        self.store.enqueue(&message)?;
        let _ = self.wake.try_send(());
        serde_json::to_string(&message).map_err(|_| CompanionError::Unexpected)
    }

    pub fn cancel(&self, run: String) -> Result<String, CompanionError> {
        self.ensure_open()?;
        let run = runtime()
            .block_on(api::cancel_run(&self.base, &self.token, &run))
            .map_err(|error| self.failure(error))?;
        self.store.acknowledge(&run)?;
        serde_json::to_string(&run).map_err(|_| CompanionError::Unexpected)
    }

    pub fn delete_thread(
        &self,
        project: String,
        design: String,
        thread: String,
    ) -> Result<(), CompanionError> {
        self.ensure_open()?;
        // Server rejects active runs. Local outbox must be resolved before delete.
        if self.store.has_pending(&thread)? {
            return Err(CompanionError::Unexpected);
        }
        runtime()
            .block_on(api::delete_thread(
                &self.base,
                &self.token,
                &project,
                &design,
                &thread,
            ))
            .map_err(|error| self.failure(error))?;
        self.store.remove_thread(&thread)?;
        Ok(())
    }

    pub fn discard_blocked_message(&self, message_id: String) -> Result<bool, CompanionError> {
        self.ensure_open()?;
        self.store.discard_blocked(&message_id).map_err(Into::into)
    }

    pub fn blocked_messages(&self, thread: String) -> Result<String, CompanionError> {
        self.ensure_open()?;
        serde_json::to_string(&self.store.blocked(&thread)?).map_err(|_| CompanionError::Unexpected)
    }

    pub fn queued_message(&self, thread: String) -> Result<String, CompanionError> {
        self.ensure_open()?;
        serde_json::to_string(&self.store.queued_for_thread(&thread)?)
            .map_err(|_| CompanionError::Unexpected)
    }

    pub fn recovered_draft(&self, thread: String) -> Result<Option<String>, CompanionError> {
        self.ensure_open()?;
        self.store.recovered_draft(&thread).map_err(Into::into)
    }

    pub fn restore_blocked_message(&self, message_id: String) -> Result<String, CompanionError> {
        self.ensure_open()?;
        self.store.restore_blocked(&message_id).map_err(Into::into)
    }

    pub fn watch_run(
        &self,
        run: String,
        after: u64,
        listener: Arc<dyn ConversationListener>,
    ) -> Result<Arc<RunSubscription>, CompanionError> {
        self.ensure_open()?;
        let (stop, receiver) = watch::channel(false);
        runtime().spawn(watch_run(
            self.base.clone(),
            self.token.clone(),
            self.identity_path.clone(),
            run,
            after,
            self.store.clone(),
            self.stop.clone(),
            receiver,
            listener,
        ));
        Ok(Arc::new(RunSubscription { stop }))
    }
}

impl Drop for ConversationSession {
    fn drop(&mut self) {
        let _ = self.stop.send(true);
    }
}

#[derive(uniffi::Object)]
pub struct RunSubscription {
    stop: watch::Sender<bool>,
}
#[uniffi::export]
impl RunSubscription {
    pub fn stop(&self) {
        let _ = self.stop.send(true);
    }
}
impl Drop for RunSubscription {
    fn drop(&mut self) {
        let _ = self.stop.send(true);
    }
}

async fn outbox(
    base: String,
    token: String,
    registration: (String, String),
    store: Arc<ConversationStore>,
    session_stop: watch::Sender<bool>,
    mut wake: mpsc::Receiver<()>,
    listener: Arc<dyn ConversationListener>,
) {
    let mut stop = session_stop.subscribe();
    let (device, identity_path) = registration;
    let mut delay = 1;
    let mut registered = false;
    loop {
        if *stop.borrow() {
            return;
        }
        if !registered {
            let result = tokio::select! {
                _ = stop.changed() => return,
                result = tokito_companion_core::register_device(&base, &token, &device, "Tokito app", "android") => result,
            };
            match result {
                Ok(_) => registered = true,
                Err(CloudError::Unauthorized) => {
                    session_stop.send_replace(true);
                    revoke_identity(&identity_path, &base, &token);
                    emit(
                        listener.as_ref(),
                        serde_json::json!({"type":"unauthorized"}),
                    );
                    return;
                }
                Err(_) => {
                    emit(listener.as_ref(), serde_json::json!({"type":"offline"}));
                    tokio::select! { _ = stop.changed() => return, _ = tokio::time::sleep(Duration::from_secs(3)) => {} }
                    continue;
                }
            }
        }
        let pending = match store.pending() {
            Ok(p) => p,
            Err(_) => {
                emit(
                    listener.as_ref(),
                    serde_json::json!({"type":"storage_error"}),
                );
                return;
            }
        };
        let threads = match store.pending_threads() {
            Ok(value) => value,
            Err(_) => {
                emit(
                    listener.as_ref(),
                    serde_json::json!({"type":"storage_error"}),
                );
                return;
            }
        };
        for thread in threads {
            let result = tokio::select! { _=stop.changed()=>return, result=api::create_thread(&base,&token,&thread.project_id,&thread.design_id,&thread.id,&thread.title)=>result };
            match result {
                Ok(confirmed) => {
                    if store.finish_thread(&confirmed).is_err() {
                        return;
                    }
                }
                Err(CloudError::Unauthorized) => {
                    session_stop.send_replace(true);
                    revoke_identity(&identity_path, &base, &token);
                    emit(
                        listener.as_ref(),
                        serde_json::json!({"type":"unauthorized"}),
                    );
                    return;
                }
                Err(_) => {
                    emit(listener.as_ref(), serde_json::json!({"type":"offline"}));
                    break;
                }
            }
        }
        for message in pending {
            if store.thread_pending(&message.thread_id).unwrap_or(true) {
                continue;
            }
            let result = tokio::select! {
                _ = stop.changed() => return,
                result = api::send_message(&base, &token, &message.project_id, &message.design_id, &message.thread_id, &message.request) => result,
            };
            match result {
                Ok(run) => {
                    if store.acknowledge(&run).is_err() {
                        emit(
                            listener.as_ref(),
                            serde_json::json!({"type":"storage_error"}),
                        );
                        return;
                    }
                    delay = 1;
                    emit(
                        listener.as_ref(),
                        serde_json::json!({"type":"accepted","run":run}),
                    );
                }
                Err(CloudError::Unauthorized) => {
                    session_stop.send_replace(true);
                    revoke_identity(&identity_path, &base, &token);
                    emit(
                        listener.as_ref(),
                        serde_json::json!({"type":"unauthorized"}),
                    );
                    return;
                }
                Err(error @ (CloudError::Conflict { .. } | CloudError::Unexpected)) => {
                    if store
                        .block(&message.request.message_id, "revision_or_request_conflict")
                        .is_err()
                    {
                        return;
                    }
                    emit(
                        listener.as_ref(),
                        serde_json::json!({"type":"blocked","message":message,"reason":error.to_string()}),
                    );
                }
                Err(_) => {
                    delay = (delay * 2).min(30);
                    emit(
                        listener.as_ref(),
                        serde_json::json!({"type":"offline","message_id":message.request.message_id}),
                    );
                    break;
                }
            }
        }
        tokio::select! { _ = stop.changed() => return, _ = wake.recv() => {}, _ = tokio::time::sleep(Duration::from_secs(delay)) => {} }
    }
}

#[allow(clippy::too_many_arguments)]
async fn watch_run(
    base: String,
    token: String,
    identity_path: String,
    run_id: String,
    mut delivered: u64,
    store: Arc<ConversationStore>,
    session_shutdown: watch::Sender<bool>,
    mut stop: watch::Receiver<bool>,
    listener: Arc<dyn ConversationListener>,
) {
    let mut session_stop = session_shutdown.subscribe();
    let mut delay = 1;
    loop {
        if *stop.borrow() || *session_stop.borrow() {
            return;
        }
        let stream_stop = stop.clone();
        let result = tokio::select! {
            _ = stop.changed() => return, _ = session_stop.changed() => return,
            result = async {
                loop {
                    let events = store.events(&run_id, delivered)?;
                    if events.is_empty() { break; }
                    for event in events { delivered = event.seq; emit(listener.as_ref(), serde_json::json!({"type":"event","run_id":run_id,"event":event})); }
                }
                let run = api::get_run(&base, &token, &run_id).await?;
                store.acknowledge(&run)?;
                let mut cursor = store.cursor(&run_id)?;
                api::stream_run(&base, &token, &run_id, &mut cursor, stream_stop, |event| {
                    store.apply_event(&run_id, &event)?;
                    if event.seq > delivered { delivered = event.seq; emit(listener.as_ref(), serde_json::json!({"type":"event","run_id":run_id,"event":event})); }
                    Ok(())
                }).await?;
                let run = api::get_run(&base, &token, &run_id).await?;
                store.acknowledge(&run)?;
                Ok::<_, CloudError>(run)
            } => result,
        };
        match result {
            Ok(run) if run.is_terminal() => {
                emit(
                    listener.as_ref(),
                    serde_json::json!({"type":"settled","run":run}),
                );
                return;
            }
            Ok(_) => delay = 1,
            Err(CloudError::Unauthorized) => {
                session_shutdown.send_replace(true);
                revoke_identity(&identity_path, &base, &token);
                emit(
                    listener.as_ref(),
                    serde_json::json!({"type":"unauthorized"}),
                );
                return;
            }
            Err(_) => {
                delay = (delay * 2).min(30);
                emit(
                    listener.as_ref(),
                    serde_json::json!({"type":"reconnecting","run_id":run_id}),
                );
            }
        }
        tokio::select! { _ = stop.changed() => return, _ = session_stop.changed() => return, _ = tokio::time::sleep(Duration::from_secs(delay)) => {} }
    }
}

fn revoke_identity(path: &str, base: &str, token: &str) {
    if let Ok(store) = crate::identity::identity_store(path) {
        let _ = store.revoke(base, token);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;
    use wiremock::{
        matchers::{method, path},
        Mock, MockServer, ResponseTemplate,
    };

    #[derive(Debug, Default)]
    struct Listener(Mutex<Vec<serde_json::Value>>);
    impl ConversationListener for Listener {
        fn on_change(&self, payload_json: String) {
            self.0
                .lock()
                .unwrap()
                .push(serde_json::from_str(&payload_json).unwrap());
        }
    }

    #[test]
    fn native_session_persists_then_delivers_and_close_revokes_access() {
        let server = runtime().block_on(MockServer::start());
        runtime().block_on(async {
            Mock::given(method("GET")).and(path("/v1/auth/me"))
                .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({"email":"qa@example.test","plan":"free"}))).mount(&server).await;
            Mock::given(method("POST")).and(path("/v1/companion/devices"))
                .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({"id":"device","label":"test","platform":"android","last_seen_at":1}))).mount(&server).await;
            Mock::given(method("POST")).and(path("/v1/projects/p/designs/d/threads/t/messages"))
                .respond_with(|request: &wiremock::Request| {
                    let body: serde_json::Value = serde_json::from_slice(&request.body).unwrap();
                    ResponseTemplate::new(200).set_body_json(serde_json::json!({"run_id":body["run_id"],"message_id":body["message_id"],"project_id":"p","design_id":"d","thread_id":"t","status":"running","created_at":1,"updated_at":1}))
                }).expect(1).mount(&server).await;
        });
        let dir = std::env::temp_dir().join(format!("tokito-native-chat-{}", uuid::Uuid::new_v4()));
        let listener = Arc::new(Listener::default());
        let client = CompanionClient::new(server.uri());
        let session = client
            .open_conversations(
                "test-token".into(),
                "device".into(),
                dir.to_string_lossy().into(),
                listener.clone(),
            )
            .unwrap();
        let pending: serde_json::Value = serde_json::from_str(
            &session
                .send(
                    "p".into(),
                    "d".into(),
                    "t".into(),
                    "Build a sensor".into(),
                    0,
                    "planner".into(),
                    None,
                )
                .unwrap(),
        )
        .unwrap();
        runtime().block_on(async {
            tokio::time::timeout(Duration::from_secs(5), async {
                loop {
                    if listener
                        .0
                        .lock()
                        .unwrap()
                        .iter()
                        .any(|e| e["type"] == "accepted")
                    {
                        break;
                    }
                    tokio::time::sleep(Duration::from_millis(10)).await;
                }
            })
            .await
            .unwrap();
        });
        assert!(session.store.pending().unwrap().is_empty());
        assert!(session
            .store
            .run(pending["request"]["run_id"].as_str().unwrap())
            .unwrap()
            .is_some());
        session.shutdown();
        assert!(matches!(
            session.list_threads("p".into(), "d".into()),
            Err(CompanionError::Unauthorized)
        ));
        drop(session);
        runtime().block_on(async {
            tokio::time::timeout(Duration::from_secs(5), async {
                while std::fs::remove_dir_all(&dir).is_err() {
                    tokio::time::sleep(Duration::from_millis(10)).await;
                }
            })
            .await
            .unwrap();
        });
    }

    #[tokio::test]
    async fn background_auth_rejection_closes_session_and_preserves_queue() {
        for reject_registration in [true, false] {
            let server = MockServer::start().await;
            Mock::given(method("POST"))
                .and(path("/v1/companion/devices"))
                .respond_with(if reject_registration {
                    ResponseTemplate::new(401)
                } else {
                    ResponseTemplate::new(200).set_body_json(serde_json::json!({"id":"device","label":"test","platform":"android","last_seen_at":1}))
                })
                .mount(&server).await;
            Mock::given(method("POST"))
                .and(path("/v1/projects/p/designs/d/threads/t/messages"))
                .respond_with(ResponseTemplate::new(401))
                .mount(&server)
                .await;
            let store =
                Arc::new(ConversationStore::open(Path::new(":memory:"), "account").unwrap());
            let (stop, _) = watch::channel(false);
            let (wake, wake_rx) = mpsc::channel(1);
            let listener = Arc::new(Listener::default());
            let session = ConversationSession {
                base: server.uri(),
                token: "token".into(),
                device: "device".into(),
                store: store.clone(),
                stop: stop.clone(),
                wake,
                listener: listener.clone(),
                identity_path: String::new(),
            };
            session
                .send(
                    "p".into(),
                    "d".into(),
                    "t".into(),
                    "Queued draft".into(),
                    0,
                    "planner".into(),
                    None,
                )
                .unwrap();
            tokio::time::timeout(
                Duration::from_secs(3),
                outbox(
                    server.uri(),
                    "token".into(),
                    ("device".into(), String::new()),
                    store.clone(),
                    stop,
                    wake_rx,
                    listener.clone(),
                ),
            )
            .await
            .unwrap();
            assert!(matches!(
                session.ensure_open(),
                Err(CompanionError::Unauthorized)
            ));
            assert!(matches!(
                session.send(
                    "p".into(),
                    "d".into(),
                    "other".into(),
                    "Must not enqueue".into(),
                    0,
                    "planner".into(),
                    None
                ),
                Err(CompanionError::Unauthorized)
            ));
            assert_eq!(store.pending().unwrap().len(), 1);
            assert_eq!(
                listener.0.lock().unwrap().last().unwrap()["type"],
                "unauthorized"
            );
        }
    }

    #[tokio::test]
    async fn run_auth_rejection_stops_sibling_workers() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .respond_with(ResponseTemplate::new(401))
            .mount(&server)
            .await;
        let store = Arc::new(ConversationStore::open(Path::new(":memory:"), "account").unwrap());
        let (session_stop, mut sibling) = watch::channel(false);
        let (_stop, receiver) = watch::channel(false);
        let listener = Arc::new(Listener::default());
        tokio::time::timeout(
            Duration::from_secs(3),
            watch_run(
                server.uri(),
                "token".into(),
                String::new(),
                "r".into(),
                0,
                store,
                session_stop.clone(),
                receiver,
                listener.clone(),
            ),
        )
        .await
        .unwrap();
        sibling.changed().await.unwrap();
        assert!(*session_stop.borrow());
        assert_eq!(
            listener.0.lock().unwrap().last().unwrap()["type"],
            "unauthorized"
        );
    }

    #[tokio::test]
    async fn saved_run_events_are_delivered_before_unavailable_network() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .respond_with(ResponseTemplate::new(503))
            .mount(&server)
            .await;
        let store = Arc::new(ConversationStore::open(Path::new(":memory:"), "account").unwrap());
        store
            .acknowledge(&api::Run {
                run_id: "r".into(),
                project_id: "p".into(),
                design_id: "d".into(),
                thread_id: "t".into(),
                message_id: "m".into(),
                status: "running".into(),
                created_at: 1,
                updated_at: 1,
            })
            .unwrap();
        store.apply_event("r", &api::RunEvent { seq: 1, event: serde_json::json!({"type":"assistant_text_delta","text":"saved response"}) }).unwrap();
        let listener = Arc::new(Listener::default());
        let (session_stop, _) = watch::channel(false);
        let (stop, receiver) = watch::channel(false);
        let task = tokio::spawn(watch_run(
            server.uri(),
            "token".into(),
            String::new(),
            "r".into(),
            0,
            store,
            session_stop,
            receiver,
            listener.clone(),
        ));
        tokio::time::timeout(Duration::from_secs(2), async {
            loop {
                if listener
                    .0
                    .lock()
                    .unwrap()
                    .iter()
                    .any(|event| event["type"] == "event")
                {
                    break;
                }
                tokio::task::yield_now().await;
            }
        })
        .await
        .unwrap();
        stop.send(true).unwrap();
        task.await.unwrap();
        let events = listener.0.lock().unwrap();
        assert_eq!(events[0]["event"]["event"]["text"], "saved response");
    }
}
