//! Previously server-verified identity for private offline caches only.
//! This never authorizes server operations or trusts an email supplied by UI.
use crate::{CloudAccount, CloudError};
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use rusqlite::{params, Connection, OptionalExtension};
use sha2::{Digest, Sha256};
use std::path::Path;

const MAX_OFFLINE_AGE: u64 = 24 * 60 * 60;

pub struct VerifiedIdentityStore {
    connection: Connection,
}

impl VerifiedIdentityStore {
    pub fn open(path: &Path) -> Result<Self, CloudError> {
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent).map_err(|_| CloudError::Unexpected)?;
        }
        let connection = Connection::open(path).map_err(db_error)?;
        connection
            .pragma_update(None, "journal_mode", "WAL")
            .map_err(db_error)?;
        connection
            .pragma_update(None, "synchronous", "FULL")
            .map_err(db_error)?;
        connection
            .busy_timeout(std::time::Duration::from_secs(5))
            .map_err(db_error)?;
        connection.execute_batch("CREATE TABLE IF NOT EXISTS verified_identity(origin TEXT NOT NULL,fingerprint TEXT NOT NULL,email TEXT NOT NULL,plan TEXT NOT NULL,verified_at INTEGER NOT NULL,expires_at INTEGER NOT NULL,PRIMARY KEY(origin,fingerprint));").map_err(db_error)?;
        Ok(Self { connection })
    }

    /// Call only with a successful account response from this exact origin/token.
    /// JWT decoding bounds local retention; it is not signature verification.
    fn remember(
        &self,
        origin: &str,
        token: &str,
        account: &CloudAccount,
        now: u64,
    ) -> Result<(), CloudError> {
        let Some(expiry) = token_expiry(token).filter(|expiry| *expiry > now) else {
            return Ok(());
        };
        let expiry = expiry.min(now.saturating_add(MAX_OFFLINE_AGE));
        if now > i64::MAX as u64 || expiry > i64::MAX as u64 || account.email.is_empty() {
            return Err(CloudError::Unexpected);
        }
        self.connection
            .execute(
                "DELETE FROM verified_identity WHERE expires_at<=?1",
                params![now as i64],
            )
            .map_err(db_error)?;
        self.connection.execute("INSERT INTO verified_identity VALUES(?1,?2,?3,?4,?5,?6) ON CONFLICT(origin,fingerprint) DO UPDATE SET email=excluded.email,plan=excluded.plan,verified_at=excluded.verified_at,expires_at=excluded.expires_at", params![origin,fingerprint(token),account.email.to_lowercase(),account.plan,now as i64,expiry as i64]).map_err(db_error)?;
        Ok(())
    }

    fn cached(
        &self,
        origin: &str,
        token: &str,
        now: u64,
    ) -> Result<Option<CloudAccount>, CloudError> {
        if now > i64::MAX as u64 {
            return Ok(None);
        }
        self.connection.query_row("SELECT email,plan FROM verified_identity WHERE origin=?1 AND fingerprint=?2 AND verified_at<=?3 AND expires_at>?3", params![origin,fingerprint(token),now as i64], |row| Ok(CloudAccount {email:row.get(0)?,plan:row.get(1)?})).optional().map_err(db_error)
    }

    pub fn revoke(&self, origin: &str, token: &str) -> Result<(), CloudError> {
        let origin = crate::conversations::origin(origin)?;
        self.connection
            .execute(
                "DELETE FROM verified_identity WHERE origin=?1 AND fingerprint=?2",
                params![origin, fingerprint(token)],
            )
            .map_err(db_error)?;
        Ok(())
    }

    /// A transport outage may reopen only an exact previously verified binding.
    /// Explicit rejection always revokes it; malformed responses fail closed.
    pub async fn resolve(&self, origin: &str, token: &str) -> Result<VerifiedIdentity, CloudError> {
        let origin = crate::conversations::origin(origin)?;
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map_err(|_| CloudError::Unexpected)?
            .as_secs();
        match crate::me(&origin, token).await {
            Ok(account) => {
                self.remember(&origin, token, &account, now)?;
                Ok(VerifiedIdentity {
                    account,
                    online: true,
                })
            }
            Err(CloudError::Unauthorized) => {
                self.revoke(&origin, token)?;
                Err(CloudError::Unauthorized)
            }
            Err(error @ (CloudError::Network(_) | CloudError::Unavailable)) => {
                match self.cached(&origin, token, now)? {
                    Some(account) => Ok(VerifiedIdentity {
                        account,
                        online: false,
                    }),
                    None => Err(error),
                }
            }
            Err(error) => Err(error),
        }
    }
}

pub struct VerifiedIdentity {
    pub account: CloudAccount,
    pub online: bool,
}
fn fingerprint(token: &str) -> String {
    format!("{:x}", Sha256::digest(token.as_bytes()))
}
fn db_error(_: rusqlite::Error) -> CloudError {
    CloudError::Unexpected
}
fn token_expiry(token: &str) -> Option<u64> {
    if token.len() > 16384 {
        return None;
    }
    let mut pieces = token.split('.');
    pieces.next()?;
    let payload = pieces.next()?;
    pieces.next()?;
    if pieces.next().is_some() {
        return None;
    }
    let bytes = URL_SAFE_NO_PAD.decode(payload).ok()?;
    serde_json::from_slice::<serde_json::Value>(&bytes)
        .ok()?
        .get("exp")?
        .as_u64()
}

#[cfg(test)]
mod tests {
    use super::*;
    use wiremock::matchers::{header, method};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    #[tokio::test]
    async fn http_verification_outage_and_rejection_are_distinct() {
        let server = MockServer::start().await;
        let dir =
            std::env::temp_dir().join(format!("tokito-identity-http-{}", uuid::Uuid::new_v4()));
        let path = dir.join("identity.sqlite3");
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_secs();
        let token = token(now + 3600);
        let response = |status| {
            Mock::given(method("GET"))
                .and(wiremock::matchers::path("/v1/auth/me"))
                .and(header("authorization", format!("Bearer {token}")))
                .respond_with(ResponseTemplate::new(status))
        };
        response(503).mount(&server).await;
        let store = VerifiedIdentityStore::open(&path).unwrap();
        assert!(matches!(
            store.resolve(&server.uri(), &token).await,
            Err(CloudError::Unavailable)
        ));
        server.reset().await;
        Mock::given(method("GET"))
            .and(wiremock::matchers::path("/v1/auth/me"))
            .and(header("authorization", format!("Bearer {token}")))
            .respond_with(
                ResponseTemplate::new(200)
                    .set_body_json(serde_json::json!({"email":"alice@example.test","plan":"free"})),
            )
            .mount(&server)
            .await;
        assert!(store.resolve(&server.uri(), &token).await.unwrap().online);
        drop(store);
        let store = VerifiedIdentityStore::open(&path).unwrap();
        server.reset().await;
        response(503).mount(&server).await;
        let cached = store.resolve(&server.uri(), &token).await.unwrap();
        assert!(!cached.online);
        assert_eq!(cached.account.email, "alice@example.test");
        server.reset().await;
        response(200).mount(&server).await;
        assert!(matches!(
            store.resolve(&server.uri(), &token).await,
            Err(CloudError::Unexpected)
        ));
        server.reset().await;
        response(401).mount(&server).await;
        assert!(matches!(
            store.resolve(&server.uri(), &token).await,
            Err(CloudError::Unauthorized)
        ));
        server.reset().await;
        response(503).mount(&server).await;
        assert!(matches!(
            store.resolve(&server.uri(), &token).await,
            Err(CloudError::Unavailable)
        ));
        drop(store);
        std::fs::remove_dir_all(dir).unwrap();
    }
    fn token(exp: u64) -> String {
        format!(
            "header.{}.signature",
            URL_SAFE_NO_PAD.encode(serde_json::json!({"exp":exp}).to_string())
        )
    }
    #[test]
    fn local_retention_is_bounded_and_never_stores_the_bearer() {
        let store = VerifiedIdentityStore::open(Path::new(":memory:")).unwrap();
        let account = CloudAccount {
            email: "alice@example.test".into(),
            plan: "free".into(),
        };
        let bearer = token(100 + 10 * MAX_OFFLINE_AGE);
        store
            .remember("https://api.example.test", &bearer, &account, 100)
            .unwrap();
        assert!(store
            .cached(
                "https://api.example.test",
                &bearer,
                100 + MAX_OFFLINE_AGE - 1
            )
            .unwrap()
            .is_some());
        assert!(store
            .cached("https://api.example.test", &bearer, 100 + MAX_OFFLINE_AGE)
            .unwrap()
            .is_none());
        let stored: String = store
            .connection
            .query_row("SELECT fingerprint FROM verified_identity", [], |row| {
                row.get(0)
            })
            .unwrap();
        assert_eq!(stored, fingerprint(&bearer));
        assert!(!stored.contains(&bearer));
        for invalid in ["opaque", "header.invalid.signature", "a.b.c.d"] {
            store
                .remember("https://api.example.test", invalid, &account, 100)
                .unwrap();
            assert!(store
                .cached("https://api.example.test", invalid, 101)
                .unwrap()
                .is_none());
        }
    }
    #[test]
    fn verified_binding_survives_restart_but_not_token_origin_or_expiry_changes() {
        let dir = std::env::temp_dir().join(format!("tokito-identity-{}", uuid::Uuid::new_v4()));
        let path = dir.join("identity.sqlite3");
        let token = token(200);
        let account = CloudAccount {
            email: "alice@example.test".into(),
            plan: "free".into(),
        };
        {
            let store = VerifiedIdentityStore::open(&path).unwrap();
            assert!(store
                .cached("https://api.example.test", &token, 100)
                .unwrap()
                .is_none());
            store
                .remember("https://api.example.test", &token, &account, 100)
                .unwrap();
        }
        {
            let store = VerifiedIdentityStore::open(&path).unwrap();
            assert_eq!(
                store
                    .cached("https://api.example.test", &token, 101)
                    .unwrap(),
                Some(account)
            );
            assert!(store
                .cached("https://other.example.test", &token, 101)
                .unwrap()
                .is_none());
            assert!(store
                .cached("https://api.example.test", "different", 101)
                .unwrap()
                .is_none());
            assert!(store
                .cached("https://api.example.test", &token, 99)
                .unwrap()
                .is_none());
            assert!(store
                .cached("https://api.example.test", &token, 200)
                .unwrap()
                .is_none());
            store.revoke("https://api.example.test", &token).unwrap();
            assert!(store
                .cached("https://api.example.test", &token, 101)
                .unwrap()
                .is_none());
        }
        std::fs::remove_dir_all(dir).unwrap();
    }
}
