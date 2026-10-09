use crate::{runtime, CompanionClient, CompanionError};
use sha2::{Digest, Sha256};
use std::{
    path::Path,
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc,
    },
};
use tokito_companion_core::reviews::{self as api, ReviewRequest, ReviewStore};

#[cfg(test)]
mod tests {
    use super::*;
    use wiremock::{
        matchers::{method, path},
        Mock, MockServer, ResponseTemplate,
    };

    #[test]
    fn unavailable_registration_keeps_exact_review_command_across_offline_restart() {
        let server = runtime().block_on(MockServer::start());
        runtime().block_on(async {
            Mock::given(method("GET"))
                .and(path("/v1/auth/me"))
                .respond_with(
                    ResponseTemplate::new(200).set_body_json(
                        serde_json::json!({"email":"qa@example.test","plan":"free"}),
                    ),
                )
                .mount(&server)
                .await;
            Mock::given(method("POST"))
                .and(path("/v1/companion/devices"))
                .respond_with(ResponseTemplate::new(503))
                .mount(&server)
                .await;
        });
        let dir = std::env::temp_dir().join(format!("review-offline-{}", uuid::Uuid::new_v4()));
        let storage = dir.join("reviews").to_string_lossy().into_owned();
        let token = "header.eyJleHAiOjQxMDI0NDQ4MDB9.signature";
        let client = CompanionClient::new(server.uri());
        let session = client
            .open_reviews(token.into(), "device".into(), storage.clone())
            .unwrap();
        assert!(session
            .decide(
                "project".into(),
                "design".into(),
                "run".into(),
                1,
                "apply".into(),
                "[0]".into()
            )
            .is_err());
        let before = session
            .local_commands("project".into(), "design".into())
            .unwrap();
        let commands: serde_json::Value = serde_json::from_str(&before).unwrap();
        assert_eq!(commands.as_array().unwrap().len(), 1);
        assert_eq!(commands[0]["state"], "pending");
        session.shutdown();
        drop(session);
        runtime().block_on(async {
            server.reset().await;
            Mock::given(method("GET"))
                .and(path("/v1/auth/me"))
                .respond_with(ResponseTemplate::new(503))
                .mount(&server)
                .await;
        });
        let reopened = client
            .open_reviews(token.into(), "device".into(), storage)
            .unwrap();
        assert_eq!(
            before,
            reopened
                .local_commands("project".into(), "design".into())
                .unwrap()
        );
        reopened.shutdown();
        drop(reopened);
        std::fs::remove_dir_all(dir).unwrap();
    }
}

#[derive(uniffi::Object)]
pub struct ReviewSession {
    base: String,
    token: String,
    device: String,
    store: ReviewStore,
    closed: AtomicBool,
    registered: AtomicBool,
    identity_path: String,
}
#[uniffi::export]
impl CompanionClient {
    pub fn open_reviews(
        &self,
        token: String,
        device: String,
        storage: String,
    ) -> Result<Arc<ReviewSession>, CompanionError> {
        let base = tokito_companion_core::conversations::origin(&self.base_url)?;
        let identity = crate::identity::identity_store(&storage)?;
        let account = runtime().block_on(identity.resolve(&base, &token))?.account;
        let owner = format!("{base}|{}", account.email.to_lowercase());
        let hash = format!("{:x}", Sha256::digest(owner.as_bytes()));
        let store = ReviewStore::open(
            &Path::new(&storage).join(format!("reviews-{hash}.sqlite3")),
            &owner,
        )?;
        Ok(Arc::new(ReviewSession {
            base,
            token,
            device,
            store,
            closed: AtomicBool::new(false),
            registered: AtomicBool::new(false),
            identity_path: storage,
        }))
    }
}
impl ReviewSession {
    fn failure(&self, error: tokito_companion_core::CloudError) -> CompanionError {
        if matches!(error, tokito_companion_core::CloudError::Unauthorized) {
            self.closed.store(true, Ordering::Release);
            if let Ok(identity) = crate::identity::identity_store(&self.identity_path) {
                let _ = identity.revoke(&self.base, &self.token);
            }
        }
        error.into()
    }
    fn ensure_registered(&self) -> Result<(), CompanionError> {
        self.ensure_open()?;
        if !self.registered.load(Ordering::Acquire) {
            runtime()
                .block_on(tokito_companion_core::register_device(
                    &self.base,
                    &self.token,
                    &self.device,
                    "Tokito app",
                    "android",
                ))
                .map_err(|error| self.failure(error))?;
            self.registered.store(true, Ordering::Release);
        }
        Ok(())
    }
    fn ensure_open(&self) -> Result<(), CompanionError> {
        if self.closed.load(Ordering::Acquire) {
            Err(CompanionError::Unauthorized)
        } else {
            Ok(())
        }
    }
    fn replay(&self, project: &str, design: &str) -> Result<(), CompanionError> {
        for command in self
            .store
            .list(project, design)?
            .into_iter()
            .filter(|c| c.state == "pending")
        {
            self.ensure_open()?;
            self.ensure_registered()?;
            let (state, result) = runtime()
                .block_on(api::submit(&self.base, &self.token, &command))
                .map_err(|error| self.failure(error))?;
            self.store
                .finish(&command.request.command_id, state, &result)?;
        }
        Ok(())
    }
}
#[uniffi::export]
impl ReviewSession {
    pub fn shutdown(&self) {
        self.closed.store(true, Ordering::Release);
    }
    pub fn local_commands(
        &self,
        project: String,
        design: String,
    ) -> Result<String, CompanionError> {
        self.ensure_open()?;
        serde_json::to_string(&self.store.list(&project, &design)?)
            .map_err(|_| CompanionError::Unexpected)
    }
    pub fn load(&self, project: String, design: String) -> Result<String, CompanionError> {
        self.ensure_open()?;
        self.replay(&project, &design)?;
        let proposals = runtime()
            .block_on(api::list(&self.base, &self.token, &project, &design))
            .map_err(|error| self.failure(error))?;
        Ok(serde_json::json!({"proposals":proposals["proposals"],"commands":self.store.list(&project,&design)?}).to_string())
    }
    pub fn decide(
        &self,
        project: String,
        design: String,
        run: String,
        event_seq: u64,
        action: String,
        selected_json: String,
    ) -> Result<String, CompanionError> {
        self.ensure_open()?;
        if selected_json.len() > 50000 {
            return Err(CompanionError::Unexpected);
        }
        let selected_indices =
            serde_json::from_str(&selected_json).map_err(|_| CompanionError::Unexpected)?;
        let command = self.store.enqueue(
            &project,
            &design,
            &action,
            ReviewRequest {
                command_id: uuid::Uuid::new_v4().to_string(),
                device_id: self.device.clone(),
                run_id: run,
                event_seq,
                selected_indices,
            },
        )?;
        self.ensure_open()?;
        self.ensure_registered()?;
        let (state, result) = runtime()
            .block_on(api::submit(&self.base, &self.token, &command))
            .map_err(|error| self.failure(error))?;
        self.store
            .finish(&command.request.command_id, state, &result)?;
        Ok(serde_json::json!({"state":state,"receipt":result}).to_string())
    }
}
