//! Crash-safe local cursor, materialized resources, and command outbox.

use std::path::Path;
use std::sync::Mutex;

use rusqlite::{params, Connection, OptionalExtension};

use crate::{CloudError, SyncBootstrap, SyncCommand, SyncEvent, SyncResource};

const MAX_RESOURCE_BYTES: usize = 2 * 1024 * 1024;
const MAX_OUTBOX_COMMANDS: i64 = 100;

pub struct SyncStateStore {
    conn: Mutex<Connection>,
}

#[derive(Debug, PartialEq)]
pub struct BlockedSyncCommand {
    pub command: SyncCommand,
    pub current_version: u64,
    pub current_payload_json: String,
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

    fn init(mut conn: Connection) -> Result<Self, CloudError> {
        conn.pragma_update(None, "synchronous", "FULL")
            .map_err(db_error)?;
        conn.busy_timeout(std::time::Duration::from_secs(5))
            .map_err(db_error)?;
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
        let migration = conn
            .transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)
            .map_err(db_error)?;
        let has_activity: bool = migration
            .query_row("SELECT EXISTS(SELECT 1 FROM pragma_table_info('resources') WHERE name='last_activity_at')", [], |row| row.get(0))
            .map_err(db_error)?;
        if !has_activity {
            migration
                .execute(
                    "ALTER TABLE resources ADD COLUMN last_activity_at INTEGER",
                    [],
                )
                .map_err(db_error)?;
        }
        migration.commit().map_err(db_error)?;
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
    ) -> Result<SyncBootstrap, CloudError> {
        let mut conn = self.conn.lock().map_err(|_| CloudError::Unexpected)?;
        let tx = conn.transaction().map_err(db_error)?;
        if bootstrap.cursor < read_cursor(&tx)? || bootstrap.cursor < acknowledged_cursor(&tx)? {
            return Err(CloudError::Network(
                "stale sync bootstrap; retry required".into(),
            ));
        }
        let mut seen = std::collections::HashSet::new();
        let mut admitted_resources = Vec::with_capacity(bootstrap.resources.len());
        for resource in &bootstrap.resources {
            if project_id.is_some_and(|project| project != resource.project_id)
                || !seen.insert((
                    &resource.project_id,
                    &resource.design_id,
                    &resource.resource,
                ))
            {
                return Err(CloudError::Unexpected);
            }
            // HTTP acknowledgements can be ahead of the SSE cursor. Validate
            // before replacing the snapshot so bootstrap cannot erase them.
            validate_resource_revision(&tx, resource)?;
            let newer: bool = tx.query_row(
                "SELECT EXISTS(SELECT 1 FROM resources WHERE project_id=?1 AND design_id=?2 AND resource=?3 AND version>?4)",
                params![resource.project_id, resource.design_id.as_deref().unwrap_or(""), resource.resource, resource.version as i64],
                |row| row.get(0),
            ).map_err(db_error)?;
            if newer {
                return Err(CloudError::Network(
                    "bootstrap predates acknowledged resource; retry required".into(),
                ));
            }
            let previous_activity: Option<i64> = tx.query_row(
                "SELECT last_activity_at FROM resources WHERE project_id=?1 AND design_id=?2 AND resource=?3",
                params![resource.project_id, resource.design_id.as_deref().unwrap_or(""), resource.resource],
                |row| row.get(0),
            ).optional().map_err(db_error)?.flatten();
            let mut admitted = resource.clone();
            admitted.last_activity_at = admitted.last_activity_at.max(previous_activity);
            admitted_resources.push(admitted);
        }
        if let Some(project_id) = project_id {
            tx.execute(
                "DELETE FROM resources WHERE project_id = ?1",
                params![project_id],
            )
            .map_err(db_error)?;
        } else {
            tx.execute("DELETE FROM resources", []).map_err(db_error)?;
        }
        for resource in &admitted_resources {
            upsert_resource(&tx, resource)?;
        }
        set_cursor(&tx, bootstrap.cursor)?;
        tx.commit().map_err(db_error)?;
        Ok(SyncBootstrap {
            cursor: bootstrap.cursor,
            resources: admitted_resources,
        })
    }

    /// Apply one event and advance the durable cursor in the same transaction.
    pub fn apply_event(&self, event: &SyncEvent) -> Result<(), CloudError> {
        let mut conn = self.conn.lock().map_err(|_| CloudError::Unexpected)?;
        let tx = conn.transaction().map_err(db_error)?;
        if event.seq <= read_cursor(&tx)? {
            return Ok(());
        }
        upsert_resource(
            &tx,
            &SyncResource {
                project_id: event.project_id.clone(),
                design_id: event.design_id.clone(),
                resource: event.resource.clone(),
                version: event.version,
                payload: event.payload.clone(),
                last_activity_at: Some(event.created_at),
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
                last_activity_at: Some(event.created_at),
            },
        )?;
        // This is a snapshot lower bound, not an SSE replay cursor. Advancing
        // the latter here would skip unrelated events preceding this receipt.
        let acknowledged = acknowledged_cursor(&tx)?.max(event.seq);
        tx.execute(
            "INSERT INTO meta(key,value) VALUES('acknowledged_cursor',?1) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
            params![acknowledged.to_string()],
        ).map_err(db_error)?;
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
            "SELECT version, payload_json, last_activity_at FROM resources
             WHERE project_id = ?1 AND design_id = ?2 AND resource = ?3",
            params![project_id, design_id, resource],
            |row| {
                let payload: String = row.get(1)?;
                Ok(SyncResource {
                    project_id: project_id.into(),
                    design_id: (!design_id.is_empty()).then(|| design_id.into()),
                    resource: resource.into(),
                    version: row.get::<_, i64>(0)? as u64,
                    payload: decode_cached_payload(&payload, 1)?,
                    last_activity_at: row.get(2)?,
                })
            },
        )
        .optional()
        .map_err(db_error)
    }

    pub fn enqueue(&self, command: &SyncCommand) -> Result<(), CloudError> {
        self.enqueue_inner(command, false).map(|_| ())
    }

    /// Reserve the next resource revision and persist the command under one
    /// lock/transaction. Never split these operations across bridge calls.
    pub fn enqueue_next(&self, command: &SyncCommand) -> Result<SyncCommand, CloudError> {
        self.enqueue_inner(command, true)
    }

    fn enqueue_inner(
        &self,
        command: &SyncCommand,
        reserve: bool,
    ) -> Result<SyncCommand, CloudError> {
        let mut command = command.clone();
        let mut conn = self.conn.lock().map_err(|_| CloudError::Unexpected)?;
        let tx = conn.transaction().map_err(db_error)?;
        let existing: Option<String> = tx
            .query_row(
                "SELECT body_json FROM outbox WHERE command_id = ?1",
                params![command.command_id],
                |row| row.get(0),
            )
            .optional()
            .map_err(db_error)?;
        if let Some(existing) = existing {
            let stored: SyncCommand =
                serde_json::from_str(&existing).map_err(|_| CloudError::Unexpected)?;
            if reserve {
                command.base_version = stored.base_version;
            }
            return if stored == command {
                Ok(stored)
            } else {
                Err(CloudError::Network(
                    "command ID already belongs to a different edit".into(),
                ))
            };
        }
        let count: i64 = tx
            .query_row("SELECT COUNT(*) FROM outbox", [], |row| row.get(0))
            .map_err(db_error)?;
        if count >= MAX_OUTBOX_COMMANDS {
            return Err(CloudError::Network(
                "sync queue is full; reconnect or resolve pending edits".into(),
            ));
        }
        if reserve {
            command.base_version = next_version(
                &tx,
                &command.project_id,
                command.design_id.as_deref(),
                &command.resource,
            )?;
        }
        let body = serde_json::to_string(&command).map_err(|_| CloudError::Unexpected)?;
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
        tx.execute(
            "INSERT INTO outbox(command_id, body_json, created_at) VALUES (?1, ?2, ?3)
             ON CONFLICT(command_id) DO NOTHING",
            params![command.command_id, body, now],
        )
        .map_err(db_error)?;
        tx.commit().map_err(db_error)?;
        Ok(command)
    }

    pub fn next_base_version(
        &self,
        project_id: &str,
        design_id: Option<&str>,
        resource: &str,
    ) -> Result<u64, CloudError> {
        let conn = self.conn.lock().map_err(|_| CloudError::Unexpected)?;
        next_version(&conn, project_id, design_id, resource)
    }

    pub fn pending(&self) -> Result<Vec<SyncCommand>, CloudError> {
        let conn = self.conn.lock().map_err(|_| CloudError::Unexpected)?;
        pending_commands(&conn)
    }

    pub fn is_pending(&self, command_id: &str) -> Result<bool, CloudError> {
        let conn = self.conn.lock().map_err(|_| CloudError::Unexpected)?;
        conn.query_row(
            "SELECT EXISTS(SELECT 1 FROM outbox WHERE command_id = ?1 AND blocked = 0)",
            params![command_id],
            |row| row.get(0),
        )
        .map_err(db_error)
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

    /// Only conflict roots carry a server snapshot. Dependent edits remain
    /// blocked behind that root and must not create duplicate recovery prompts.
    pub fn conflicts(&self) -> Result<Vec<BlockedSyncCommand>, CloudError> {
        let conn = self.conn.lock().map_err(|_| CloudError::Unexpected)?;
        let mut stmt = conn.prepare("SELECT body_json, conflict_version, conflict_payload_json FROM outbox WHERE blocked=1 AND conflict_version IS NOT NULL AND conflict_payload_json IS NOT NULL ORDER BY rowid").map_err(db_error)?;
        let rows = stmt
            .query_map([], |row| {
                Ok((
                    row.get::<_, String>(0)?,
                    row.get::<_, u64>(1)?,
                    row.get::<_, String>(2)?,
                ))
            })
            .map_err(db_error)?;
        rows.map(|row| {
            let (body, current_version, current_payload_json) = row.map_err(db_error)?;
            Ok(BlockedSyncCommand {
                command: serde_json::from_str(&body).map_err(|_| CloudError::Unexpected)?,
                current_version,
                current_payload_json,
            })
        })
        .collect()
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
            // A changed precondition is a new command, never a retry of the
            // old immutable request identity. Replace dependent IDs too.
            command.command_id = uuid::Uuid::new_v4().to_string();
            let body = serde_json::to_string(&command).map_err(|_| CloudError::Unexpected)?;
            tx.execute(
                "UPDATE outbox SET body_json = ?2, command_id = ?3, blocked = 0,
                 conflict_version = NULL, conflict_payload_json = NULL WHERE rowid = ?1",
                params![rowid, body, command.command_id],
            )
            .map_err(db_error)?;
        }
        tx.commit().map_err(db_error)
    }

    pub fn resources(&self, project_id: Option<&str>) -> Result<Vec<SyncResource>, CloudError> {
        let conn = self.conn.lock().map_err(|_| CloudError::Unexpected)?;
        let sql = if project_id.is_some() {
            "SELECT project_id, design_id, resource, version, payload_json, last_activity_at
             FROM resources WHERE project_id = ?1 ORDER BY project_id, design_id, resource"
        } else {
            "SELECT project_id, design_id, resource, version, payload_json, last_activity_at
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
                payload: decode_cached_payload(&payload, 4)?,
                last_activity_at: row.get(5)?,
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

fn decode_cached_payload(payload: &str, column: usize) -> rusqlite::Result<serde_json::Value> {
    serde_json::from_str(payload).map_err(|error| {
        rusqlite::Error::FromSqlConversionFailure(
            column,
            rusqlite::types::Type::Text,
            Box::new(error),
        )
    })
}

fn read_cursor(conn: &Connection) -> Result<u64, CloudError> {
    let value: Option<String> = conn
        .query_row("SELECT value FROM meta WHERE key='cursor'", [], |row| {
            row.get(0)
        })
        .optional()
        .map_err(db_error)?;
    value
        .map(|v| v.parse().map_err(|_| CloudError::Unexpected))
        .unwrap_or(Ok(0))
}

fn pending_commands(conn: &Connection) -> Result<Vec<SyncCommand>, CloudError> {
    let mut stmt = conn
        .prepare("SELECT body_json FROM outbox WHERE blocked=0 ORDER BY rowid")
        .map_err(db_error)?;
    let rows = stmt
        .query_map([], |row| row.get::<_, String>(0))
        .map_err(db_error)?;
    rows.map(|row| {
        serde_json::from_str(&row.map_err(db_error)?).map_err(|_| CloudError::Unexpected)
    })
    .collect()
}

fn next_version(
    conn: &Connection,
    project_id: &str,
    design_id: Option<&str>,
    resource: &str,
) -> Result<u64, CloudError> {
    let current: Option<u64> = conn
        .query_row(
            "SELECT version FROM resources WHERE project_id=?1 AND design_id=?2 AND resource=?3",
            params![project_id, design_id.unwrap_or(""), resource],
            |row| row.get(0),
        )
        .optional()
        .map_err(db_error)?;
    let (queued, blocked): (Option<u64>, i64) = conn.query_row(
        "SELECT MAX(json_extract(body_json, '$.base_version')), COALESCE(MAX(blocked),0) FROM outbox WHERE json_extract(body_json,'$.project_id')=?1 AND COALESCE(json_extract(body_json,'$.design_id'),'')=?2 AND json_extract(body_json,'$.resource')=?3",
        params![project_id, design_id.unwrap_or(""), resource], |row| Ok((row.get(0)?, row.get(1)?))).map_err(db_error)?;
    if blocked != 0 {
        return Err(CloudError::Network(
            "resolve the pending resource conflict before making another edit".into(),
        ));
    }
    Ok(current
        .unwrap_or(0)
        .max(queued.map(|version| version.saturating_add(1)).unwrap_or(0)))
}

fn upsert_resource(conn: &Connection, resource: &SyncResource) -> Result<(), CloudError> {
    validate_resource_revision(conn, resource)?;
    let payload = serde_json::to_string(&resource.payload).map_err(|_| CloudError::Unexpected)?;
    conn.execute(
        "INSERT INTO resources(project_id, design_id, resource, version, payload_json, last_activity_at)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6)
         ON CONFLICT(project_id, design_id, resource) DO UPDATE SET
           version=excluded.version, payload_json=excluded.payload_json,
           last_activity_at=CASE WHEN resources.last_activity_at IS NULL THEN excluded.last_activity_at
             WHEN excluded.last_activity_at IS NULL THEN resources.last_activity_at
             ELSE MAX(resources.last_activity_at,excluded.last_activity_at) END
         WHERE excluded.version >= resources.version",
        params![
            resource.project_id,
            resource.design_id.as_deref().unwrap_or(""),
            resource.resource,
            resource.version as i64,
            payload,
            resource.last_activity_at
        ],
    )
    .map_err(db_error)?;
    Ok(())
}

fn validate_resource_revision(
    conn: &Connection,
    resource: &SyncResource,
) -> Result<(), CloudError> {
    if resource.version > i64::MAX as u64
        || resource.last_activity_at.is_some_and(|value| value < 0)
    {
        return Err(CloudError::Unexpected);
    }
    let existing: Option<(u64, String)> = conn.query_row(
        "SELECT version,payload_json FROM resources WHERE project_id=?1 AND design_id=?2 AND resource=?3",
        params![resource.project_id, resource.design_id.as_deref().unwrap_or(""), resource.resource],
        |row| Ok((row.get(0)?, row.get(1)?)),
    ).optional().map_err(db_error)?;
    if let Some((version, payload)) = existing {
        if resource.version == version
            && serde_json::from_str::<serde_json::Value>(&payload)
                .map_err(|_| CloudError::Unexpected)?
                != resource.payload
        {
            return Err(CloudError::Unexpected);
        }
    }
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

fn acknowledged_cursor(conn: &Connection) -> Result<u64, CloudError> {
    let value: Option<String> = conn
        .query_row(
            "SELECT value FROM meta WHERE key='acknowledged_cursor'",
            [],
            |row| row.get(0),
        )
        .optional()
        .map_err(db_error)?;
    value
        .map(|value| value.parse().map_err(|_| CloudError::Unexpected))
        .transpose()
        .map(|value| value.unwrap_or(0))
}

fn db_error(error: rusqlite::Error) -> CloudError {
    CloudError::Network(format!("sync state: {error}"))
}

#[cfg(test)]
mod tests {
    #[test]
    fn committed_activity_survives_restart_bootstrap_and_exact_replay() {
        let directory =
            std::env::temp_dir().join(format!("tokito-activity-{}", uuid::Uuid::new_v4()));
        let path = directory.join("sync.sqlite3");
        let event = SyncEvent {
            seq: 1,
            project_id: "p".into(),
            design_id: Some("d".into()),
            resource: "bom".into(),
            version: 1,
            command_id: "approved".into(),
            actor_device_id: "phone".into(),
            payload: serde_json::json!({"lines": []}),
            created_at: 100,
        };
        {
            let store = SyncStateStore::open(&path).unwrap();
            store.apply_event(&event).unwrap();
            store.apply_event(&event).unwrap();
            let mut cached = store.resource("p", Some("d"), "bom").unwrap().unwrap();
            assert_eq!(cached.last_activity_at, Some(100));
            cached.last_activity_at = None;
            store
                .apply_bootstrap(
                    &SyncBootstrap {
                        cursor: 1,
                        resources: vec![cached],
                    },
                    None,
                )
                .unwrap();
        }
        {
            let store = SyncStateStore::open(&path).unwrap();
            assert_eq!(
                store.resources(None).unwrap()[0].last_activity_at,
                Some(100)
            );
            assert!(store.resources(Some("other")).unwrap().is_empty());
            let mut earlier = event.clone();
            earlier.seq = 2;
            earlier.created_at = 90;
            store.apply_event(&earlier).unwrap();
            assert_eq!(
                store.resources(None).unwrap()[0].last_activity_at,
                Some(100)
            );
            let command = SyncCommand {
                device_id: "phone".into(),
                command_id: "pending".into(),
                project_id: "p".into(),
                design_id: Some("d".into()),
                resource: "bom".into(),
                base_version: 1,
                payload: serde_json::json!({"lines":[1]}),
            };
            store.enqueue(&command).unwrap();
            assert_eq!(
                store.resources(None).unwrap()[0].last_activity_at,
                Some(100)
            );
        }
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn existing_resource_cache_migrates_without_inventing_activity() {
        let conn = Connection::open_in_memory().unwrap();
        conn.execute_batch("CREATE TABLE resources(project_id TEXT NOT NULL,design_id TEXT NOT NULL,resource TEXT NOT NULL,version INTEGER NOT NULL,payload_json TEXT NOT NULL,PRIMARY KEY(project_id,design_id,resource)); INSERT INTO resources VALUES('p','','project',1,'{}');").unwrap();
        let store = SyncStateStore::init(conn).unwrap();
        assert_eq!(
            store
                .resource("p", None, "project")
                .unwrap()
                .unwrap()
                .last_activity_at,
            None
        );
    }

    #[test]
    fn malformed_cache_is_not_reinterpreted_as_null_content() {
        let store = super::SyncStateStore::open(std::path::Path::new(":memory:")).unwrap();
        store.conn.lock().unwrap().execute("INSERT INTO resources(project_id,design_id,resource,version,payload_json) VALUES('p','d','schematic',1,'broken-json')", []).unwrap();
        assert!(store.resource("p", Some("d"), "schematic").is_err());
        assert!(store.resources(None).is_err());
        assert!(store.resources(Some("p")).is_err());
    }
    use super::*;

    #[test]
    fn bootstrap_cannot_erase_newer_ack_or_cross_scope() {
        let store = SyncStateStore::in_memory().unwrap();
        let event = SyncEvent {
            seq: 10,
            project_id: "p".into(),
            design_id: Some("d".into()),
            resource: "bom".into(),
            version: 4,
            command_id: "ack".into(),
            actor_device_id: "device".into(),
            payload: serde_json::json!({"lines":[4]}),
            created_at: 1,
        };
        store.apply_ack(&event).unwrap();
        assert!(store
            .apply_bootstrap(
                &SyncBootstrap {
                    cursor: 9,
                    resources: vec![]
                },
                Some("p")
            )
            .is_err());
        let mut resource = store.resource("p", Some("d"), "bom").unwrap().unwrap();
        resource.version = 3;
        resource.payload = serde_json::json!({"lines":[3]});
        assert!(store
            .apply_bootstrap(
                &SyncBootstrap {
                    cursor: 7,
                    resources: vec![resource.clone()]
                },
                Some("p")
            )
            .is_err());
        assert_eq!(store.cursor().unwrap(), 0);
        assert_eq!(
            store
                .resource("p", Some("d"), "bom")
                .unwrap()
                .unwrap()
                .version,
            4
        );
        resource.project_id = "other".into();
        assert!(store
            .apply_bootstrap(
                &SyncBootstrap {
                    cursor: 11,
                    resources: vec![resource]
                },
                Some("p")
            )
            .is_err());
        assert_eq!(store.cursor().unwrap(), 0);
    }

    #[test]
    fn revision_identity_is_immutable_and_duplicate_bootstrap_is_rejected() {
        let store = SyncStateStore::in_memory().unwrap();
        let mut resource = SyncResource {
            project_id: "p".into(),
            design_id: None,
            resource: "project".into(),
            version: 1,
            payload: serde_json::json!({"name":"original"}),
            last_activity_at: None,
        };
        store
            .apply_bootstrap(
                &SyncBootstrap {
                    cursor: 1,
                    resources: vec![resource.clone()],
                },
                None,
            )
            .unwrap();
        assert!(store
            .apply_bootstrap(
                &SyncBootstrap {
                    cursor: 2,
                    resources: vec![resource.clone(), resource.clone()]
                },
                None
            )
            .is_err());
        resource.payload = serde_json::json!({"name":"different"});
        assert!(store
            .apply_bootstrap(
                &SyncBootstrap {
                    cursor: 2,
                    resources: vec![resource]
                },
                None
            )
            .is_err());
        assert_eq!(store.cursor().unwrap(), 1);
        assert_eq!(
            store
                .resource("p", None, "project")
                .unwrap()
                .unwrap()
                .payload["name"],
            "original"
        );
    }

    #[test]
    fn acknowledgement_snapshot_floor_survives_restart_without_skipping_sse() {
        let directory =
            std::env::temp_dir().join(format!("tokito-ack-floor-{}", uuid::Uuid::new_v4()));
        let path = directory.join("sync.sqlite3");
        let resource = SyncResource {
            project_id: "p".into(),
            design_id: None,
            resource: "project".into(),
            version: 1,
            payload: serde_json::json!({"name":"acknowledged"}),
            last_activity_at: Some(1),
        };
        {
            let store = SyncStateStore::open(&path).unwrap();
            store
                .apply_ack(&SyncEvent {
                    seq: 12,
                    project_id: resource.project_id.clone(),
                    design_id: None,
                    resource: resource.resource.clone(),
                    version: 1,
                    command_id: "c".into(),
                    actor_device_id: "d".into(),
                    payload: resource.payload.clone(),
                    created_at: 1,
                })
                .unwrap();
            assert_eq!(store.cursor().unwrap(), 0);
        }
        {
            let store = SyncStateStore::open(&path).unwrap();
            assert!(store
                .apply_bootstrap(
                    &SyncBootstrap {
                        cursor: 11,
                        resources: vec![]
                    },
                    None
                )
                .is_err());
            assert_eq!(store.cursor().unwrap(), 0);
            assert_eq!(
                store.resource("p", None, "project").unwrap().unwrap(),
                resource
            );
            store
                .apply_bootstrap(
                    &SyncBootstrap {
                        cursor: 12,
                        resources: vec![resource],
                    },
                    None,
                )
                .unwrap();
            assert_eq!(store.cursor().unwrap(), 12);
        }
        std::fs::remove_dir_all(directory).unwrap();
    }

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
        assert_ne!(pending[0].command_id, "first");
        assert_ne!(pending[1].command_id, "second");
        store
            .mark_conflict(&pending[0].command_id, 6, "{}")
            .unwrap();
        store.discard_command(&pending[0].command_id).unwrap();
        assert!(store.pending().unwrap().is_empty());
    }

    fn command(id: &str) -> SyncCommand {
        SyncCommand {
            device_id: "d".into(),
            command_id: id.into(),
            project_id: "p".into(),
            design_id: Some("s".into()),
            resource: "bom".into(),
            base_version: 0,
            payload: serde_json::json!({"lines":[]}),
        }
    }

    #[test]
    fn concurrent_enqueues_reserve_unique_revisions_atomically() {
        let store = std::sync::Arc::new(SyncStateStore::in_memory().unwrap());
        let handles: Vec<_> = (0..20)
            .map(|i| {
                let store = store.clone();
                std::thread::spawn(move || {
                    store
                        .enqueue_next(&command(&i.to_string()))
                        .unwrap()
                        .base_version
                })
            })
            .collect();
        let mut versions: Vec<_> = handles.into_iter().map(|h| h.join().unwrap()).collect();
        versions.sort_unstable();
        assert_eq!(versions, (0..20).collect::<Vec<_>>());
    }

    #[test]
    fn duplicate_ids_cannot_silently_replace_edits_and_blocked_resources_reject_new_edits() {
        let store = SyncStateStore::in_memory().unwrap();
        let first = command("first");
        store.enqueue_next(&first).unwrap();
        store.enqueue_next(&first).unwrap();
        let mut changed = first.clone();
        changed.payload = serde_json::json!({"lines":[1]});
        assert!(store.enqueue_next(&changed).is_err());
        store.mark_conflict("first", 5, "{}").unwrap();
        assert!(store.enqueue_next(&command("second")).is_err());
    }

    #[test]
    fn old_events_and_bootstraps_do_not_rewind_cursor_or_materialized_resources() {
        let store = SyncStateStore::in_memory().unwrap();
        let event = SyncEvent {
            seq: 10,
            project_id: "p".into(),
            design_id: Some("s".into()),
            resource: "bom".into(),
            version: 4,
            command_id: "remote".into(),
            actor_device_id: "other".into(),
            payload: serde_json::json!({"new":true}),
            created_at: 1,
        };
        store.apply_event(&event).unwrap();
        let mut old = event.clone();
        old.seq = 9;
        old.version = 3;
        old.payload = serde_json::json!({});
        store.apply_event(&old).unwrap();
        assert!(store
            .apply_bootstrap(
                &SyncBootstrap {
                    cursor: 9,
                    resources: vec![]
                },
                None
            )
            .is_err());
        assert_eq!(store.cursor().unwrap(), 10);
        assert_eq!(
            store
                .resource("p", Some("s"), "bom")
                .unwrap()
                .unwrap()
                .payload,
            event.payload
        );
    }

    #[test]
    fn reservation_after_remote_change_uses_revision_not_queue_length() {
        let store = SyncStateStore::in_memory().unwrap();
        store.enqueue_next(&command("first")).unwrap();
        store
            .apply_ack(&SyncEvent {
                seq: 12,
                project_id: "p".into(),
                design_id: Some("s".into()),
                resource: "bom".into(),
                version: 7,
                command_id: "remote".into(),
                actor_device_id: "other".into(),
                payload: serde_json::json!({}),
                created_at: 1,
            })
            .unwrap();
        assert_eq!(
            store.enqueue_next(&command("second")).unwrap().base_version,
            7
        );
        assert_eq!(store.cursor().unwrap(), 0);
    }

    #[test]
    fn queue_is_bounded_and_conflict_invalidates_a_previously_loaded_batch() {
        let store = SyncStateStore::in_memory().unwrap();
        for i in 0..100 {
            store.enqueue_next(&command(&i.to_string())).unwrap();
        }
        assert!(store.enqueue_next(&command("overflow")).is_err());
        let batch = store.pending().unwrap();
        store.mark_conflict(&batch[0].command_id, 2, "{}").unwrap();
        assert!(!store.is_pending(&batch[1].command_id).unwrap());
    }

    #[test]
    fn blocked_conflicts_survive_restart_and_rebase_replaces_all_request_ids() {
        let path = std::env::temp_dir().join(format!(
            "tokito-sync-conflict-{}.sqlite3",
            uuid::Uuid::new_v4()
        ));
        {
            let store = SyncStateStore::open(&path).unwrap();
            store.enqueue_next(&command("original")).unwrap();
            store.enqueue_next(&command("dependent")).unwrap();
            store
                .mark_conflict("original", 4, "{\"server\":true}")
                .unwrap();
        }
        {
            let store = SyncStateStore::open(&path).unwrap();
            let conflicts = store.conflicts().unwrap();
            assert_eq!(conflicts.len(), 1);
            assert_eq!(conflicts[0].command.command_id, "original");
            assert_eq!(conflicts[0].current_version, 4);
            assert_eq!(conflicts[0].current_payload_json, "{\"server\":true}");
            assert!(store.pending().unwrap().is_empty());
            store.rebase_conflict("original").unwrap();
            let pending = store.pending().unwrap();
            assert_eq!(pending.len(), 2);
            assert_eq!(pending[0].base_version, 4);
            assert_eq!(pending[1].base_version, 5);
            for edit in &pending {
                assert!(!["original", "dependent"].contains(&edit.command_id.as_str()));
                assert_eq!(edit.payload, command("original").payload);
            }
            assert_ne!(pending[0].command_id, pending[1].command_id);
            assert!(store.conflicts().unwrap().is_empty());
            assert!(store.rebase_conflict("original").is_err());
            assert!(!store.is_pending("dependent").unwrap());
        }
        std::fs::remove_file(path).unwrap();
    }
}
