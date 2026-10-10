//! Server-owned edit review. Immutable commands survive uncertain responses.
use crate::{conversations::origin, CloudError};
use rusqlite::{params, Connection, OptionalExtension};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::{path::Path, sync::Mutex, time::Duration};

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
pub struct ReviewRequest {
    pub command_id: String,
    pub device_id: String,
    pub run_id: String,
    pub event_seq: u64,
    pub selected_indices: Vec<usize>,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct ReviewCommand {
    pub project: String,
    pub design: String,
    pub action: String,
    pub request: ReviewRequest,
    pub state: String,
    pub result: Option<Value>,
}
pub struct ReviewStore {
    conn: Mutex<Connection>,
}
fn db(_: rusqlite::Error) -> CloudError {
    CloudError::Unavailable
}
impl ReviewStore {
    pub fn open(path: &Path, owner: &str) -> Result<Self, CloudError> {
        if owner.is_empty() {
            return Err(CloudError::Unauthorized);
        }
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent).map_err(|_| CloudError::Unavailable)?;
        }
        let mut conn = Connection::open(path).map_err(db)?;
        conn.busy_timeout(Duration::from_secs(5)).map_err(db)?;
        conn.pragma_update(None, "journal_mode", "WAL")
            .map_err(db)?;
        conn.pragma_update(None, "synchronous", "FULL")
            .map_err(db)?;
        conn.execute_batch("CREATE TABLE IF NOT EXISTS review_owner(id INTEGER PRIMARY KEY CHECK(id=1), owner TEXT NOT NULL); CREATE TABLE IF NOT EXISTS review_commands(id TEXT PRIMARY KEY,project TEXT NOT NULL,design TEXT NOT NULL,run TEXT NOT NULL,seq INTEGER NOT NULL,body TEXT NOT NULL,state TEXT NOT NULL,result TEXT);").map_err(db)?;
        let tx = conn.transaction().map_err(db)?;
        let existing: Option<String> = tx
            .query_row("SELECT owner FROM review_owner WHERE id=1", [], |r| {
                r.get(0)
            })
            .optional()
            .map_err(db)?;
        match existing {
            Some(value) if value != owner => return Err(CloudError::Unauthorized),
            None => {
                tx.execute("INSERT INTO review_owner VALUES(1,?1)", [owner])
                    .map_err(db)?;
            }
            _ => {}
        }
        tx.commit().map_err(db)?;
        conn.execute_batch("CREATE TABLE IF NOT EXISTS proposal_cache(project TEXT NOT NULL,design TEXT NOT NULL,body TEXT NOT NULL,updated INTEGER NOT NULL,PRIMARY KEY(project,design));").map_err(db)?;
        Ok(Self {
            conn: Mutex::new(conn),
        })
    }
    pub fn enqueue(
        &self,
        project: &str,
        design: &str,
        action: &str,
        request: ReviewRequest,
    ) -> Result<ReviewCommand, CloudError> {
        segment(project)?;
        segment(design)?;
        segment(&request.run_id)?;
        segment(&request.command_id)?;
        segment(&request.device_id)?;
        if request.event_seq > i64::MAX as u64 {
            return Err(CloudError::Unexpected);
        }
        if !matches!(action, "apply" | "reject")
            || request.selected_indices.len() > 5000
            || (action == "apply" && request.selected_indices.is_empty())
            || (action == "reject" && !request.selected_indices.is_empty())
        {
            return Err(CloudError::Unexpected);
        }
        let mut indices = request.selected_indices.clone();
        indices.sort_unstable();
        indices.dedup();
        if indices.len() != request.selected_indices.len() {
            return Err(CloudError::Unexpected);
        }
        let mut conn = self.conn.lock().map_err(|_| CloudError::Unavailable)?;
        let tx = conn.transaction().map_err(db)?;
        // A second click/restart reuses the exact already-durable command, never
        // generates a fresh id while the first outcome remains uncertain.
        let prior:Option<String>=tx.query_row("SELECT body FROM review_commands WHERE project=?1 AND design=?2 AND run=?3 AND seq=?4 AND state='pending'",params![project,design,request.run_id,request.event_seq],|r|r.get(0)).optional().map_err(db)?;
        if let Some(prior) = prior {
            let prior: ReviewCommand =
                serde_json::from_str(&prior).map_err(|_| CloudError::Unexpected)?;
            if prior.action != action || prior.request.selected_indices != request.selected_indices
            {
                return Err(CloudError::Conflict {
                    current_version: 0,
                    current_payload: "{\"error\":{\"code\":\"review_pending\"}}".into(),
                });
            }
            return Ok(prior);
        }
        let count: i64 = tx
            .query_row(
                "SELECT COUNT(*) FROM review_commands WHERE state='pending'",
                [],
                |r| r.get(0),
            )
            .map_err(db)?;
        if count >= 100 {
            return Err(CloudError::Unavailable);
        }
        let command = ReviewCommand {
            project: project.into(),
            design: design.into(),
            action: action.into(),
            request,
            state: "pending".into(),
            result: None,
        };
        let body = serde_json::to_string(&command).map_err(|_| CloudError::Unexpected)?;
        tx.execute(
            "INSERT INTO review_commands VALUES(?1,?2,?3,?4,?5,?6,'pending',NULL)",
            params![
                command.request.command_id,
                project,
                design,
                command.request.run_id,
                command.request.event_seq,
                body
            ],
        )
        .map_err(db)?;
        tx.commit().map_err(db)?;
        Ok(command)
    }
    pub fn pending_scopes(&self) -> Result<Vec<(String, String)>, CloudError> {
        let conn = self.conn.lock().map_err(|_| CloudError::Unexpected)?;
        let mut stmt = conn.prepare("SELECT DISTINCT project,design FROM review_commands WHERE state='pending' ORDER BY rowid").map_err(db)?;
        let rows = stmt
            .query_map([], |row| Ok((row.get(0)?, row.get(1)?)))
            .map_err(db)?
            .collect::<Result<Vec<_>, _>>()
            .map_err(db);
        rows
    }
    /// Delivery order is oldest first, independently of the newest-first UI list.
    pub fn pending_in_scope(
        &self,
        project: &str,
        design: &str,
    ) -> Result<Vec<ReviewCommand>, CloudError> {
        let conn = self.conn.lock().map_err(|_| CloudError::Unexpected)?;
        let mut stmt = conn.prepare("SELECT body FROM review_commands WHERE project=?1 AND design=?2 AND state='pending' ORDER BY rowid").map_err(db)?;
        let rows = stmt
            .query_map(params![project, design], |row| row.get::<_, String>(0))
            .map_err(db)?;
        rows.map(|row| serde_json::from_str(&row.map_err(db)?).map_err(|_| CloudError::Unexpected))
            .collect()
    }
    pub fn cache_proposals(
        &self,
        project: &str,
        design: &str,
        value: &Value,
    ) -> Result<(), CloudError> {
        validate_proposals(project, design, value)?;
        let conn = self.conn.lock().map_err(|_| CloudError::Unexpected)?;
        conn.execute("INSERT INTO proposal_cache VALUES(?1,?2,?3,unixepoch()) ON CONFLICT(project,design) DO UPDATE SET body=excluded.body,updated=excluded.updated",params![project,design,value.to_string()]).map_err(db)?;
        conn.execute("DELETE FROM proposal_cache WHERE rowid NOT IN (SELECT rowid FROM proposal_cache ORDER BY updated DESC,rowid DESC LIMIT 16)",[]).map_err(db)?;
        Ok(())
    }
    pub fn cached_proposals(
        &self,
        project: &str,
        design: &str,
    ) -> Result<Option<Value>, CloudError> {
        let conn = self.conn.lock().map_err(|_| CloudError::Unexpected)?;
        let body: Option<String> = conn
            .query_row(
                "SELECT body FROM proposal_cache WHERE project=?1 AND design=?2",
                params![project, design],
                |r| r.get(0),
            )
            .optional()
            .map_err(db)?;
        body.map(|body| {
            let value = serde_json::from_str(&body).map_err(|_| CloudError::Unexpected)?;
            validate_proposals(project, design, &value)?;
            Ok(value)
        })
        .transpose()
    }
    pub fn list(&self, project: &str, design: &str) -> Result<Vec<ReviewCommand>, CloudError> {
        let conn = self.conn.lock().map_err(|_| CloudError::Unavailable)?;
        let mut statement=conn.prepare("SELECT body,state,result FROM review_commands WHERE project=?1 AND design=?2 ORDER BY (state='pending') DESC,rowid DESC LIMIT 200").map_err(db)?;
        let rows = statement
            .query_map(params![project, design], |r| {
                Ok((
                    r.get::<_, String>(0)?,
                    r.get::<_, String>(1)?,
                    r.get::<_, Option<String>>(2)?,
                ))
            })
            .map_err(db)?;
        rows.map(|row| {
            let (body, state, result) = row.map_err(db)?;
            let mut c: ReviewCommand =
                serde_json::from_str(&body).map_err(|_| CloudError::Unexpected)?;
            c.state = state;
            c.result = result
                .map(|v| serde_json::from_str(&v).map_err(|_| CloudError::Unexpected))
                .transpose()?;
            Ok(c)
        })
        .collect()
    }
    pub fn finish(&self, id: &str, state: &str, result: &Value) -> Result<(), CloudError> {
        if !matches!(state, "confirmed" | "blocked") {
            return Err(CloudError::Unexpected);
        }
        // Canonical resources are persisted by sync, not duplicated here.
        let mut receipt = result.clone();
        if let Some(object) = receipt.as_object_mut() {
            object.remove("resources");
        }
        let mut conn = self.conn.lock().map_err(|_| CloudError::Unavailable)?;
        let tx = conn.transaction().map_err(db)?;
        tx.execute(
            "UPDATE review_commands SET state=?2,result=?3 WHERE id=?1 AND state='pending'",
            params![id, state, receipt.to_string()],
        )
        .map_err(db)?;
        tx.execute("DELETE FROM review_commands WHERE state!='pending' AND rowid NOT IN (SELECT rowid FROM review_commands WHERE state!='pending' ORDER BY rowid DESC LIMIT 200)",[]).map_err(db)?;
        tx.commit().map_err(db)?;
        Ok(())
    }
}
fn validate_proposals(project: &str, design: &str, value: &Value) -> Result<(), CloudError> {
    let rows = value["proposals"]
        .as_array()
        .ok_or(CloudError::Unexpected)?;
    if rows.len() > 200
        || value.to_string().len() > 2 * 1024 * 1024
        || rows.iter().any(|p| {
            p["project_id"] != project
                || p["design_id"] != design
                || p["run_id"].as_str().is_none()
                || p["event_seq"].as_u64().is_none()
                || p["base_schematic_version"].as_u64().is_none()
                || p["base_bom_version"].as_u64().is_none()
                || !matches!(
                    p["status"].as_str(),
                    Some("pending" | "applied" | "rejected")
                )
                || !matches!(p["payload"]["kind"].as_str(), Some("schematic" | "bom"))
                || p["payload"]["ops"]
                    .as_array()
                    .is_none_or(|ops| ops.len() > 5000 || ops.iter().any(|op| !op.is_object()))
        })
    {
        return Err(CloudError::Unexpected);
    }
    Ok(())
}
fn segment(value: &str) -> Result<&str, CloudError> {
    if value.is_empty()
        || value.len() > 128
        || !value
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
    {
        Err(CloudError::Unexpected)
    } else {
        Ok(value)
    }
}
async fn http(
    base: &str,
    token: &str,
    project: &str,
    design: &str,
    command: Option<&ReviewCommand>,
) -> Result<(u16, Value), CloudError> {
    use futures_util::StreamExt;
    let url = format!(
        "{}/v1/projects/{}/designs/{}/proposals{}",
        origin(base)?,
        segment(project)?,
        segment(design)?,
        command
            .map(|c| format!("/{}", c.action))
            .unwrap_or_default()
    );
    let client = reqwest::Client::builder()
        .redirect(reqwest::redirect::Policy::none())
        .connect_timeout(Duration::from_secs(15))
        .timeout(Duration::from_secs(45))
        .build()
        .map_err(|_| CloudError::Unexpected)?;
    let request = if let Some(c) = command {
        client.post(url).json(&c.request)
    } else {
        client.get(url)
    };
    let response = request
        .bearer_auth(token)
        .send()
        .await
        .map_err(|e| CloudError::Network(e.to_string()))?;
    let status = response.status().as_u16();
    let mut body = Vec::new();
    let mut stream = response.bytes_stream();
    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(|_| CloudError::Unexpected)?;
        if body.len() + chunk.len() > 8 * 1024 * 1024 {
            return Err(CloudError::Unexpected);
        }
        body.extend_from_slice(&chunk);
    }
    match status {
        401 | 403 => Err(CloudError::Unauthorized),
        404 => Err(CloudError::Unavailable),
        429 => Err(CloudError::RateLimited),
        500..=599 => Err(CloudError::Network(
            "Server could not confirm the review".into(),
        )),
        _ => Ok((
            status,
            serde_json::from_slice(&body).map_err(|_| CloudError::Unexpected)?,
        )),
    }
}
pub async fn list(
    base: &str,
    token: &str,
    project: &str,
    design: &str,
) -> Result<Value, CloudError> {
    let (status, value) = http(base, token, project, design, None).await?;
    if status != 200 {
        return Err(CloudError::Unexpected);
    }
    Ok(value)
}
pub async fn submit(
    base: &str,
    token: &str,
    command: &ReviewCommand,
) -> Result<(&'static str, Value), CloudError> {
    let response = http(
        base,
        token,
        &command.project,
        &command.design,
        Some(command),
    )
    .await;
    let (status, value) = match response {
        // A removed project/design/proposal cannot recover by automatic retry.
        // Preserve its command and expose an explicit blocked decision instead.
        Err(CloudError::Unavailable) => {
            return Ok((
                "blocked",
                serde_json::json!({"error":{"code":"proposal_unavailable"}}),
            ))
        }
        other => other?,
    };
    match status {
        200 => {
            if value["command_id"] != command.request.command_id
                || value["run_id"] != command.request.run_id
                || value["event_seq"].as_u64() != Some(command.request.event_seq)
            {
                return Err(CloudError::Unexpected);
            }
            let result = &value["result"];
            if command.action == "reject" {
                if result["status"] != "rejected" {
                    return Err(CloudError::Unexpected);
                }
            } else {
                if result["status"] != "applied"
                    || result["rejected"].as_array().is_none_or(|v| !v.is_empty())
                {
                    return Err(CloudError::Unexpected);
                }
                let mut completed: Vec<usize> = serde_json::from_value(result["applied"].clone())
                    .map_err(|_| CloudError::Unexpected)?;
                let unchanged: Vec<usize> = serde_json::from_value(result["unchanged"].clone())
                    .map_err(|_| CloudError::Unexpected)?;
                completed.extend(unchanged);
                completed.sort_unstable();
                let mut expected = command.request.selected_indices.clone();
                expected.sort_unstable();
                if completed != expected {
                    return Err(CloudError::Unexpected);
                }
            }
            Ok(("confirmed", value))
        }
        400 | 409 | 422 => Ok(("blocked", value)),
        _ => Err(CloudError::Unexpected),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn durable_command_replayed_unchanged_and_scoped() {
        let path = std::env::temp_dir().join(format!("review-{}.db", uuid::Uuid::new_v4()));
        let request = ReviewRequest {
            command_id: uuid::Uuid::new_v4().to_string(),
            device_id: "device".into(),
            run_id: "run".into(),
            event_seq: 7,
            selected_indices: vec![0, 1],
        };
        {
            let s = ReviewStore::open(&path, "owner").unwrap();
            s.enqueue("p", "d", "apply", request.clone()).unwrap();
        }
        let s = ReviewStore::open(&path, "owner").unwrap();
        let mut next = request.clone();
        next.command_id = uuid::Uuid::new_v4().to_string();
        assert_eq!(s.enqueue("p", "d", "apply", next).unwrap().request, request);
        let mut changed = request.clone();
        changed.selected_indices = vec![1];
        assert!(matches!(
            s.enqueue("p", "d", "apply", changed),
            Err(CloudError::Conflict { .. })
        ));
        assert!(s.list("other", "d").unwrap().is_empty());
        assert!(ReviewStore::open(&path, "another").is_err());
        s.finish(
            &request.command_id,
            "blocked",
            &serde_json::json!({"error":{"code":"proposal_stale"}}),
        )
        .unwrap();
        assert_eq!(s.list("p", "d").unwrap()[0].state, "blocked");
        drop(s);
        let _ = std::fs::remove_file(path);
    }

    #[tokio::test]
    async fn authenticated_replay_and_conflict_contract() {
        use wiremock::{
            matchers::{body_json, header, method, path},
            Mock, MockServer, ResponseTemplate,
        };
        let server = MockServer::start().await;
        let command = ReviewCommand {
            project: "p".into(),
            design: "d".into(),
            action: "apply".into(),
            request: ReviewRequest {
                command_id: "immutable".into(),
                device_id: "device".into(),
                run_id: "run".into(),
                event_seq: 1,
                selected_indices: vec![0],
            },
            state: "pending".into(),
            result: None,
        };
        let receipt = serde_json::json!({"command_id":"immutable","run_id":"run","event_seq":1,"resources":[],"result":{"status":"applied","applied":[0],"unchanged":[],"rejected":[],"erc":[]}});
        Mock::given(method("POST"))
            .and(path("/v1/projects/p/designs/d/proposals/apply"))
            .and(header("authorization", "Bearer token"))
            .and(body_json(serde_json::to_value(&command.request).unwrap()))
            .respond_with(ResponseTemplate::new(200).set_body_json(&receipt))
            .expect(2)
            .mount(&server)
            .await;
        assert_eq!(
            submit(&server.uri(), "token", &command).await.unwrap(),
            ("confirmed", receipt.clone())
        );
        assert_eq!(
            submit(&server.uri(), "token", &command).await.unwrap(),
            ("confirmed", receipt)
        );
        server.verify().await;
        server.reset().await;
        Mock::given(method("POST"))
            .respond_with(
                ResponseTemplate::new(409)
                    .set_body_json(serde_json::json!({"error":{"code":"proposal_stale"}})),
            )
            .mount(&server)
            .await;
        assert_eq!(
            submit(&server.uri(), "token", &command).await.unwrap().0,
            "blocked"
        );
        server.reset().await;
        Mock::given(method("POST"))
            .respond_with(ResponseTemplate::new(404).set_body_string("private upstream details"))
            .mount(&server)
            .await;
        assert_eq!(
            submit(&server.uri(), "token", &command).await.unwrap(),
            (
                "blocked",
                serde_json::json!({"error":{"code":"proposal_unavailable"}})
            )
        );
    }
    #[tokio::test]
    async fn error_status_is_classified_before_decoding_response_body() {
        use wiremock::{matchers::method, Mock, MockServer, ResponseTemplate};
        let server = MockServer::start().await;
        for (status, body) in [
            (401, ""),
            (403, "denied"),
            (503, "<html>unavailable</html>"),
            (404, "missing"),
            (200, "invalid JSON"),
        ] {
            server.reset().await;
            Mock::given(method("GET"))
                .respond_with(ResponseTemplate::new(status).set_body_string(body))
                .mount(&server)
                .await;
            let error = list(&server.uri(), "token", "p", "d").await.unwrap_err();
            match status {
                401 | 403 => assert_eq!(error, CloudError::Unauthorized),
                503 => assert!(matches!(error, CloudError::Network(_))),
                404 => assert_eq!(error, CloudError::Unavailable),
                200 => assert_eq!(error, CloudError::Unexpected),
                _ => unreachable!(),
            }
        }
    }

    #[tokio::test]
    async fn mismatched_or_partial_receipt_never_acknowledges_command() {
        use wiremock::{matchers::method, Mock, MockServer, ResponseTemplate};
        let server = MockServer::start().await;
        let command = ReviewCommand {
            project: "p".into(),
            design: "d".into(),
            action: "apply".into(),
            request: ReviewRequest {
                command_id: "id".into(),
                device_id: "device".into(),
                run_id: "run".into(),
                event_seq: 1,
                selected_indices: vec![0, 1],
            },
            state: "pending".into(),
            result: None,
        };
        Mock::given(method("POST")).respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({"command_id":"id","run_id":"run","event_seq":1,"result":{"status":"applied","applied":[0],"unchanged":[],"rejected":[]}}))).mount(&server).await;
        assert_eq!(
            submit(&server.uri(), "token", &command).await.unwrap_err(),
            CloudError::Unexpected
        );
    }
}
