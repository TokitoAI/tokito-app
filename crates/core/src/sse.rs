//! Bounded incremental SSE framing. Transport chunks are not UTF-8 boundaries.

use crate::CloudError;

pub(crate) const MAX_FRAME_BYTES: usize = 2 * 1024 * 1024;

#[derive(Debug, PartialEq, Eq)]
pub(crate) struct Frame {
    pub event: String,
    pub id: Option<String>,
    pub data: String,
}

#[derive(Default)]
pub(crate) struct Decoder {
    bytes: Vec<u8>,
    scan: usize,
}

impl Decoder {
    pub fn has_pending_data(&self) -> bool {
        !self.bytes.is_empty()
    }

    pub fn push(&mut self, chunk: &[u8]) -> Result<Vec<Frame>, CloudError> {
        // Consume incrementally so a large transport chunk containing many small
        // events cannot bypass the per-frame limit or cause an unbounded copy.
        let mut frames = Vec::new();
        for byte in chunk {
            if self.bytes.len() >= MAX_FRAME_BYTES {
                return Err(CloudError::Unexpected);
            }
            self.bytes.push(*byte);
            let end = self.bytes.len();
            let delimiter = if self.bytes.ends_with(b"\r\n\r\n") {
                4
            } else if self.bytes.ends_with(b"\n\n") || self.bytes.ends_with(b"\r\r") {
                2
            } else {
                continue;
            };
            if let Some(frame) = parse(&self.bytes[..end - delimiter], self.scan == 0)? {
                frames.push(frame);
            }
            self.scan += 1;
            self.bytes.clear();
        }
        Ok(frames)
    }
}

fn parse(bytes: &[u8], first: bool) -> Result<Option<Frame>, CloudError> {
    let text = std::str::from_utf8(bytes).map_err(|_| CloudError::Unexpected)?;
    let text = if first {
        text.trim_start_matches('\u{feff}')
    } else {
        text
    };
    let mut frame = Frame {
        event: "message".into(),
        id: None,
        data: String::new(),
    };
    let mut has_data = false;
    for line in text.split(['\r', '\n']) {
        if line.is_empty() || line.starts_with(':') {
            continue;
        }
        let (field, value) = line.split_once(':').unwrap_or((line, ""));
        let value = value.strip_prefix(' ').unwrap_or(value);
        match field {
            "event" => frame.event = value.into(),
            "id" if !value.contains('\0') => frame.id = Some(value.into()),
            "data" => {
                if has_data {
                    frame.data.push('\n');
                }
                frame.data.push_str(value);
                has_data = true;
            }
            _ => {}
        }
    }
    Ok(has_data.then_some(frame))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn all_byte_boundaries_preserve_unicode_crlf_and_multiline_data() {
        let bytes =
            "\u{feff}: heartbeat\r\n\r\nid: 3\r\nevent: turn\r\ndata: α\r\ndata:  β\r\n\r\n"
                .as_bytes();
        for split in 0..=bytes.len() {
            let mut decoder = Decoder::default();
            let mut frames = decoder.push(&bytes[..split]).unwrap();
            frames.extend(decoder.push(&bytes[split..]).unwrap());
            assert_eq!(
                frames,
                vec![Frame {
                    event: "turn".into(),
                    id: Some("3".into()),
                    data: "α\n β".into()
                }]
            );
        }
    }

    #[test]
    fn rejects_unbounded_or_invalid_utf8_frames() {
        assert!(Decoder::default()
            .push(&vec![b'x'; MAX_FRAME_BYTES + 1])
            .is_err());
        assert!(Decoder::default().push(b"data: \xff\n\n").is_err());
    }

    #[test]
    fn ignores_comments_but_keeps_empty_data_events_and_drops_nul_ids() {
        let frames = Decoder::default()
            .push(b": heartbeat\n\nid: bad\0id\ndata:\n\n")
            .unwrap();
        assert_eq!(
            frames,
            vec![Frame {
                event: "message".into(),
                id: None,
                data: String::new()
            }]
        );
    }
}
