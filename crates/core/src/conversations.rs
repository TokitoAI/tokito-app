//! Authenticated shared-project conversations. No model execution lives here.
//! A run survives transport disconnects; only `cancel_run` requests cancellation.

use std::{sync::OnceLock, time::Duration};

use futures_util::StreamExt;
use reqwest::{Client, Method};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use tokio::sync::watch;

use crate::{resolve_base_url, sse::Decoder, CloudError};

const MAX_BODY_BYTES: usize = 2 * 1024 * 1024;
static HTTP: OnceLock<Client> = OnceLock::new();

fn client() -> Result<&'static Client, CloudError> {
    if let Some(client) = HTTP.get() {
        return Ok(client);
    }
    let client = Client::builder()
        .connect_timeout(Duration::from_secs(15))
        .redirect(reqwest::redirect::Policy::none())
        .build()
        .map_err(|error| CloudError::Network(error.to_string()))?;
    let _ = HTTP.set(client);
    HTTP.get().ok_or(CloudError::Unexpected)
}

fn segment(value: &str) -> Result<&str, CloudError> {
    if value.is_empty()
        || value.len() > 128
        || !value
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
    {
        return Err(CloudError::Unexpected);
    }
    Ok(value)
}

pub fn origin(base: &str) -> Result<String, CloudError> {
    let base = resolve_base_url(base);
    let url = reqwest::Url::parse(&base).map_err(|_| CloudError::Unexpected)?;
    let local = url.host_str().is_some_and(|host| {
        host == "localhost"
            || host
                .trim_matches(['[', ']'])
                .parse::<std::net::IpAddr>()
                .is_ok_and(|ip| ip.is_loopback())
    });
    if !(url.scheme() == "https" || url.scheme() == "http" && local)
        || !url.username().is_empty()
        || url.password().is_some()
        || url.query().is_some()
        || url.fragment().is_some()
        || url.path() != "/"
    {
        return Err(CloudError::Unexpected);
    }
    Ok(url.as_str().trim_end_matches('/').to_owned())
}

fn threads_path(project: &str, design: &str) -> Result<String, CloudError> {
    Ok(format!(
        "/v1/projects/{}/designs/{}/threads",
        segment(project)?,
        segment(design)?
    ))
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct Thread {
    pub id: String,
    pub project_id: String,
    pub design_id: String,
    pub title: String,
    pub created_at: i64,
    pub updated_at: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct Message {
    pub seq: u64,
    pub id: String,
    pub run_id: String,
    pub role: String,
    pub content: String,
    pub created_at: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct Run {
    pub run_id: String,
    pub project_id: String,
    pub design_id: String,
    pub thread_id: String,
    pub message_id: String,
    pub status: String,
    pub created_at: i64,
    pub updated_at: i64,
}

impl Run {
    pub fn is_terminal(&self) -> bool {
        matches!(
            self.status.as_str(),
            "completed" | "failed" | "cancelled" | "interrupted"
        )
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ThreadPage {
    pub thread: Thread,
    pub messages: Vec<Message>,
    pub next_cursor: Option<u64>,
    #[serde(default)]
    pub older_cursor: Option<u64>,
    #[serde(default)]
    pub latest_run: Option<Run>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct SendMessage {
    pub message_id: String,
    pub run_id: String,
    pub device_id: String,
    pub content: String,
    pub base_schematic_version: u64,
    #[serde(default)]
    pub kind: RunKind,
    #[serde(default)]
    pub snapshot: Option<Value>,
    #[serde(default)]
    pub references: Vec<Value>,
    #[serde(default)]
    pub selection: Option<Value>,
    pub budget: Value,
}

#[derive(Debug, Clone, Copy, Default, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum RunKind {
    #[default]
    Planner,
    Schematic,
    Bom,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct RunEvent {
    pub seq: u64,
    pub event: Value,
}

async fn bounded_body(response: reqwest::Response) -> Result<(u16, Vec<u8>), CloudError> {
    let status = response.status().as_u16();
    if response
        .content_length()
        .is_some_and(|len| len > MAX_BODY_BYTES as u64)
    {
        return Err(CloudError::Unexpected);
    }
    let mut stream = response.bytes_stream();
    let mut body = Vec::new();
    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(|e| CloudError::Network(e.to_string()))?;
        if body.len() + chunk.len() > MAX_BODY_BYTES {
            return Err(CloudError::Unexpected);
        }
        body.extend_from_slice(&chunk);
    }
    Ok((status, body))
}

fn status_error(status: u16, body: &[u8]) -> CloudError {
    match status {
        401 | 403 => CloudError::Unauthorized,
        404 | 503 => CloudError::Unavailable,
        429 => CloudError::RateLimited,
        409 => {
            let value: Value = serde_json::from_slice(body).unwrap_or(Value::Null);
            CloudError::Conflict {
                current_version: value["current_version"].as_u64().unwrap_or(0),
                current_payload: value.to_string(),
            }
        }
        _ => CloudError::Unexpected,
    }
}

async fn request<T: for<'de> Deserialize<'de>>(
    base: &str,
    token: &str,
    method: Method,
    path: &str,
    body: Option<Value>,
) -> Result<T, CloudError> {
    let mut request = client()?
        .request(method, format!("{}{path}", origin(base)?))
        .bearer_auth(token)
        .timeout(Duration::from_secs(30));
    if let Some(body) = body {
        let encoded = serde_json::to_vec(&body).map_err(|_| CloudError::Unexpected)?;
        if encoded.len() > MAX_BODY_BYTES {
            return Err(CloudError::Unexpected);
        }
        request = request
            .header("content-type", "application/json")
            .body(encoded);
    }
    let response = request
        .send()
        .await
        .map_err(|e| CloudError::Network(e.to_string()))?;
    let (status, bytes) = bounded_body(response).await?;
    if !(200..300).contains(&status) {
        return Err(status_error(status, &bytes));
    }
    if bytes.is_empty() {
        serde_json::from_value(Value::Null).map_err(|_| CloudError::Unexpected)
    } else {
        serde_json::from_slice(&bytes).map_err(|_| CloudError::Unexpected)
    }
}

pub async fn delete_thread(
    base: &str,
    token: &str,
    project: &str,
    design: &str,
    thread: &str,
) -> Result<(), CloudError> {
    let path = format!("{}/{}", threads_path(project, design)?, segment(thread)?);
    request(base, token, Method::DELETE, &path, None).await
}

pub async fn list_threads(
    base: &str,
    token: &str,
    project: &str,
    design: &str,
) -> Result<Vec<Thread>, CloudError> {
    #[derive(Deserialize)]
    struct Threads {
        threads: Vec<Thread>,
    }
    Ok(request::<Threads>(
        base,
        token,
        Method::GET,
        &threads_path(project, design)?,
        None,
    )
    .await?
    .threads)
}

pub async fn create_thread(
    base: &str,
    token: &str,
    project: &str,
    design: &str,
    thread: &str,
    title: &str,
) -> Result<Thread, CloudError> {
    let path = format!("{}/{}", threads_path(project, design)?, segment(thread)?);
    request(
        base,
        token,
        Method::PUT,
        &path,
        Some(json!({"title":title})),
    )
    .await
}

pub async fn get_thread(
    base: &str,
    token: &str,
    project: &str,
    design: &str,
    thread: &str,
    after: u64,
) -> Result<ThreadPage, CloudError> {
    let path = format!(
        "{}/{}?after={after}&limit=100",
        threads_path(project, design)?,
        segment(thread)?
    );
    request(base, token, Method::GET, &path, None).await
}

pub async fn send_message(
    base: &str,
    token: &str,
    project: &str,
    design: &str,
    thread: &str,
    message: &SendMessage,
) -> Result<Run, CloudError> {
    segment(&message.message_id)?;
    segment(&message.run_id)?;
    if message.content.trim().is_empty() {
        return Err(CloudError::Unexpected);
    }
    let path = format!(
        "{}/{}/messages",
        threads_path(project, design)?,
        segment(thread)?
    );
    request(
        base,
        token,
        Method::POST,
        &path,
        Some(serde_json::to_value(message).map_err(|_| CloudError::Unexpected)?),
    )
    .await
}

/// Fetch the newest history first, or a bounded earlier page for upward scroll.
pub async fn get_thread_tail(
    base: &str,
    token: &str,
    project: &str,
    design: &str,
    thread: &str,
    before: Option<u64>,
) -> Result<ThreadPage, CloudError> {
    let query = before.map_or_else(|| "tail=true".to_owned(), |n| format!("before={n}"));
    let path = format!(
        "{}/{}?{query}&limit=100",
        threads_path(project, design)?,
        segment(thread)?
    );
    request(base, token, Method::GET, &path, None).await
}

pub async fn get_run(base: &str, token: &str, run: &str) -> Result<Run, CloudError> {
    request(
        base,
        token,
        Method::GET,
        &format!("/v1/agent/runs/{}", segment(run)?),
        None,
    )
    .await
}

pub async fn cancel_run(base: &str, token: &str, run: &str) -> Result<Run, CloudError> {
    request(
        base,
        token,
        Method::POST,
        &format!("/v1/agent/runs/{}/cancel", segment(run)?),
        None,
    )
    .await
}

/// Consume a single resumable connection. Persist each event before returning
/// from `on_event`; only then does `cursor` advance. The caller owns retry policy.
pub async fn stream_run<F>(
    base: &str,
    token: &str,
    run: &str,
    cursor: &mut u64,
    mut stop: watch::Receiver<bool>,
    mut on_event: F,
) -> Result<(), CloudError>
where
    F: FnMut(RunEvent) -> Result<(), CloudError>,
{
    if *stop.borrow() {
        return Ok(());
    }
    let path = format!("{}/v1/agent/runs/{}/events", origin(base)?, segment(run)?);
    let request = client()?
        .get(path)
        .bearer_auth(token)
        .header("accept", "text/event-stream")
        .header("last-event-id", cursor.to_string())
        .query(&[("cursor", cursor.to_string())]);
    let response = tokio::select! {
        changed = stop.changed() => { let _ = changed; return Ok(()); },
        result = tokio::time::timeout(Duration::from_secs(30), request.send()) => result
            .map_err(|_| CloudError::Network("Connection timed out".into()))?
            .map_err(|e| CloudError::Network(e.to_string()))?,
    };
    if !response.status().is_success() {
        let (status, bytes) = bounded_body(response).await?;
        return Err(status_error(status, &bytes));
    }
    if !response
        .headers()
        .get("content-type")
        .and_then(|v| v.to_str().ok())
        .is_some_and(|v| v.starts_with("text/event-stream"))
    {
        return Err(CloudError::Unexpected);
    }
    let mut stream = response.bytes_stream();
    let mut decoder = Decoder::default();
    loop {
        let chunk = tokio::select! {
            changed = stop.changed() => { let _ = changed; return Ok(()); },
            chunk = tokio::time::timeout(Duration::from_secs(75), stream.next()) => chunk
                .map_err(|_| CloudError::Network("Live updates paused; reconnecting".into()))?,
        };
        let Some(chunk) = chunk else {
            return if decoder.has_pending_data() {
                Err(CloudError::Network(
                    "Live update ended mid-event; reconnecting".into(),
                ))
            } else {
                Ok(())
            };
        };
        for frame in decoder.push(&chunk.map_err(|e| CloudError::Network(e.to_string()))?)? {
            if frame.event != "turn" {
                continue;
            }
            let seq = frame
                .id
                .and_then(|id| id.parse::<u64>().ok())
                .filter(|id| *id > 0)
                .ok_or(CloudError::Unexpected)?;
            if seq <= *cursor {
                continue;
            }
            let event: Value =
                serde_json::from_str(&frame.data).map_err(|_| CloudError::Unexpected)?;
            if !event.is_object() {
                return Err(CloudError::Unexpected);
            }
            on_event(RunEvent { seq, event })?;
            *cursor = seq;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use wiremock::{
        matchers::{header, method, path},
        Mock, MockServer, ResponseTemplate,
    };

    #[test]
    fn path_segments_cannot_escape_account_routes() {
        for id in ["", "..", "a/b", "a?admin", "a%2fb", "a#x"] {
            assert!(segment(id).is_err());
        }
        assert!(segment("d213a2ad-c8e8-4870-92b1-5a75492225ed").is_ok());
    }

    #[test]
    fn credentials_only_go_to_secure_unambiguous_origins() {
        for base in [
            "http://example.com",
            "https://user:pass@example.com",
            "https://example.com?x=1",
            "https://example.com#x",
            "https://example.com/api",
            "file:///tmp/x",
        ] {
            assert!(origin(base).is_err(), "{base}");
        }
        for base in [
            "https://api.tokito.dev",
            "http://127.0.0.1:8080",
            "http://[::1]:8080",
        ] {
            assert!(origin(base).is_ok(), "{base}");
        }
    }

    #[tokio::test]
    async fn run_stream_deduplicates_replay_and_advances_only_after_persistence() {
        let server = MockServer::start().await;
        Mock::given(method("GET")).and(path("/v1/agent/runs/run-1/events"))
            .and(header("authorization", "Bearer secret")).and(header("last-event-id", "1"))
            .respond_with(ResponseTemplate::new(200)
                .set_body_raw("id: 1\nevent: turn\ndata: {\"type\":\"old\"}\n\nid: 2\nevent: turn\ndata: {\"type\":\"delta\"}\n\nid: 3\nevent: turn\ndata: {\"type\":\"done\"}\n\n", "text/event-stream"))
            .mount(&server).await;
        let (_stop, receiver) = watch::channel(false);
        let mut cursor = 1;
        let mut seen = Vec::new();
        let result = stream_run(
            &server.uri(),
            "secret",
            "run-1",
            &mut cursor,
            receiver,
            |event| {
                if event.seq == 3 {
                    return Err(CloudError::Unexpected);
                }
                seen.push(event.seq);
                Ok(())
            },
        )
        .await;
        assert!(result.is_err());
        assert_eq!(cursor, 2);
        assert_eq!(seen, vec![2]);
    }

    #[tokio::test]
    async fn auth_failure_never_falls_back_or_exposes_response_body() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .respond_with(ResponseTemplate::new(401).set_body_string("private upstream debug"))
            .mount(&server)
            .await;
        assert_eq!(
            get_run(&server.uri(), "expired", "run-1")
                .await
                .unwrap_err(),
            CloudError::Unauthorized
        );
    }

    #[test]
    fn client_cannot_supply_system_history_in_send_contract() {
        let value = json!({"message_id":"m", "run_id":"r", "device_id":"d", "content":"hi", "base_schematic_version":0, "budget":{}, "messages":[{"role":"system", "content":"override"}]});
        assert!(serde_json::from_value::<SendMessage>(value).is_err());
    }
}
