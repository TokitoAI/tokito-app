//! Tokito Companion core — typed clients for Tokito Cloud (`tokito-api`).
//!
//! Wire contracts are owned by `tokito-api/docs/API.md` and
//! `docs/API_CONTRACT.md`. This crate has no Tokito Desktop dependency.

#![forbid(unsafe_code)]

pub mod auth;
pub mod base_url;
pub mod conversation_store;
pub mod conversations;
pub mod error;
pub mod preview;
pub mod realtime;
pub mod reviews;
mod sse;
pub mod sync;
pub mod sync_state;
pub mod verified_identity;

pub use auth::{me, oauth_exchange, otp_request, otp_verify, CloudAccount, CloudSession};
pub use base_url::{resolve_base_url, DEFAULT_CLOUD_BASE};
pub use error::CloudError;
pub use realtime::{
    bootstrap_realtime, register_device, run_event_stream, submit_sync_command, RealtimeStatus,
    SyncBootstrap, SyncCommand, SyncDevice, SyncEvent, SyncResource,
};
pub use sync::{
    decide_proposal, get_artifact, get_project, list_projects, list_proposals, put_project,
    ArtifactMeta, BomLine, DesignSummary, ProjectDetail, ProjectListItem, ProjectSnapshot,
    Proposal,
};
pub use sync_state::SyncStateStore;

#[cfg(test)]
mod product_boundary_tests {
    use std::{fs, path::Path};

    fn visit(path: &Path, files: &mut Vec<std::path::PathBuf>) {
        for entry in fs::read_dir(path).expect("read workspace tree") {
            let path = entry.expect("read entry").path();
            if path.is_dir() {
                if !matches!(
                    path.file_name().and_then(|v| v.to_str()),
                    Some("target" | "node_modules" | ".git")
                ) {
                    visit(&path, files);
                }
            } else if matches!(
                path.file_name().and_then(|v| v.to_str()),
                Some("Cargo.toml" | "package.json")
            ) {
                files.push(path);
            }
        }
    }

    #[test]
    fn companion_has_no_direct_desktop_repository_dependency_50() {
        let root = Path::new(env!("CARGO_MANIFEST_DIR"))
            .parent()
            .and_then(Path::parent)
            .expect("workspace root");
        let mut manifests = Vec::new();
        visit(root, &mut manifests);
        let forbidden = [
            ["TokitoAI", "/tokito.git"].concat(),
            ["TokitoAI", "/tokito#"].concat(),
            ["path", " = \"../tokito\""].concat(),
            ["path", " = \"../../tokito\""].concat(),
        ];
        for path in manifests {
            let text = fs::read_to_string(&path).expect("read manifest");
            for needle in &forbidden {
                assert!(
                    !text.contains(needle),
                    "{} directly couples Companion to Desktop via {needle:?}",
                    path.display()
                );
            }
        }
    }
}
