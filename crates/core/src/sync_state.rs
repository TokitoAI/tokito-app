//! Crash-safe local cursor, materialized resources, and command outbox.

use std::path::Path;
use std::sync::Mutex;

use rusqlite::{params, Connection, OptionalExtension};

use crate::{CloudError, SyncBootstrap, SyncCommand, SyncEvent, SyncResource};

const MAX_RESOURCE_BYTES: usize = 2 * 1024 * 1024;

pub struct SyncStateStore {
    conn: Mutex<Connection>,
}

impl SyncStateStore {
    pub fn open(path: &Path) -> Result<Self, CloudError> {
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)
                .map_err(|error| CloudError::Network(error.to_string()))?;
        }
        let conn = Connection::open(path).map_err(db_error)?;
        conn.pragma_update(None, "journal_mode", "WAL")
            .map_err(db_error)?;
        Self::init(conn)
    }

    #[cfg(test)]
    fn in_memory() -> Result<Self, CloudError> {
        Self::init(Connection::open_in_memory().map_err(db_error)?)
    }

    fn init(conn: Connection) -> Result<Self, CloudError> {
        conn.execute_batch(
            "CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
             CREATE TABLE IF NOT EXISTS resources (
               project_id TEXT NOT NULL, design_id TEXT NOT NULL, resource TEXT NOT NULL,
               version INTEGER NOT NULL, payload_json TEXT NOT NULL,
               PRIMARY KEY(project_id, design_id, resource)
             );
             CREATE TABLE IF NOT EXISTS outbox (
               command_id TEXT PRIMARY KEY, body_json TEXT NOT NULL, created_at INTEGER NOT NULL,
               blocked INTEGER NOT NULL DEFAULT 0,
               conflict_version INTEGER,
               conflict_payload_json TEXT
             );",
        )
        .map_err(db_error)?;
        Ok(Self {
            conn: Mutex::new(conn),
        })
    }

    pub fn cursor(&self) -> Result<u64, CloudError> {
        let conn = self.conn.lock().map_err(|_| CloudError::Unexpected)?;
        let value: Option<String> = conn
            .query_row("SELECT value FROM meta WHERE key = 'cursor'", [], |row| {
                row.get(0)
            })
            .optional()
            .map_err(db_error)?;
        Ok(value.and_then(|value| value.parse().ok()).unwrap_or(0))
    }

    pub fn apply_bootstrap(
        &self,
        bootstrap: &SyncBootstrap,
        project_id: Option<&str>,
    ) -> Result<(), CloudError> {
        let mut conn = self.conn.lock().map_err(|_| CloudError::Unexpected)?;
        let tx = conn.transaction().map_err(db_error)?;
        if let Some(project_id) = project_id {
            tx.execute(
                "DELETE FROM resources WHERE project_id = ?1",
                params![project_id],
            )
            .map_err(db_error)?;
        } else {
            tx.execute("DELETE FROM resources", []).map_err(db_error)?;
        }
        for resource in &bootstrap.resources {
            upsert_resource(&tx, resource)?;
        }
        set_cursor(&tx, bootstrap.cursor)?;
        tx.commit().map_err(db_error)
    }

    /// Apply one event and advance the durable cursor in the same transaction.
    pub fn apply_event(&self, event: &SyncEvent) -> Result<(), CloudError> {
        let mut conn = self.conn.lock().map_err(|_| CloudError::Unexpected)?;
        let tx = conn.transaction().map_err(db_error)?;
        upsert_resource(
            &tx,
            &SyncResource {
                project_id: event.project_id.clone(),
                design_id: event.design_id.clone(),
                resource: event.resource.clone(),
                version: event.version,
                payload: event.payload.clone(),
            },
        )?;
        set_cursor(&tx, event.seq)?;
        tx.execute(
            "DELETE FROM outbox WHERE command_id = ?1",
            params![event.command_id],
        )
        .map_err(db_error)?;
        tx.commit().map_err(db_error)
    }

    /// Materialize an HTTP command acknowledgement without advancing the SSE cursor.
    /// The journal remains the only cursor authority, so a concurrent stream can
    /// still replay every earlier event in global sequence order.
    pub fn apply_ack(&self, event: &SyncEvent) -> Result<(), CloudError> {
        let mut conn = self.conn.lock().map_err(|_| CloudError::Unexpected)?;
        let tx = conn.transaction().map_err(db_error)?;
        upsert_resource(
            &tx,
            &SyncResource {
                project_id: event.project_id.clone(),
                design_id: event.design_id.clone(),
                resource: event.resource.clone(),
                version: event.version,
                payload: event.payload.clone(),
            },
        )?;
        tx.execute(
            "DELETE FROM outbox WHERE command_id = ?1",
            params![event.command_id],
        )
        .map_err(db_error)?;
        tx.commit().map_err(db_error)
    }

    pub fn resource(
        &self,
        project_id: &str,
        design_id: Option<&str>,
        resource: &str,
    ) -> Result<Option<SyncResource>, CloudError> {
        let conn = self.conn.lock().map_err(|_| CloudError::Unexpected)?;
        let design_id = design_id.unwrap_or("");
        conn.query_row(
            "SELECT version, payload_json FROM resources
             WHERE project_id = ?1 AND design_id = ?2 AND resource = ?3",
            params![project_id, design_id, resource],
            |row| {
                let payload: String = row.get(1)?;
                Ok(SyncResource {
                    project_id: project_id.into(),
                    design_id: (!design_id.is_empty()).then(|| design_id.into()),
                    resource: resource.into(),
                    version: row.get::<_, i64>(0)? as u64,
                    payload: serde_json::from_str(&payload).unwrap_or(serde_json::Value::Null),
                })
            },
        )
        .optional()
        .map_err(db_error)
    }

    pub fn enqueue(&self, command: &SyncCommand) -> Result<(), CloudError> {
        let body = serde_json::to_string(command).map_err(|_| CloudError::Unexpected)?;
        let payload = serde_json::to_vec(&command.payload).map_err(|_| CloudError::Unexpected)?;
        if payload.len() > MAX_RESOURCE_BYTES {
            return Err(CloudError::Network(format!(
                "sync resource exceeds {MAX_RESOURCE_BYTES} bytes"
            )));
        }
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|value| value.as_millis() as i64)
            .unwrap_or(0);
        let conn = self.conn.lock().map_err(|_| CloudError::Unexpected)?;
        conn.execute(
            "INSERT INTO outbox(command_id, body_json, created_at) VALUES (?1, ?2, ?3)
             ON CONFLICT(command_id) DO NOTHING",
            params![command.command_id, body, now],
        )
        .map_err(db_error)?;
        Ok(())
    }

    pub fn next_base_version(
        &self,
        project_id: &str,
        design_id: Option<&str>,
        resource: &str,
    ) -> Result<u64, CloudError> {
        let conn = self.conn.lock().map_err(|_| CloudError::Unexpected)?;
        let current: Option<i64> = conn
            .query_row(
                "SELECT version FROM resources
                 WHERE project_id = ?1 AND design_id = ?2 AND resource = ?3",
                params![project_id, design_id.unwrap_or(""), resource],
                |row| row.get(0),
            )
            .optional()
            .map_err(db_error)?;
        let queued: i64 = conn
            .query_row(
                "SELECT COUNT(*) FROM outbox
                 WHERE blocked = 0
                 AND json_extract(body_json, '$.project_id') = ?1
                 AND COALESCE(json_extract(body_json, '$.design_id'), '') = ?2
                 AND json_extract(body_json, '$.resource') = ?3",
                params![project_id, design_id.unwrap_or(""), resource],
                |row| row.get(0),
            )
            .map_err(db_error)?;
        Ok(current.unwrap_or(0) as u64 + queued as u64)
    }

    pub fn pending(&self) -> Result<Vec<SyncCommand>, CloudError> {
        let conn = self.conn.lock().map_err(|_| CloudError::Unexpected)?;
        let mut stmt = conn
            .prepare(
                "SELECT body_json FROM outbox
                 WHERE blocked = 0 ORDER BY rowid",
            )
            .map_err(db_error)?;
        let rows = stmt
            .query_map([], |row| row.get::<_, String>(0))
            .map_err(db_error)?;
        let mut commands = Vec::new();
        for row in rows {
            let body = row.map_err(db_error)?;
            commands.push(serde_json::from_str(&body).map_err(|_| CloudError::Unexpected)?);
        }
        Ok(commands)
    }

    pub fn mark_conflict(
        &self,
        command_id: &str,
        current_version: u64,
        current_payload: &str,
    ) -> Result<(), CloudError> {
        let mut conn = self.conn.lock().map_err(|_| CloudError::Unexpected)?;
        let tx = conn.transaction().map_err(db_error)?;
        let (rowid, body): (i64, String) = tx
            .query_row(
                "SELECT rowid, body_json FROM outbox WHERE command_id = ?1",
                params![command_id],
                |row| Ok((row.get(0)?, row.get(1)?)),
            )
            .map_err(db_error)?;
        let command: SyncCommand =
            serde_json::from_str(&body).map_err(|_| CloudError::Unexpected)?;
        tx.execute(
            "UPDATE outbox SET blocked = 1,
             conflict_version = CASE WHEN command_id = ?5 THEN ?6 ELSE conflict_version END,
             conflict_payload_json = CASE WHEN command_id = ?5 THEN ?7 ELSE conflict_payload_json END
             WHERE rowid >= ?1
             AND json_extract(body_json, '$.project_id') = ?2
             AND COALESCE(json_extract(body_json, '$.design_id'), '') = ?3
             AND json_extract(body_json, '$.resource') = ?4",
            params![rowid, command.project_id, command.design_id.unwrap_or_default(), command.resource,
                command_id, current_version as i64, current_payload],
        )
        .map_err(db_error)?;
        tx.commit().map_err(db_error)
    }

    pub fn discard_command(&self, command_id: &str) -> Result<(), CloudError> {
        let mut conn = self.conn.lock().map_err(|_| CloudError::Unexpected)?;
        let tx = conn.transaction().map_err(db_error)?;
        let row: Option<(i64, String)> = tx
            .query_row(
                "SELECT rowid, body_json FROM outbox WHERE command_id = ?1 AND blocked = 1",
                params![command_id],
                |row| Ok((row.get(0)?, row.get(1)?)),
            )
            .optional()
            .map_err(db_error)?;
        let Some((rowid, body)) = row else {
            return Err(CloudError::Unexpected);
        };
        let command: SyncCommand =
            serde_json::from_str(&body).map_err(|_| CloudError::Unexpected)?;
        tx.execute(
            "DELETE FROM outbox WHERE rowid >= ?1
             AND json_extract(body_json, '$.project_id') = ?2
             AND COALESCE(json_extract(body_json, '$.design_id'), '') = ?3
             AND json_extract(body_json, '$.resource') = ?4",
            params![
                rowid,
                command.project_id,
                command.design_id.unwrap_or_default(),
                command.resource
            ],
        )
        .map_err(db_error)?;
        tx.commit().map_err(db_error)
    }

    pub fn rebase_conflict(&self, command_id: &str) -> Result<(), CloudError> {
        let mut conn = self.conn.lock().map_err(|_| CloudError::Unexpected)?;
        let tx = conn.transaction().map_err(db_error)?;
        let row: Option<(i64, String, i64)> = tx
            .query_row(
                "SELECT rowid, body_json, conflict_version FROM outbox
                 WHERE command_id = ?1 AND blocked = 1",
                params![command_id],
                |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
            )
            .optional()
            .map_err(db_error)?;
        let Some((conflicted_rowid, body, version)) = row else {
            return Err(CloudError::Unexpected);
        };
        let mut command: SyncCommand =
            serde_json::from_str(&body).map_err(|_| CloudError::Unexpected)?;
        let project_id = command.project_id.clone();
        let design_id = command.design_id.clone().unwrap_or_default();
        let resource = command.resource.clone();
        let mut queued = Vec::new();
        {
            let mut stmt = tx
                .prepare(
                    "SELECT rowid, body_json FROM outbox
                     WHERE rowid >= ?1
                     AND json_extract(body_json, '$.project_id') = ?2
                     AND COALESCE(json_extract(body_json, '$.design_id'), '') = ?3
                     AND json_extract(body_json, '$.resource') = ?4
                     ORDER BY rowid",
                )
                .map_err(db_error)?;
            let rows = stmt
                .query_map(
                    params![conflicted_rowid, project_id, design_id, resource],
                    |row| Ok((row.get::<_, i64>(0)?, row.get::<_, String>(1)?)),
                )
                .map_err(db_error)?;
            for row in rows {
                queued.push(row.map_err(db_error)?);
            }
        }
        for (base_version, (rowid, body)) in (version as u64..).zip(queued) {
            command = serde_json::from_str(&body).map_err(|_| CloudError::Unexpected)?;
            command.base_version = base_version;
            let body = serde_json::to_string(&command).map_err(|_| CloudError::Unexpected)?;
            tx.execute(
                "UPDATE outbox SET body_json = ?2, blocked = 0,
                 conflict_version = NULL, conflict_payload_json = NULL WHERE rowid = ?1",
                params![rowid, body],
            )
            .map_err(db_error)?;
        }
        tx.commit().map_err(db_error)
    }

    pub fn resources(&self, project_id: Option<&str>) -> Result<Vec<SyncResource>, CloudError> {
        let conn = self.conn.lock().map_err(|_| CloudError::Unexpected)?;
        let sql = if project_id.is_some() {
            "SELECT project_id, design_id, resource, version, payload_json
             FROM resources WHERE project_id = ?1 ORDER BY project_id, design_id, resource"
        } else {
            "SELECT project_id, design_id, resource, version, payload_json
             FROM resources ORDER BY project_id, design_id, resource"
        };
        let mut stmt = conn.prepare(sql).map_err(db_error)?;
        let map_row = |row: &rusqlite::Row<'_>| -> rusqlite::Result<SyncResource> {
            let design_id: String = row.get(1)?;
            let payload: String = row.get(4)?;
            Ok(SyncResource {
                project_id: row.get(0)?,
                design_id: (!design_id.is_empty()).then_some(design_id),
                resource: row.get(2)?,
                version: row.get::<_, i64>(3)? as u64,
                payload: serde_json::from_str(&payload).unwrap_or(serde_json::Value::Null),
            })
        };
        let mut resources = Vec::new();
        if let Some(project_id) = project_id {
            let rows = stmt
                .query_map(params![project_id], map_row)
                .map_err(db_error)?;
            for row in rows {
                resources.push(row.map_err(db_error)?);
            }
        } else {
            let rows = stmt.query_map([], map_row).map_err(db_error)?;
            for row in rows {
                resources.push(row.map_err(db_error)?);
            }
        }
        Ok(resources)
    }
}

fn upsert_resource(conn: &Connection, resource: &SyncResource) -> Result<(), CloudError> {
    let payload = serde_json::to_string(&resource.payload).map_err(|_| CloudError::Unexpected)?;
    conn.execute(
        "INSERT INTO resources(project_id, design_id, resource, version, payload_json)
         VALUES (?1, ?2, ?3, ?4, ?5)
         ON CONFLICT(project_id, design_id, resource) DO UPDATE SET
           version=excluded.version, payload_json=excluded.payload_json
         WHERE excluded.version >= resources.version",
        params![
            resource.project_id,
            resource.design_id.as_deref().unwrap_or(""),
            resource.resource,
            resource.version as i64,
            payload
        ],
    )
    .map_err(db_error)?;
    Ok(())
}

fn set_cursor(conn: &Connection, cursor: u64) -> Result<(), CloudError> {
    conn.execute(
        "INSERT INTO meta(key, value) VALUES ('cursor', ?1)
         ON CONFLICT(key) DO UPDATE SET value=excluded.value",
        params![cursor.to_string()],
    )
    .map_err(db_error)?;
    Ok(())
}

fn db_error(error: rusqlite::Error) -> CloudError {
    CloudError::Network(format!("sync state: {error}"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn event_resource_cursor_and_outbox_commit_together() {
        let store = SyncStateStore::in_memory().unwrap();
        let command = SyncCommand {
            device_id: "d".into(),
            command_id: "c".into(),
            project_id: "p".into(),
            design_id: Some("s".into()),
            resource: "bom".into(),
            base_version: 0,
            payload: serde_json::json!({"lines":[]}),
        };
        store.enqueue(&command).unwrap();
        assert_eq!(store.pending().unwrap().len(), 1);
        store
            .apply_event(&SyncEvent {
                seq: 7,
                project_id: "p".into(),
                design_id: Some("s".into()),
                resource: "bom".into(),
                version: 1,
                command_id: "c".into(),
                actor_device_id: "d".into(),
                payload: command.payload,
                created_at: 1,
            })
            .unwrap();
        assert_eq!(store.cursor().unwrap(), 7);
        assert!(store.pending().unwrap().is_empty());
        assert_eq!(
            store
                .resource("p", Some("s"), "bom")
                .unwrap()
                .unwrap()
                .version,
            1
        );
    }

    #[test]
    fn queued_edits_reserve_sequential_base_versions_and_conflicts_block() {
        let store = SyncStateStore::in_memory().unwrap();
        let command = SyncCommand {
            device_id: "d".into(),
            command_id: "first".into(),
            project_id: "p".into(),
            design_id: Some("s".into()),
            resource: "chat".into(),
            base_version: 0,
            payload: serde_json::json!({"messages":[]}),
        };
        store.enqueue(&command).unwrap();
        let mut second = command.clone();
        second.command_id = "second".into();
        second.base_version = 1;
        second.payload = serde_json::json!({"messages":[{"content":"later"}]});
        store.enqueue(&second).unwrap();
        assert_eq!(store.next_base_version("p", Some("s"), "chat").unwrap(), 2);
        store.mark_conflict("first", 4, "{}").unwrap();
        assert!(store.pending().unwrap().is_empty());
        store.rebase_conflict("first").unwrap();
        let pending = store.pending().unwrap();
        assert_eq!(pending[0].base_version, 4);
        assert_eq!(pending[1].base_version, 5);
        store.mark_conflict("first", 6, "{}").unwrap();
        store.discard_command("first").unwrap();
        assert!(store.pending().unwrap().is_empty());
    }
}
