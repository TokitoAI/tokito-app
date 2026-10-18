//! Failure modes for Tokito Cloud auth calls.
//!
//! Stable Cloud error mapping owned by this client.

use thiserror::Error;

#[derive(Debug, Error, PartialEq, Eq)]
pub enum CloudError {
    /// Under the per-email OTP request cooldown.
    #[error("Please wait {} before requesting another code.", cooldown_wait(*.retry_after_secs))]
    Cooldown { retry_after_secs: Option<u64> },
    /// Too many OTP requests (hourly cap) or a generic 429.
    #[error("Too many code requests. Try again {}.", retry_clause(*.retry_after_secs))]
    RateLimited { retry_after_secs: Option<u64> },
    /// Per-IP cap on OTP verify attempts.
    #[error("Too many attempts from this network. Try again {}.", retry_clause(*.retry_after_secs))]
    NetworkRateLimited { retry_after_secs: Option<u64> },
    /// Per-email daily cap on failed OTP attempts.
    #[error("Too many failed attempts for this email. Sign in with Google or GitHub, or try again {}.", retry_clause(*.retry_after_secs))]
    DailyCapReached { retry_after_secs: Option<u64> },
    /// The OTP was burned by too many wrong attempts.
    #[error("Too many attempts on that code. Request a new code.")]
    CodeBurned,
    /// Authenticated but not permitted (for example not an org member).
    #[error("You don't have access to this. Ask an admin of the organization.")]
    Forbidden,
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
    /// Optimistic resource version was stale. Payload is canonical server JSON.
    #[error("resource changed on another device (version {current_version})")]
    Conflict {
        current_version: u64,
        current_payload: String,
    },
    /// Unexpected status or malformed body.
    #[error("unexpected server response")]
    Unexpected,
}

/// Human duration, rounded up: seconds under a minute, minutes under an hour,
/// otherwise hours plus leftover minutes.
pub fn format_duration(total_secs: u64) -> String {
    fn plural(n: u64, unit: &str) -> String {
        format!("{n} {unit}{}", if n == 1 { "" } else { "s" })
    }
    let secs = total_secs.max(1);
    if secs < 60 {
        return plural(secs, "second");
    }
    let minutes = secs.div_ceil(60);
    if minutes < 60 {
        return plural(minutes, "minute");
    }
    let (hours, rest) = (minutes / 60, minutes % 60);
    if rest == 0 {
        plural(hours, "hour")
    } else {
        format!("{} {}", plural(hours, "hour"), plural(rest, "minute"))
    }
}

fn retry_clause(secs: Option<u64>) -> String {
    match secs {
        Some(s) => format!("in {}", format_duration(s)),
        None => "later".into(),
    }
}

fn cooldown_wait(secs: Option<u64>) -> String {
    secs.map_or_else(|| "a moment".into(), format_duration)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn durations_round_up_and_pluralise() {
        assert_eq!(format_duration(0), "1 second");
        assert_eq!(format_duration(1), "1 second");
        assert_eq!(format_duration(45), "45 seconds");
        assert_eq!(format_duration(61), "2 minutes");
        assert_eq!(format_duration(3600), "1 hour");
        assert_eq!(format_duration(5400), "1 hour 30 minutes");
    }

    #[test]
    fn messages_say_what_to_do_next() {
        assert_eq!(
            CloudError::Cooldown {
                retry_after_secs: Some(30)
            }
            .to_string(),
            "Please wait 30 seconds before requesting another code."
        );
        assert_eq!(
            CloudError::DailyCapReached { retry_after_secs: None }.to_string(),
            "Too many failed attempts for this email. Sign in with Google or GitHub, or try again later."
        );
        assert_eq!(
            CloudError::NetworkRateLimited {
                retry_after_secs: Some(120)
            }
            .to_string(),
            "Too many attempts from this network. Try again in 2 minutes."
        );
    }
}
