//! Cloud base URL resolution for this independent client.

/// Default Tokito Cloud base URL.
pub const DEFAULT_CLOUD_BASE: &str = "https://api.tokito.dev";

/// Resolve the active cloud base URL: explicit override wins, else
/// `TOKITO_CLOUD_BASE_URL`, else [`DEFAULT_CLOUD_BASE`]. Never ends with `/`.
pub fn resolve_base_url(override_base: &str) -> String {
    let raw = if !override_base.trim().is_empty() {
        override_base.trim().to_string()
    } else if let Ok(env) = std::env::var("TOKITO_CLOUD_BASE_URL") {
        if env.trim().is_empty() {
            DEFAULT_CLOUD_BASE.to_string()
        } else {
            env.trim().to_string()
        }
    } else {
        DEFAULT_CLOUD_BASE.to_string()
    };
    raw.trim_end_matches('/').to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn override_wins() {
        assert_eq!(
            resolve_base_url("https://example.test/v1/"),
            "https://example.test/v1"
        );
    }

    #[test]
    fn empty_override_uses_default() {
        // Clear env interference for this assertion when possible.
        let _guard = EnvGuard::clear("TOKITO_CLOUD_BASE_URL");
        assert_eq!(resolve_base_url(""), DEFAULT_CLOUD_BASE);
        assert_eq!(resolve_base_url("   "), DEFAULT_CLOUD_BASE);
    }

    struct EnvGuard {
        key: &'static str,
        prev: Option<String>,
    }

    impl EnvGuard {
        fn clear(key: &'static str) -> Self {
            let prev = std::env::var(key).ok();
            std::env::remove_var(key);
            Self { key, prev }
        }
    }

    impl Drop for EnvGuard {
        fn drop(&mut self) {
            match &self.prev {
                Some(v) => std::env::set_var(self.key, v),
                None => std::env::remove_var(self.key),
            }
        }
    }
}
