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
        let account = runtime().block_on(tokito_companion_core::me(&base, &token))?;
        let owner = format!("{base}|{}", account.email.to_lowercase());
        let key = format!("{:x}", Sha256::digest(owner.as_bytes()));
        let path = Path::new(&storage_dir).join(format!("chat-{key}.sqlite3"));
        let store = Arc::new(ConversationStore::open(&path, &owner)?);
        runtime().block_on(tokito_companion_core::register_device(
            &base,
            &token,
            &device_id,
            "Tokito app",
            "android",
        ))?;
        let (stop, receiver) = watch::channel(false);
        let (wake, wake_rx) = mpsc::channel(1);
        runtime().spawn(outbox(
            base.clone(),
            token.clone(),
            store.clone(),
            receiver,
            wake_rx,
            listener,
        ));
        Ok(Arc::new(ConversationSession {
            base,
            token,
            device: device_id,
            store,
            stop,
            wake,
        }))
    }
}

impl ConversationSession {
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
        let threads = runtime().block_on(api::list_threads(
            &self.base,
            &self.token,
            &project,
            &design,
        ))?;
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
        let thread = runtime().block_on(api::create_thread(
            &self.base,
            &self.token,
            &project,
            &design,
            &id,
            &title,
        ))?;
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
        let page = runtime().block_on(api::get_thread_tail(
            &self.base,
            &self.token,
            &project,
            &design,
            &thread,
            before,
        ))?;
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

    pub fn send(
        &self,
        project: String,
        design: String,
        thread: String,
        content: String,
        base_version: u64,
        kind: String,
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
        let run = runtime().block_on(api::cancel_run(&self.base, &self.token, &run))?;
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
        runtime().block_on(api::delete_thread(
            &self.base,
            &self.token,
            &project,
            &design,
            &thread,
        ))?;
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
            run,
            after,
            self.store.clone(),
            self.stop.subscribe(),
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
    store: Arc<ConversationStore>,
    mut stop: watch::Receiver<bool>,
    mut wake: mpsc::Receiver<()>,
    listener: Arc<dyn ConversationListener>,
) {
    let mut delay = 1;
    loop {
        if *stop.borrow() {
            return;
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
        for message in pending {
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
    run_id: String,
    mut delivered: u64,
    store: Arc<ConversationStore>,
    mut session_stop: watch::Receiver<bool>,
    mut stop: watch::Receiver<bool>,
    listener: Arc<dyn ConversationListener>,
) {
    let mut delay = 1;
    loop {
        if *stop.borrow() || *session_stop.borrow() {
            return;
        }
        let stream_stop = stop.clone();
        let result = tokio::select! {
            _ = stop.changed() => return, _ = session_stop.changed() => return,
            result = async {
                let run = api::get_run(&base, &token, &run_id).await?;
                store.acknowledge(&run)?;
                loop {
                    let events = store.events(&run_id, delivered)?;
                    if events.is_empty() { break; }
                    for event in events { delivered = event.seq; emit(listener.as_ref(), serde_json::json!({"type":"event","run_id":run_id,"event":event})); }
                }
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
}
