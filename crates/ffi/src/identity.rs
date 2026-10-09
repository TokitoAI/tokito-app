//! Server-verified identity recovery for private offline workspaces.
use crate::{runtime, Account, CompanionClient, CompanionError};
use std::path::Path;
use tokito_companion_core::verified_identity::VerifiedIdentityStore;

#[derive(Debug, Clone, uniffi::Record)]
pub struct ResolvedIdentity {
    pub account: Account,
    pub online: bool,
}

#[uniffi::export]
impl CompanionClient {
    pub fn resolve_identity(
        &self,
        token: String,
        state_path: String,
    ) -> Result<ResolvedIdentity, CompanionError> {
        let store = identity_store(&state_path)?;
        let identity = runtime().block_on(store.resolve(&self.base_url, &token))?;
        Ok(ResolvedIdentity {
            account: Account {
                email: identity.account.email,
                plan: identity.account.plan,
            },
            online: identity.online,
        })
    }

    pub fn forget_identity(&self, token: String, state_path: String) -> Result<(), CompanionError> {
        identity_store(&state_path)?.revoke(&self.base_url, &token)?;
        Ok(())
    }
}

pub(crate) fn identity_store(state_path: &str) -> Result<VerifiedIdentityStore, CompanionError> {
    if state_path.trim().is_empty() {
        return Err(CompanionError::Unexpected);
    }
    let path = Path::new(state_path);
    if !path.is_absolute() {
        return Err(CompanionError::Unexpected);
    }
    Ok(VerifiedIdentityStore::open(
        &path.with_file_name("verified-identity.sqlite3"),
    )?)
}
