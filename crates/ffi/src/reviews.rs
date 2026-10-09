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

#[derive(uniffi::Object)]
pub struct ReviewSession {
    base: String,
    token: String,
    device: String,
    store: ReviewStore,
    closed: AtomicBool,
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
        let account = runtime().block_on(tokito_companion_core::me(&base, &token))?;
        runtime().block_on(tokito_companion_core::register_device(
            &base,
            &token,
            &device,
            "Tokito app",
            "android",
        ))?;
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
        }))
    }
}
impl ReviewSession {
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
            let (state, result) =
                runtime().block_on(api::submit(&self.base, &self.token, &command))?;
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
        let proposals =
            runtime().block_on(api::list(&self.base, &self.token, &project, &design))?;
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
        let (state, result) = runtime().block_on(api::submit(&self.base, &self.token, &command))?;
        self.store
            .finish(&command.request.command_id, state, &result)?;
        Ok(serde_json::json!({"state":state,"receipt":result}).to_string())
    }
}
