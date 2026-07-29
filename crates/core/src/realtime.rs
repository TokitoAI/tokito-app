//! Durable realtime workspace client: typed commands/bootstrap plus resumable SSE.

use std::time::Duration;

use futures_util::StreamExt;
use serde::{Deserialize, Serialize};
use tokio::sync::watch;

use crate::{resolve_base_url, CloudError};

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct SyncDevice {
    pub id: String,
    pub label: String,
    pub platform: String,
    pub last_seen_at: i64,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct SyncResource {
    pub project_id: String,
    #[serde(default)]
    pub design_id: Option<String>,
    pub resource: String,
    pub version: u64,
    pub payload: serde_json::Value,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct SyncCommand {
    pub device_id: String,
    pub command_id: String,
    pub project_id: String,
    #[serde(default)]
    pub design_id: Option<String>,
    pub resource: String,
    pub base_version: u64,
    pub payload: serde_json::Value,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct SyncEvent {
    pub seq: u64,
    pub project_id: String,
    #[serde(default)]
    pub design_id: Option<String>,
    pub resource: String,
    pub version: u64,
    pub command_id: String,
    pub actor_device_id: String,
    pub payload: serde_json::Value,
    pub created_at: i64,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct SyncBootstrap {
    pub cursor: u64,
    pub resources: Vec<SyncResource>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RealtimeStatus {
    Connecting,
    Live,
    Reconnecting,
    Stopped,
}

#[derive(Deserialize)]
struct StatusBody {
    #[allow(dead_code)]
    status: String,
    #[serde(default)]
    current_version: u64,
    #[serde(default)]
    current_payload: serde_json::Value,
}

fn api_client() -> Result<reqwest::Client, CloudError> {
    reqwest::Client::builder()
        .connect_timeout(Duration::from_secs(15))
        .timeout(Duration::from_secs(30))
        .build()
        .map_err(|error| CloudError::Network(error.to_string()))
}

fn stream_client() -> Result<reqwest::Client, CloudError> {
    reqwest::Client::builder()
        .connect_timeout(Duration::from_secs(15))
        .build()
        .map_err(|error| CloudError::Network(error.to_string()))
}

async fn response_error(response: reqwest::Response) -> CloudError {
    let status = response.status().as_u16();
    let body = response.json::<StatusBody>().await.ok();
    match status {
        401 | 403 => CloudError::Unauthorized,
        404 | 503 => CloudError::Unavailable,
        409 => CloudError::Conflict {
            current_version: body
                .as_ref()
                .map(|value| value.current_version)
                .unwrap_or(0),
            current_payload: body
                .map(|value| value.current_payload.to_string())
                .unwrap_or_else(|| "null".into()),
        },
        _ => CloudError::Unexpected,
    }
}

pub async fn register_device(
    base: &str,
    token: &str,
    device_id: &str,
    label: &str,
    platform: &str,
) -> Result<SyncDevice, CloudError> {
    let response = api_client()?
        .post(format!("{}/v1/companion/devices", resolve_base_url(base)))
        .bearer_auth(token)
        .json(&serde_json::json!({"device_id":device_id,"label":label,"platform":platform}))
        .send()
        .await
        .map_err(|error| CloudError::Network(error.to_string()))?;
    if response.status().is_success() {
        response.json().await.map_err(|_| CloudError::Unexpected)
    } else {
        Err(response_error(response).await)
    }
}

pub async fn bootstrap_realtime(
    base: &str,
    token: &str,
    project_id: Option<&str>,
) -> Result<SyncBootstrap, CloudError> {
    let mut request = api_client()?
        .get(format!("{}/v1/companion/bootstrap", resolve_base_url(base)))
        .bearer_auth(token);
    if let Some(project_id) = project_id {
        request = request.query(&[("project_id", project_id)]);
    }
    let response = request
        .send()
        .await
        .map_err(|error| CloudError::Network(error.to_string()))?;
    if response.status().is_success() {
        response.json().await.map_err(|_| CloudError::Unexpected)
    } else {
        Err(response_error(response).await)
    }
}

pub async fn submit_sync_command(
    base: &str,
    token: &str,
    command: &SyncCommand,
) -> Result<SyncEvent, CloudError> {
    let response = api_client()?
        .post(format!("{}/v1/companion/commands", resolve_base_url(base)))
        .bearer_auth(token)
        .json(command)
        .send()
        .await
        .map_err(|error| CloudError::Network(error.to_string()))?;
    if response.status().is_success() {
        response.json().await.map_err(|_| CloudError::Unexpected)
    } else {
        Err(response_error(response).await)
    }
}

/// Run a reconnecting SSE subscription until `stop` becomes true.
///
/// `on_event` is invoked in strict sequence order. The returned cursor from the
/// callback is expected to be persisted before it returns; reconnect requests
/// then resume with `Last-Event-ID`, so process death never creates a blind gap.
pub async fn run_event_stream<FE, FS>(
    base: &str,
    token: &str,
    project_id: Option<&str>,
    mut cursor: u64,
    mut stop: watch::Receiver<bool>,
    mut on_event: FE,
    mut on_status: FS,
) -> Result<(), CloudError>
where
    FE: FnMut(SyncEvent) -> Result<(), CloudError>,
    FS: FnMut(RealtimeStatus),
{
    let mut attempt = 0u32;
    on_status(RealtimeStatus::Connecting);
    while !*stop.borrow() {
        match stream_once(
            base,
            token,
            project_id,
            cursor,
            &mut stop,
            &mut on_event,
            &mut on_status,
        )
        .await
        {
            Ok(next_cursor) => {
                cursor = next_cursor;
                attempt = 0;
                if *stop.borrow() {
                    break;
                }
            }
            Err(CloudError::Unauthorized) => return Err(CloudError::Unauthorized),
            Err(error) => {
                attempt = attempt.saturating_add(1);
                on_status(RealtimeStatus::Reconnecting);
                let seconds = (1u64 << attempt.min(5)).min(30);
                let jitter_ms = (cursor ^ (attempt as u64 * 1_103_515_245)) % 750;
                tokio::select! {
                    _ = tokio::time::sleep(Duration::from_millis(seconds * 1_000 + jitter_ms)) => {},
                    changed = stop.changed() => {
                        if changed.is_err() || *stop.borrow() { break; }
                    }
                }
                if !matches!(
                    error,
                    CloudError::Network(_) | CloudError::Unavailable | CloudError::Unexpected
                ) {
                    return Err(error);
                }
                continue;
            }
        }
    }
    on_status(RealtimeStatus::Stopped);
    Ok(())
}

async fn stream_once<FE, FS>(
    base: &str,
    token: &str,
    project_id: Option<&str>,
    cursor: u64,
    stop: &mut watch::Receiver<bool>,
    on_event: &mut FE,
    on_status: &mut FS,
) -> Result<u64, CloudError>
where
    FE: FnMut(SyncEvent) -> Result<(), CloudError>,
    FS: FnMut(RealtimeStatus),
{
    let mut request = stream_client()?
        .get(format!("{}/v1/companion/events", resolve_base_url(base)))
        .bearer_auth(token)
        .header("last-event-id", cursor.to_string())
        .query(&[("cursor", cursor.to_string())]);
    if let Some(project_id) = project_id {
        request = request.query(&[("project_id", project_id)]);
    }
    let response = request
        .send()
        .await
        .map_err(|error| CloudError::Network(error.to_string()))?;
    if !response.status().is_success() {
        return Err(response_error(response).await);
    }
    on_status(RealtimeStatus::Live);
    let mut bytes = response.bytes_stream();
    let mut buffer = Vec::new();
    let mut last = cursor;
    loop {
        tokio::select! {
            changed = stop.changed() => {
                if changed.is_err() || *stop.borrow() { return Ok(last); }
            }
            chunk = bytes.next() => match chunk {
                Some(Ok(chunk)) => {
                    buffer.extend_from_slice(&chunk);
                    while let Some(end) = event_boundary(&buffer) {
                        let frame = buffer.drain(..end).collect::<Vec<_>>();
                        drain_separator(&mut buffer);
                        if let Some(event) = parse_sse_frame(&frame)? {
                            if event.seq > last {
                                last = event.seq;
                                on_event(event)?;
                            }
                        }
                    }
                    if buffer.len() > 2 * 1024 * 1024 { return Err(CloudError::Unexpected); }
                }
                Some(Err(error)) => return Err(CloudError::Network(error.to_string())),
                None => return Err(CloudError::Network("event stream closed".into())),
            }
        }
    }
}

fn event_boundary(buffer: &[u8]) -> Option<usize> {
    buffer
        .windows(2)
        .position(|window| window == b"\n\n")
        .or_else(|| buffer.windows(4).position(|window| window == b"\r\n\r\n"))
}

fn drain_separator(buffer: &mut Vec<u8>) {
    while buffer
        .first()
        .is_some_and(|byte| *byte == b'\r' || *byte == b'\n')
    {
        buffer.remove(0);
    }
}

fn parse_sse_frame(frame: &[u8]) -> Result<Option<SyncEvent>, CloudError> {
    let text = std::str::from_utf8(frame).map_err(|_| CloudError::Unexpected)?;
    let mut event_name = "message";
    let mut data = String::new();
    for raw in text.lines() {
        let line = raw.trim_end_matches('\r');
        if let Some(value) = line.strip_prefix("event:") {
            event_name = value.trim();
        }
        if let Some(value) = line.strip_prefix("data:") {
            if !data.is_empty() {
                data.push('\n');
            }
            data.push_str(value.trim_start());
        }
    }
    if event_name != "sync" || data.is_empty() {
        return Ok(None);
    }
    serde_json::from_str(&data)
        .map(Some)
        .map_err(|_| CloudError::Unexpected)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_split_safe_multiline_sse() {
        let frame = b"id: 9\r\nevent: sync\r\ndata: {\"seq\":9,\"project_id\":\"p\",\"design_id\":null,\"resource\":\"project\",\r\ndata: \"version\":1,\"command_id\":\"c\",\"actor_device_id\":\"d\",\"payload\":{},\"created_at\":1}";
        let event = parse_sse_frame(frame).unwrap().unwrap();
        assert_eq!(event.seq, 9);
        assert_eq!(event.resource, "project");
    }
}
