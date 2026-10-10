//! Bounded, account/origin-scoped immutable drawings. Identity is verified by the
//! caller before opening; cached content never authorizes network operations.
use super::{validate, SchematicPreview};
use crate::CloudError;
use rusqlite::{params, Connection, OptionalExtension};
use sha2::{Digest, Sha256};
use std::path::Path;

pub struct PreviewCache {
    conn: Connection,
    scope: String,
}
impl PreviewCache {
    pub fn open(path: &Path, origin: &str, verified_email: &str) -> Result<Self, CloudError> {
        if verified_email.is_empty() {
            return Err(CloudError::Unauthorized);
        }
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent).map_err(|_| CloudError::Unexpected)?;
        }
        let scope = format!(
            "{:x}",
            Sha256::digest(
                serde_json::to_vec(&(
                    crate::conversations::origin(origin)?,
                    verified_email.to_lowercase()
                ))
                .map_err(|_| CloudError::Unexpected)?
            )
        );
        let conn = Connection::open(path).map_err(db)?;
        conn.pragma_update(None, "journal_mode", "WAL")
            .map_err(db)?;
        conn.busy_timeout(std::time::Duration::from_secs(5))
            .map_err(db)?;
        conn.execute_batch("CREATE TABLE IF NOT EXISTS previews(scope TEXT NOT NULL,project TEXT NOT NULL,design TEXT NOT NULL,sheet TEXT NOT NULL,version INTEGER NOT NULL,body TEXT NOT NULL,used INTEGER NOT NULL,PRIMARY KEY(scope,project,design,sheet,version));").map_err(db)?;
        Ok(Self { conn, scope })
    }
    pub fn get(
        &self,
        project: &str,
        design: &str,
        sheet: &str,
        version: u64,
    ) -> Result<Option<SchematicPreview>, CloudError> {
        let body:Option<String>=self.conn.query_row("SELECT body FROM previews WHERE scope=?1 AND project=?2 AND design=?3 AND sheet=?4 AND version=?5",params![self.scope,project,design,sheet,version],|r|r.get(0)).optional().map_err(db)?;
        body.map(|body| {
            let preview:SchematicPreview=serde_json::from_str(&body).map_err(|_|CloudError::Unexpected)?;
            validate(&preview,project,design,version,sheet)?;
            self.conn.execute("UPDATE previews SET used=?6 WHERE scope=?1 AND project=?2 AND design=?3 AND sheet=?4 AND version=?5",params![self.scope,project,design,sheet,version,now()]).map_err(db)?;
            Ok(preview)
        }).transpose()
    }
    pub fn put(&mut self, preview: &SchematicPreview) -> Result<(), CloudError> {
        validate(
            preview,
            &preview.project_id,
            &preview.design_id,
            preview.schematic_version,
            &preview.sheet_id,
        )?;
        let body = serde_json::to_string(preview).map_err(|_| CloudError::Unexpected)?;
        let tx = self.conn.transaction().map_err(db)?;
        let previous:Option<String>=tx.query_row("SELECT body FROM previews WHERE scope=?1 AND project=?2 AND design=?3 AND sheet=?4 AND version=?5",params![self.scope,preview.project_id,preview.design_id,preview.sheet_id,preview.schematic_version],|r|r.get(0)).optional().map_err(db)?;
        if let Some(previous) = previous {
            let previous: SchematicPreview =
                serde_json::from_str(&previous).map_err(|_| CloudError::Unexpected)?;
            if previous.source_sha256 != preview.source_sha256 {
                return Err(CloudError::Unexpected);
            }
        }
        tx.execute("INSERT INTO previews VALUES(?1,?2,?3,?4,?5,?6,?7) ON CONFLICT(scope,project,design,sheet,version) DO UPDATE SET body=excluded.body,used=excluded.used",params![self.scope,preview.project_id,preview.design_id,preview.sheet_id,preview.schematic_version,body,now()]).map_err(db)?;
        tx.execute("DELETE FROM previews WHERE rowid NOT IN (SELECT rowid FROM previews ORDER BY used DESC,rowid DESC LIMIT 16)",[]).map_err(db)?;
        loop {
            let bytes: i64 = tx
                .query_row(
                    "SELECT COALESCE(SUM(length(CAST(body AS BLOB))),0) FROM previews",
                    [],
                    |r| r.get(0),
                )
                .map_err(db)?;
            if bytes <= 32 * 1024 * 1024 {
                break;
            }
            tx.execute("DELETE FROM previews WHERE rowid=(SELECT rowid FROM previews ORDER BY used,rowid LIMIT 1)",[]).map_err(db)?;
        }
        tx.commit().map_err(db)
    }
}
fn db(_: rusqlite::Error) -> CloudError {
    CloudError::Unexpected
}
fn now() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis()
        .min(i64::MAX as u128) as i64
}
#[cfg(test)]
mod tests {
    use super::*;
    fn preview(version: u64) -> SchematicPreview {
        let svg = "<svg/>".to_owned();
        SchematicPreview {
            project_id: "p".into(),
            design_id: "d".into(),
            sheet_id: "root".into(),
            schematic_version: version,
            source_sha256: "a".repeat(64),
            sha256: format!("{:x}", Sha256::digest(svg.as_bytes())),
            svg,
        }
    }
    #[test]
    fn restart_scope_integrity_and_bounded_eviction() {
        let path = std::env::temp_dir().join(format!("preview-{}.sqlite3", uuid::Uuid::new_v4()));
        let mut cache = PreviewCache::open(&path, "https://api.example", "one@example").unwrap();
        cache.put(&preview(1)).unwrap();
        drop(cache);
        let mut cache = PreviewCache::open(&path, "https://api.example", "one@example").unwrap();
        assert!(cache.get("p", "d", "root", 1).unwrap().is_some());
        assert!(
            PreviewCache::open(&path, "https://api.example", "two@example")
                .unwrap()
                .get("p", "d", "root", 1)
                .unwrap()
                .is_none()
        );
        assert!(
            PreviewCache::open(&path, "https://other.example", "one@example")
                .unwrap()
                .get("p", "d", "root", 1)
                .unwrap()
                .is_none()
        );
        for version in 2..=20 {
            cache.put(&preview(version)).unwrap();
        }
        assert!(cache.get("p", "d", "root", 1).unwrap().is_none());
        assert!(cache.get("p", "d", "root", 20).unwrap().is_some());
        let mut changed = preview(20);
        changed.svg = "<svg>changed</svg>".into();
        changed.sha256 = format!("{:x}", Sha256::digest(changed.svg.as_bytes()));
        cache.put(&changed).unwrap();
        assert_eq!(
            cache.get("p", "d", "root", 20).unwrap().unwrap().svg,
            changed.svg
        );
        changed.source_sha256 = "b".repeat(64);
        assert!(cache.put(&changed).is_err());
        cache
            .conn
            .execute(
                "UPDATE previews SET body=replace(body,'<svg>changed</svg>','<svg>bad</svg>') WHERE version=20",
                [],
            )
            .unwrap();
        assert!(cache.get("p", "d", "root", 20).is_err());
        drop(cache);
        std::fs::remove_file(path).unwrap();
    }
}
