//! Account-bound durable chat outbox and event cache. Transport acknowledgements
//! and UI delivery never substitute for committing the local transaction.

use std::{path::Path, sync::Mutex, time::Duration};

use rusqlite::{params, Connection, OptionalExtension};
use serde::{Deserialize, Serialize};

use crate::{
    conversations::{Run, RunEvent, SendMessage, ThreadPage},
    CloudError,
};

const MAX_JSON_BYTES: usize = 2 * 1024 * 1024;
const MAX_PENDING: i64 = 100;
const MAX_RUN_BYTES: i64 = 64 * 1024 * 1024;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PendingMessage {
    pub project_id: String,
    pub design_id: String,
    pub thread_id: String,
    pub request: SendMessage,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct QueuedMessage {
    pub message: PendingMessage,
    pub blocked: bool,
}

pub struct ConversationStore {
    conn: Mutex<Connection>,
}

fn db_error(error: rusqlite::Error) -> CloudError {
    // A SQLite error can contain user content/SQL. Keep it out of UI and logs.
    let _ = error;
    CloudError::Unavailable
}

fn encode<T: Serialize>(value: &T) -> Result<String, CloudError> {
    let json = serde_json::to_string(value).map_err(|_| CloudError::Unexpected)?;
    if json.len() > MAX_JSON_BYTES {
        return Err(CloudError::Unexpected);
    }
    Ok(json)
}

impl ConversationStore {
    /// `owner` is a verified account+origin identity, never the bearer token.
    pub fn open(path: &Path, owner: &str) -> Result<Self, CloudError> {
        if owner.trim().is_empty() {
            return Err(CloudError::Unauthorized);
        }
        if let Some(parent) = path.parent().filter(|p| !p.as_os_str().is_empty()) {
            std::fs::create_dir_all(parent).map_err(|_| CloudError::Unavailable)?;
        }
        Self::init(Connection::open(path).map_err(db_error)?, owner)
    }

    fn init(mut conn: Connection, owner: &str) -> Result<Self, CloudError> {
        conn.busy_timeout(Duration::from_secs(5))
            .map_err(db_error)?;
        conn.pragma_update(None, "journal_mode", "WAL")
            .map_err(db_error)?;
        conn.pragma_update(None, "synchronous", "FULL")
            .map_err(db_error)?;
        conn.execute_batch("CREATE TABLE IF NOT EXISTS chat_owner (id INTEGER PRIMARY KEY CHECK(id=1), owner TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS chat_outbox (message_id TEXT PRIMARY KEY, thread_id TEXT NOT NULL UNIQUE, body TEXT NOT NULL, error TEXT);
            CREATE TABLE IF NOT EXISTS chat_runs (run_id TEXT PRIMARY KEY, thread_id TEXT NOT NULL, body TEXT NOT NULL, cursor INTEGER NOT NULL DEFAULT 0, event_bytes INTEGER NOT NULL DEFAULT 0);
            CREATE INDEX IF NOT EXISTS chat_runs_thread ON chat_runs(thread_id);
            CREATE TABLE IF NOT EXISTS chat_events (run_id TEXT NOT NULL, seq INTEGER NOT NULL, body TEXT NOT NULL, PRIMARY KEY(run_id,seq));
            CREATE TABLE IF NOT EXISTS chat_threads (thread_id TEXT PRIMARY KEY, body TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS chat_recovered_drafts (thread_id TEXT PRIMARY KEY, content TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS chat_messages (thread_id TEXT NOT NULL, seq INTEGER NOT NULL, message_id TEXT NOT NULL, body TEXT NOT NULL, PRIMARY KEY(thread_id,seq), UNIQUE(thread_id,message_id));")
            .map_err(db_error)?;
        let tx = conn.transaction().map_err(db_error)?;
        let existing: Option<String> = tx
            .query_row("SELECT owner FROM chat_owner WHERE id=1", [], |r| r.get(0))
            .optional()
            .map_err(db_error)?;
        match existing {
            Some(existing) if existing != owner => return Err(CloudError::Unauthorized),
            None => {
                tx.execute("INSERT INTO chat_owner(id,owner) VALUES(1,?1)", [owner])
                    .map_err(db_error)?;
            }
            _ => {}
        }
        tx.commit().map_err(db_error)?;
        Ok(Self {
            conn: Mutex::new(conn),
        })
    }

    pub fn enqueue(&self, message: &PendingMessage) -> Result<(), CloudError> {
        let body = encode(message)?;
        let mut conn = self.conn.lock().map_err(|_| CloudError::Unexpected)?;
        let tx = conn.transaction().map_err(db_error)?;
        let existing: Option<String> = tx
            .query_row(
                "SELECT body FROM chat_outbox WHERE message_id=?1",
                [&message.request.message_id],
                |r| r.get(0),
            )
            .optional()
            .map_err(db_error)?;
        if let Some(existing) = existing {
            return if existing == body {
                Ok(())
            } else {
                Err(CloudError::Conflict {
                    current_version: 0,
                    current_payload: "null".into(),
                })
            };
        }
        let count: i64 = tx
            .query_row("SELECT COUNT(*) FROM chat_outbox", [], |r| r.get(0))
            .map_err(db_error)?;
        if count >= MAX_PENDING {
            return Err(CloudError::Unavailable);
        }
        tx.execute(
            "INSERT INTO chat_outbox(message_id,thread_id,body) VALUES(?1,?2,?3)",
            params![message.request.message_id, message.thread_id, body],
        )
        .map_err(db_error)?;
        tx.execute(
            "DELETE FROM chat_recovered_drafts WHERE thread_id=?1",
            [&message.thread_id],
        )
        .map_err(db_error)?;
        tx.commit().map_err(db_error)
    }

    /// Include quarantined and offline work so a reopened view cannot hide a
    /// durable message or offer a second send into the same thread.
    pub fn queued_for_thread(&self, thread: &str) -> Result<Option<QueuedMessage>, CloudError> {
        let conn = self.conn.lock().map_err(|_| CloudError::Unexpected)?;
        let row: Option<(String, bool)> = conn
            .query_row(
                "SELECT body,error IS NOT NULL FROM chat_outbox WHERE thread_id=?1",
                [thread],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .optional()
            .map_err(db_error)?;
        row.map(|(body, blocked)| {
            Ok(QueuedMessage {
                message: serde_json::from_str(&body).map_err(|_| CloudError::Unexpected)?,
                blocked,
            })
        })
        .transpose()
    }

    pub fn recovered_draft(&self, thread: &str) -> Result<Option<String>, CloudError> {
        let conn = self.conn.lock().map_err(|_| CloudError::Unexpected)?;
        conn.query_row(
            "SELECT content FROM chat_recovered_drafts WHERE thread_id=?1",
            [thread],
            |r| r.get(0),
        )
        .optional()
        .map_err(db_error)
    }

    /// Recovery is one durable transaction, not delete-then-copy through JS.
    /// A process death before rendering the composer cannot erase the request.
    pub fn restore_blocked(&self, message_id: &str) -> Result<String, CloudError> {
        let mut conn = self.conn.lock().map_err(|_| CloudError::Unexpected)?;
        let tx = conn.transaction().map_err(db_error)?;
        let body: Option<String> = tx
            .query_row(
                "SELECT body FROM chat_outbox WHERE message_id=?1 AND error IS NOT NULL",
                [message_id],
                |r| r.get(0),
            )
            .optional()
            .map_err(db_error)?;
        let message: PendingMessage = serde_json::from_str(&body.ok_or(CloudError::Unexpected)?)
            .map_err(|_| CloudError::Unexpected)?;
        tx.execute("INSERT INTO chat_recovered_drafts(thread_id,content) VALUES(?1,?2) ON CONFLICT(thread_id) DO UPDATE SET content=excluded.content", params![message.thread_id, message.request.content]).map_err(db_error)?;
        tx.execute("DELETE FROM chat_outbox WHERE message_id=?1", [message_id])
            .map_err(db_error)?;
        tx.commit().map_err(db_error)?;
        Ok(message.request.content)
    }

    pub fn pending(&self) -> Result<Vec<PendingMessage>, CloudError> {
        let conn = self.conn.lock().map_err(|_| CloudError::Unexpected)?;
        let mut stmt = conn
            .prepare("SELECT body FROM chat_outbox WHERE error IS NULL ORDER BY rowid LIMIT 100")
            .map_err(db_error)?;
        let rows = stmt
            .query_map([], |r| r.get::<_, String>(0))
            .map_err(db_error)?;
        rows.map(|row| {
            serde_json::from_str(&row.map_err(db_error)?).map_err(|_| CloudError::Unexpected)
        })
        .collect()
    }

    pub fn has_pending(&self, thread: &str) -> Result<bool, CloudError> {
        let conn = self.conn.lock().map_err(|_| CloudError::Unexpected)?;
        conn.query_row(
            "SELECT EXISTS(SELECT 1 FROM chat_outbox WHERE thread_id=?1)",
            [thread],
            |r| r.get(0),
        )
        .map_err(db_error)
    }

    pub fn blocked(&self, thread: &str) -> Result<Vec<PendingMessage>, CloudError> {
        let conn = self.conn.lock().map_err(|_| CloudError::Unexpected)?;
        let mut query = conn
            .prepare(
                "SELECT body FROM chat_outbox WHERE thread_id=?1 AND error IS NOT NULL LIMIT 100",
            )
            .map_err(db_error)?;
        let result = query
            .query_map([thread], |r| r.get::<_, String>(0))
            .map_err(db_error)?
            .map(|row| {
                serde_json::from_str(&row.map_err(db_error)?).map_err(|_| CloudError::Unexpected)
            })
            .collect();
        result
    }

    /// Explicit user recovery for a rejected request; never silently resubmit
    /// a stale engineering request against a newer schematic revision.
    pub fn discard_blocked(&self, message_id: &str) -> Result<bool, CloudError> {
        let conn = self.conn.lock().map_err(|_| CloudError::Unexpected)?;
        Ok(conn
            .execute(
                "DELETE FROM chat_outbox WHERE message_id=?1 AND error IS NOT NULL",
                [message_id],
            )
            .map_err(db_error)?
            == 1)
    }

    /// Retryable transport failures leave the same idempotency key queued.
    /// Conflicts/invalid requests are quarantined, never silently rebased.
    pub fn block(&self, message_id: &str, reason: &str) -> Result<(), CloudError> {
        let conn = self.conn.lock().map_err(|_| CloudError::Unexpected)?;
        conn.execute(
            "UPDATE chat_outbox SET error=?2 WHERE message_id=?1",
            params![message_id, reason.chars().take(256).collect::<String>()],
        )
        .map_err(db_error)?;
        Ok(())
    }

    pub fn acknowledge(&self, run: &Run) -> Result<(), CloudError> {
        let body = encode(run)?;
        let mut conn = self.conn.lock().map_err(|_| CloudError::Unexpected)?;
        let tx = conn.transaction().map_err(db_error)?;
        let pending: Option<String> = tx
            .query_row(
                "SELECT body FROM chat_outbox WHERE message_id=?1",
                [&run.message_id],
                |r| r.get(0),
            )
            .optional()
            .map_err(db_error)?;
        if let Some(pending) = pending {
            let pending: PendingMessage =
                serde_json::from_str(&pending).map_err(|_| CloudError::Unexpected)?;
            if pending.request.run_id != run.run_id
                || pending.project_id != run.project_id
                || pending.design_id != run.design_id
                || pending.thread_id != run.thread_id
            {
                return Err(CloudError::Unexpected);
            }
        }
        let existing: Option<String> = tx
            .query_row(
                "SELECT body FROM chat_runs WHERE run_id=?1",
                [&run.run_id],
                |r| r.get(0),
            )
            .optional()
            .map_err(db_error)?;
        if let Some(existing) = existing {
            let old: Run = serde_json::from_str(&existing).map_err(|_| CloudError::Unexpected)?;
            if old.project_id != run.project_id
                || old.design_id != run.design_id
                || old.thread_id != run.thread_id
                || old.message_id != run.message_id
            {
                return Err(CloudError::Unexpected);
            }
        }
        tx.execute("INSERT INTO chat_runs(run_id,thread_id,body) VALUES(?1,?2,?3) ON CONFLICT(run_id) DO UPDATE SET body=excluded.body", params![run.run_id,run.thread_id,body]).map_err(db_error)?;
        tx.execute(
            "DELETE FROM chat_outbox WHERE message_id=?1",
            [&run.message_id],
        )
        .map_err(db_error)?;
        tx.commit().map_err(db_error)
    }

    pub fn cursor(&self, run_id: &str) -> Result<u64, CloudError> {
        let conn = self.conn.lock().map_err(|_| CloudError::Unexpected)?;
        let cursor: Option<u64> = conn
            .query_row(
                "SELECT cursor FROM chat_runs WHERE run_id=?1",
                [run_id],
                |r| r.get(0),
            )
            .optional()
            .map_err(db_error)?;
        Ok(cursor.unwrap_or(0))
    }

    pub fn run(&self, run_id: &str) -> Result<Option<Run>, CloudError> {
        let conn = self.conn.lock().map_err(|_| CloudError::Unexpected)?;
        let body: Option<String> = conn
            .query_row(
                "SELECT body FROM chat_runs WHERE run_id=?1",
                [run_id],
                |r| r.get(0),
            )
            .optional()
            .map_err(db_error)?;
        body.map(|body| serde_json::from_str(&body).map_err(|_| CloudError::Unexpected))
            .transpose()
    }

    pub fn cached_page(
        &self,
        thread_id: &str,
        after: u64,
    ) -> Result<Option<ThreadPage>, CloudError> {
        let conn = self.conn.lock().map_err(|_| CloudError::Unexpected)?;
        let body: Option<String> = conn
            .query_row(
                "SELECT body FROM chat_threads WHERE thread_id=?1",
                [thread_id],
                |r| r.get(0),
            )
            .optional()
            .map_err(db_error)?;
        let Some(body) = body else {
            return Ok(None);
        };
        let thread = serde_json::from_str(&body).map_err(|_| CloudError::Unexpected)?;
        let mut stmt=conn.prepare("SELECT body FROM chat_messages WHERE thread_id=?1 AND seq>?2 ORDER BY seq LIMIT 101").map_err(db_error)?;
        let rows = stmt
            .query_map(params![thread_id, after], |r| r.get::<_, String>(0))
            .map_err(db_error)?;
        let mut messages = rows
            .map(|row| {
                serde_json::from_str(&row.map_err(db_error)?).map_err(|_| CloudError::Unexpected)
            })
            .collect::<Result<Vec<crate::conversations::Message>, _>>()?;
        let next_cursor = if messages.len() > 100 {
            messages.pop();
            messages.last().map(|m| m.seq)
        } else {
            None
        };
        Ok(Some(ThreadPage {
            thread,
            messages,
            next_cursor,
            older_cursor: None,
            latest_run: None,
        }))
    }

    /// Call only after the server confirms deletion. Preserve any queued message
    /// until the user resolves it rather than silently deleting unsent content.
    pub fn remove_thread(&self, thread_id: &str) -> Result<(), CloudError> {
        let mut conn = self.conn.lock().map_err(|_| CloudError::Unexpected)?;
        let tx = conn.transaction().map_err(db_error)?;
        let pending: i64 = tx
            .query_row(
                "SELECT COUNT(*) FROM chat_outbox WHERE thread_id=?1",
                [thread_id],
                |r| r.get(0),
            )
            .map_err(db_error)?;
        if pending > 0 {
            return Err(CloudError::Conflict {
                current_version: 0,
                current_payload: "null".into(),
            });
        }
        tx.execute("DELETE FROM chat_events WHERE run_id IN (SELECT run_id FROM chat_runs WHERE thread_id=?1)", [thread_id]).map_err(db_error)?;
        tx.execute("DELETE FROM chat_runs WHERE thread_id=?1", [thread_id])
            .map_err(db_error)?;
        tx.execute("DELETE FROM chat_messages WHERE thread_id=?1", [thread_id])
            .map_err(db_error)?;
        tx.execute("DELETE FROM chat_threads WHERE thread_id=?1", [thread_id])
            .map_err(db_error)?;
        tx.execute(
            "DELETE FROM chat_recovered_drafts WHERE thread_id=?1",
            [thread_id],
        )
        .map_err(db_error)?;
        tx.commit().map_err(db_error)
    }

    pub fn apply_event(&self, run_id: &str, event: &RunEvent) -> Result<(), CloudError> {
        if event.seq == 0 || event.seq > i64::MAX as u64 {
            return Err(CloudError::Unexpected);
        }
        let body = encode(&event.event)?;
        let mut conn = self.conn.lock().map_err(|_| CloudError::Unexpected)?;
        let tx = conn.transaction().map_err(db_error)?;
        let existing: Option<String> = tx
            .query_row(
                "SELECT body FROM chat_events WHERE run_id=?1 AND seq=?2",
                params![run_id, event.seq],
                |r| r.get(0),
            )
            .optional()
            .map_err(db_error)?;
        if let Some(existing) = existing {
            return if existing == body {
                Ok(())
            } else {
                Err(CloudError::Unexpected)
            };
        }
        let cursor: u64 = tx
            .query_row(
                "SELECT cursor FROM chat_runs WHERE run_id=?1",
                [run_id],
                |r| r.get(0),
            )
            .map_err(db_error)?;
        if event.seq != cursor + 1 {
            return Err(CloudError::Unexpected);
        }
        let bytes: i64 = tx
            .query_row(
                "SELECT event_bytes FROM chat_runs WHERE run_id=?1",
                [run_id],
                |r| r.get(0),
            )
            .map_err(db_error)?;
        if bytes + body.len() as i64 > MAX_RUN_BYTES {
            return Err(CloudError::Unavailable);
        }
        tx.execute(
            "INSERT INTO chat_events(run_id,seq,body) VALUES(?1,?2,?3)",
            params![run_id, event.seq, body],
        )
        .map_err(db_error)?;
        tx.execute(
            "UPDATE chat_runs SET cursor=?2,event_bytes=event_bytes+?3 WHERE run_id=?1",
            params![run_id, event.seq, body.len() as i64],
        )
        .map_err(db_error)?;
        tx.commit().map_err(db_error)
    }

    pub fn events(&self, run_id: &str, after: u64) -> Result<Vec<RunEvent>, CloudError> {
        let conn = self.conn.lock().map_err(|_| CloudError::Unexpected)?;
        let mut stmt = conn.prepare("SELECT seq,body FROM chat_events WHERE run_id=?1 AND seq>?2 ORDER BY seq LIMIT 250").map_err(db_error)?;
        let rows = stmt
            .query_map(params![run_id, after], |r| {
                Ok((r.get::<_, u64>(0)?, r.get::<_, String>(1)?))
            })
            .map_err(db_error)?;
        rows.map(|row| {
            let (seq, body) = row.map_err(db_error)?;
            Ok(RunEvent {
                seq,
                event: serde_json::from_str(&body).map_err(|_| CloudError::Unexpected)?,
            })
        })
        .collect()
    }

    pub fn cache_page(&self, page: &ThreadPage) -> Result<(), CloudError> {
        let thread = encode(&page.thread)?;
        let mut conn = self.conn.lock().map_err(|_| CloudError::Unexpected)?;
        let tx = conn.transaction().map_err(db_error)?;
        tx.execute("INSERT INTO chat_threads(thread_id,body) VALUES(?1,?2) ON CONFLICT(thread_id) DO UPDATE SET body=excluded.body", params![page.thread.id,thread]).map_err(db_error)?;
        for message in &page.messages {
            let body = encode(message)?;
            tx.execute("INSERT INTO chat_messages(thread_id,seq,message_id,body) VALUES(?1,?2,?3,?4) ON CONFLICT(thread_id,seq) DO UPDATE SET body=excluded.body", params![page.thread.id,message.seq,message.id,body]).map_err(db_error)?;
        }
        tx.commit().map_err(db_error)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    fn pending() -> PendingMessage {
        PendingMessage {
            project_id: "p".into(),
            design_id: "d".into(),
            thread_id: "t".into(),
            request: SendMessage {
                message_id: "m".into(),
                run_id: "r".into(),
                device_id: "device".into(),
                content: "test".into(),
                base_schematic_version: 0,
                kind: crate::conversations::RunKind::Planner,
                snapshot: None,
                references: vec![],
                selection: None,
                budget: json!({}),
            },
        }
    }
    fn run() -> Run {
        Run {
            run_id: "r".into(),
            project_id: "p".into(),
            design_id: "d".into(),
            thread_id: "t".into(),
            message_id: "m".into(),
            status: "running".into(),
            created_at: 1,
            updated_at: 1,
        }
    }
    fn store() -> ConversationStore {
        ConversationStore::init(Connection::open_in_memory().unwrap(), "account").unwrap()
    }

    #[test]
    fn retry_keeps_identity_and_different_body_cannot_reuse_id() {
        let store = store();
        let mut message = pending();
        store.enqueue(&message).unwrap();
        store.enqueue(&message).unwrap();
        assert_eq!(store.pending().unwrap().len(), 1);
        message.request.content = "different".into();
        assert!(store.enqueue(&message).is_err());
        store.acknowledge(&run()).unwrap();
        assert!(store.pending().unwrap().is_empty());
    }

    #[test]
    fn wrong_scope_ack_cannot_discard_pending_message() {
        let store = store();
        store.enqueue(&pending()).unwrap();
        let mut run = run();
        run.design_id = "other".into();
        assert!(store.acknowledge(&run).is_err());
        assert_eq!(store.pending().unwrap().len(), 1);
    }

    #[test]
    fn blocked_messages_require_explicit_recovery_and_remain_thread_scoped() {
        let store = store();
        store.enqueue(&pending()).unwrap();
        assert!(!store.discard_blocked("m").unwrap());
        store.block("m", "revision conflict").unwrap();
        assert!(store.pending().unwrap().is_empty());
        assert!(store.has_pending("t").unwrap());
        assert!(store.blocked("other").unwrap().is_empty());
        assert_eq!(store.blocked("t").unwrap()[0].request.content, "test");
        assert!(store.discard_blocked("m").unwrap());
        assert!(!store.has_pending("t").unwrap());
    }

    #[test]
    fn an_existing_run_cannot_be_reassigned_to_another_thread() {
        let store = store();
        store.acknowledge(&run()).unwrap();
        let mut changed = run();
        changed.thread_id = "another".into();
        assert!(store.acknowledge(&changed).is_err());
        assert_eq!(store.run("r").unwrap().unwrap().thread_id, "t");
    }

    #[test]
    fn restored_message_is_durable_until_requeued() {
        let path = std::env::temp_dir().join(format!(
            "tokito-chat-recovery-{}.sqlite3",
            uuid::Uuid::new_v4()
        ));
        {
            let store = ConversationStore::open(&path, "owner").unwrap();
            store.enqueue(&pending()).unwrap();
            assert!(!store.queued_for_thread("t").unwrap().unwrap().blocked);
            assert!(store.queued_for_thread("other").unwrap().is_none());
            store.block("m", "stale_revision").unwrap();
            assert!(store.queued_for_thread("t").unwrap().unwrap().blocked);
            assert_eq!(store.restore_blocked("m").unwrap(), "test");
            assert!(!store.has_pending("t").unwrap());
        }
        {
            let store = ConversationStore::open(&path, "owner").unwrap();
            assert_eq!(store.recovered_draft("t").unwrap().as_deref(), Some("test"));
            assert!(store.recovered_draft("other").unwrap().is_none());
            store.enqueue(&pending()).unwrap();
            assert!(store.recovered_draft("t").unwrap().is_none());
        }
        std::fs::remove_file(path).unwrap();
    }

    #[test]
    fn cached_history_is_bounded_and_confirmed_deletion_is_complete() {
        use crate::conversations::{Message, Thread};
        let store = store();
        let page = ThreadPage {
            thread: Thread {
                id: "t".into(),
                project_id: "p".into(),
                design_id: "d".into(),
                title: "Test".into(),
                created_at: 1,
                updated_at: 1,
            },
            messages: (1..=150)
                .map(|seq| Message {
                    seq,
                    id: format!("m-{seq}"),
                    run_id: "r".into(),
                    role: "user".into(),
                    content: "hello".into(),
                    created_at: 1,
                })
                .collect(),
            next_cursor: None,
            older_cursor: None,
            latest_run: None,
        };
        store.cache_page(&page).unwrap();
        let first = store.cached_page("t", 0).unwrap().unwrap();
        assert_eq!(first.messages.len(), 100);
        assert_eq!(first.next_cursor, Some(100));
        assert_eq!(
            store.cached_page("t", 100).unwrap().unwrap().messages.len(),
            50
        );
        store.enqueue(&pending()).unwrap();
        assert!(store.remove_thread("t").is_err());
        assert!(store.cached_page("t", 0).unwrap().is_some());
        store.acknowledge(&run()).unwrap();
        store
            .apply_event(
                "r",
                &RunEvent {
                    seq: 1,
                    event: json!({"type":"done"}),
                },
            )
            .unwrap();
        store.remove_thread("t").unwrap();
        assert!(store.cached_page("t", 0).unwrap().is_none());
        assert!(store.run("r").unwrap().is_none());
        assert!(store.events("r", 0).unwrap().is_empty());
    }

    #[test]
    fn events_are_atomic_idempotent_and_gap_checked() {
        let store = store();
        store.acknowledge(&run()).unwrap();
        let event = RunEvent {
            seq: 1,
            event: json!({"type":"assistant_text_delta","text":"one"}),
        };
        store.apply_event("r", &event).unwrap();
        store.apply_event("r", &event).unwrap();
        assert_eq!(store.cursor("r").unwrap(), 1);
        assert_eq!(store.events("r", 0).unwrap(), vec![event]);
        assert!(store
            .apply_event(
                "r",
                &RunEvent {
                    seq: 3,
                    event: json!({})
                }
            )
            .is_err());
        assert!(store
            .apply_event(
                "r",
                &RunEvent {
                    seq: 1,
                    event: json!({})
                }
            )
            .is_err());
        assert_eq!(store.cursor("r").unwrap(), 1);
    }

    #[test]
    fn account_binding_and_outbox_survive_process_restart() {
        let path =
            std::env::temp_dir().join(format!("tokito-chat-test-{}.sqlite", uuid::Uuid::new_v4()));
        {
            let store = ConversationStore::open(&path, "a").unwrap();
            store.enqueue(&pending()).unwrap();
        }
        assert!(matches!(
            ConversationStore::open(&path, "b"),
            Err(CloudError::Unauthorized)
        ));
        {
            let store = ConversationStore::open(&path, "a").unwrap();
            assert_eq!(store.pending().unwrap().len(), 1);
        }
        std::fs::remove_file(path).unwrap();
    }
}
