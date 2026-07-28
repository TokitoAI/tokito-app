//! Tokito Companion core — typed clients for Tokito Cloud (`tokito-ai`).
//!
//! Auth wire contract matches `tokito-ai/docs/API.md` and the desktop
//! `tokito::services::cloud` client. Companion sync routes (`/v1/companion/*`)
//! match `docs/API_CONTRACT.md` / `tokito-ai` companion crate.

#![forbid(unsafe_code)]

pub mod auth;
pub mod base_url;
pub mod error;
pub mod sync;

pub use auth::{me, oauth_exchange, otp_request, otp_verify, CloudAccount, CloudSession};
pub use base_url::{resolve_base_url, DEFAULT_CLOUD_BASE};
pub use error::CloudError;
pub use sync::{
    decide_proposal, get_project, list_projects, list_proposals, put_project, ArtifactMeta,
    BomLine, DesignSummary, ProjectDetail, ProjectListItem, ProjectSnapshot, Proposal,
};
