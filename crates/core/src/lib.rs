//! Tokito Companion core — typed clients for Tokito Cloud (`tokito-ai`).
//!
//! Auth wire contract matches `tokito-ai/docs/API.md` and the desktop
//! `tokito::services::cloud` client. Companion sync routes (`/v1/companion/*`)
//! are specified in `docs/API_CONTRACT.md` and land in later milestones;
//! they are not stubbed here.

#![forbid(unsafe_code)]

pub mod auth;
pub mod base_url;
pub mod error;

pub use auth::{me, oauth_exchange, otp_request, otp_verify, CloudAccount, CloudSession};
pub use base_url::{resolve_base_url, DEFAULT_CLOUD_BASE};
pub use error::CloudError;
