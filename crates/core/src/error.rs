//! Failure modes for Tokito Cloud auth calls.
//!
//! Variants mirror desktop `tokito::services::cloud::CloudError` so UI copy
//! and retry policy stay aligned across desktop and companion.

use thiserror::Error;

#[derive(Debug, Error, PartialEq, Eq)]
pub enum CloudError {
    /// Under the 60s per-email OTP cooldown.
    #[error("wait a moment before requesting another code")]
    Cooldown,
    /// More than 5 OTP requests for this email in the last hour.
    #[error("too many code requests; try again later")]
    RateLimited,
    /// The mailer failed to deliver the code.
    #[error("could not send the code; try again")]
    SendFailed,
    /// Wrong, expired, or already-used OTP / pairing code.
    #[error("that code is invalid or expired")]
    InvalidCode,
    /// Missing/expired bearer — the session is no longer valid.
    #[error("session expired")]
    Unauthorized,
    /// The route is disabled server-side (auth not configured).
    #[error("this sign-in method is not available right now")]
    Unavailable,
    /// Network / transport failure.
    #[error("network error: {0}")]
    Network(String),
    /// Unexpected status or malformed body.
    #[error("unexpected server response")]
    Unexpected,
}
