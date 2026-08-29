//! Native, dependency-free safety core for the Android integration layer.
//!
//! Values that depend on device evidence, calibration, models, or release policy
//! are supplied by callers. This crate deliberately has no default capability,
//! inference backend, threshold, queue size, or thermal cutoff.

use std::collections::{HashMap, HashSet, VecDeque};
use std::time::Instant;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RuntimeError {
    InvalidTransition,
    InvalidConfiguration,
    UnsupportedCapability,
    DeadlineExceeded,
    Cancelled,
    RegistrationLost,
    InvalidGeometry,
    ResourceExhausted,
    DuplicateTerminalOutcome,
    ContextRevisionMismatch,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SessionState {
    Created,
    Calibrating,
    Ready,
    Degraded,
    Executing,
    Suspended,
    Stopping,
    Closed,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LifecycleSignal {
    StartCalibration,
    CalibrationAccepted { degraded: bool },
    BeginExecution,
    CompleteExecution,
    Suspend,
    Resume,
    Stop,
    Close,
}

#[derive(Debug, Clone)]
pub struct Session {
    state: SessionState,
    execution_return_state: Option<SessionState>,
    generation: u64,
}

impl Session {
    pub fn new() -> Self {
        Self {
            state: SessionState::Created,
            execution_return_state: None,
            generation: 0,
        }
    }

    pub fn state(&self) -> SessionState {
        self.state
    }

    pub fn generation(&self) -> u64 {
        self.generation
    }

    pub fn transition(&mut self, signal: LifecycleSignal) -> Result<SessionState, RuntimeError> {
        let next = match (self.state, signal) {
            (SessionState::Created, LifecycleSignal::StartCalibration) => SessionState::Calibrating,
            (
                SessionState::Calibrating,
                LifecycleSignal::CalibrationAccepted { degraded: false },
            ) => SessionState::Ready,
            (
                SessionState::Calibrating,
                LifecycleSignal::CalibrationAccepted { degraded: true },
            ) => SessionState::Degraded,
            (SessionState::Ready | SessionState::Degraded, LifecycleSignal::BeginExecution) => {
                self.execution_return_state = Some(self.state);
                SessionState::Executing
            }
            (SessionState::Executing, LifecycleSignal::CompleteExecution) => self
                .execution_return_state
                .take()
                .ok_or(RuntimeError::InvalidTransition)?,
            (
                SessionState::Ready | SessionState::Degraded | SessionState::Executing,
                LifecycleSignal::Suspend,
            ) => SessionState::Suspended,
            (SessionState::Suspended, LifecycleSignal::Resume) => SessionState::Calibrating,
            (
                SessionState::Ready
                | SessionState::Degraded
                | SessionState::Executing
                | SessionState::Suspended,
                LifecycleSignal::Stop,
            ) => {
                self.execution_return_state = None;
                SessionState::Stopping
            }
            (SessionState::Stopping, LifecycleSignal::Close) => SessionState::Closed,
            _ => return Err(RuntimeError::InvalidTransition),
        };
        if matches!(signal, LifecycleSignal::Suspend | LifecycleSignal::Resume) {
            self.generation = self
                .generation
                .checked_add(1)
                .ok_or(RuntimeError::InvalidConfiguration)?;
        }
        self.state = next;
        Ok(next)
    }
}

impl Default for Session {
    fn default() -> Self {
        Self::new()
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct ToolId(pub String);

#[derive(Debug, Clone)]
pub struct CapabilityManifest {
    contract_version: String,
    enabled_tools: HashSet<ToolId>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ToolCall {
    pub schema_version: String,
    pub tool_call_id: String,
    pub session_id: String,
    pub context_revision: String,
    pub tool: ToolId,
    pub deadline: Instant,
}

impl ToolCall {
    pub fn validate(
        &self,
        manifest: &CapabilityManifest,
        current_context: &str,
        now: Instant,
    ) -> Result<(), RuntimeError> {
        if self.schema_version != manifest.contract_version()
            || self.tool_call_id.trim().is_empty()
            || self.session_id.trim().is_empty()
        {
            return Err(RuntimeError::InvalidConfiguration);
        }
        if self.context_revision != current_context {
            return Err(RuntimeError::ContextRevisionMismatch);
        }
        if self.deadline <= now {
            return Err(RuntimeError::DeadlineExceeded);
        }
        manifest.require(&self.tool)
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct EvidenceReference {
    pub digest: String,
    pub kind: String,
    pub retention: String,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Provenance {
    pub runtime_revision: String,
    pub model_bundle_digest: String,
    pub preprocessing_digest: String,
    pub backend: String,
    pub device_profile: String,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Observation {
    pub observation_id: String,
    pub tool_call_id: String,
    pub session_id: String,
    pub context_revision: String,
    pub coordinate_frame_id: Option<u64>,
    pub registration_revision: Option<u64>,
    pub confidence: Option<f64>,
    pub evidence: Vec<EvidenceReference>,
    pub provenance: Provenance,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Abstention {
    pub reason: RuntimeError,
    pub recoverable: bool,
}

#[derive(Debug, Clone, PartialEq)]
pub enum TerminalOutcome {
    Observation(Observation),
    Abstained(Abstention),
    Cancelled,
    Error(RuntimeError),
}

#[derive(Debug, Default)]
pub struct CallLedger {
    terminal: HashMap<String, TerminalOutcome>,
}

impl CallLedger {
    pub fn finish(
        &mut self,
        call: &ToolCall,
        outcome: TerminalOutcome,
    ) -> Result<(), RuntimeError> {
        if self.terminal.contains_key(&call.tool_call_id) {
            return Err(RuntimeError::DuplicateTerminalOutcome);
        }
        if let TerminalOutcome::Observation(observation) = &outcome {
            if observation.tool_call_id != call.tool_call_id
                || observation.session_id != call.session_id
                || observation.context_revision != call.context_revision
                || observation.observation_id.trim().is_empty()
                || observation
                    .confidence
                    .is_some_and(|value| !value.is_finite() || !(0.0..=1.0).contains(&value))
                || observation.coordinate_frame_id.is_some()
                    != observation.registration_revision.is_some()
                || (observation.coordinate_frame_id.is_some() && observation.confidence.is_none())
                || observation.evidence.iter().any(|evidence| {
                    evidence.digest.trim().is_empty()
                        || evidence.kind.trim().is_empty()
                        || evidence.retention.trim().is_empty()
                })
                || observation.provenance.runtime_revision.trim().is_empty()
                || observation.provenance.model_bundle_digest.trim().is_empty()
                || observation
                    .provenance
                    .preprocessing_digest
                    .trim()
                    .is_empty()
                || observation.provenance.backend.trim().is_empty()
                || observation.provenance.device_profile.trim().is_empty()
            {
                return Err(RuntimeError::InvalidConfiguration);
            }
        }
        self.terminal.insert(call.tool_call_id.clone(), outcome);
        Ok(())
    }

    pub fn terminal(&self, tool_call_id: &str) -> Option<&TerminalOutcome> {
        self.terminal.get(tool_call_id)
    }
}

impl CapabilityManifest {
    pub fn new(
        contract_version: impl Into<String>,
        enabled_tools: impl IntoIterator<Item = ToolId>,
    ) -> Result<Self, RuntimeError> {
        let contract_version = contract_version.into();
        if contract_version.trim().is_empty() {
            return Err(RuntimeError::InvalidConfiguration);
        }
        let enabled_tools: HashSet<_> = enabled_tools.into_iter().collect();
        if enabled_tools.iter().any(|tool| tool.0.trim().is_empty()) {
            return Err(RuntimeError::InvalidConfiguration);
        }
        Ok(Self {
            contract_version,
            enabled_tools,
        })
    }

    pub fn contract_version(&self) -> &str {
        &self.contract_version
    }

    pub fn supports(&self, tool: &ToolId) -> bool {
        self.enabled_tools.contains(tool)
    }

    pub fn require(&self, tool: &ToolId) -> Result<(), RuntimeError> {
        self.supports(tool)
            .then_some(())
            .ok_or(RuntimeError::UnsupportedCapability)
    }
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Point {
    pub x: f64,
    pub y: f64,
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct RegionOfInterest {
    pub frame_id: u64,
    pub x: f64,
    pub y: f64,
    pub width: f64,
    pub height: f64,
}

impl RegionOfInterest {
    pub fn validate(self) -> Result<Self, RuntimeError> {
        let coordinates = [self.x, self.y, self.width, self.height];
        if coordinates.iter().any(|value| !value.is_finite())
            || self.width <= 0.0
            || self.height <= 0.0
        {
            return Err(RuntimeError::InvalidGeometry);
        }
        Ok(self)
    }
}

#[derive(Debug, Clone)]
pub struct Registration {
    pub frame_id: u64,
    pub revision: u64,
    pub confidence: f64,
    pub required_confidence: f64,
    pub homography: [[f64; 3]; 3],
}

impl Registration {
    pub fn validate(&self) -> Result<(), RuntimeError> {
        if !self.confidence.is_finite()
            || !self.required_confidence.is_finite()
            || !(0.0..=1.0).contains(&self.confidence)
            || !(0.0..=1.0).contains(&self.required_confidence)
            || self
                .homography
                .iter()
                .flatten()
                .any(|value| !value.is_finite())
        {
            return Err(RuntimeError::InvalidGeometry);
        }
        if self.confidence < self.required_confidence {
            return Err(RuntimeError::RegistrationLost);
        }
        Ok(())
    }

    pub fn map(&self, point: Point) -> Result<Point, RuntimeError> {
        self.validate()?;
        if !point.x.is_finite() || !point.y.is_finite() {
            return Err(RuntimeError::InvalidGeometry);
        }
        let h = &self.homography;
        let denominator = h[2][0] * point.x + h[2][1] * point.y + h[2][2];
        if !denominator.is_finite() || denominator.abs() <= f64::EPSILON {
            return Err(RuntimeError::InvalidGeometry);
        }
        let mapped = Point {
            x: (h[0][0] * point.x + h[0][1] * point.y + h[0][2]) / denominator,
            y: (h[1][0] * point.x + h[1][1] * point.y + h[1][2]) / denominator,
        };
        if !mapped.x.is_finite() || !mapped.y.is_finite() {
            return Err(RuntimeError::InvalidGeometry);
        }
        Ok(mapped)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum WorkPriority {
    Background,
    Interactive,
}

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct WorkKey {
    pub session_id: String,
    pub tool_call_id: String,
}

#[derive(Debug, Clone)]
pub struct WorkRequest {
    pub key: WorkKey,
    pub session_generation: u64,
    pub priority: WorkPriority,
    pub deadline: Instant,
    pub roi: RegionOfInterest,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EnqueueOutcome {
    Accepted,
    Coalesced,
    DroppedStale,
}

#[derive(Debug)]
pub struct BoundedRoiScheduler {
    capacity: usize,
    queue: VecDeque<WorkRequest>,
    cancelled: HashSet<WorkKey>,
    dropped_stale: u64,
}

impl BoundedRoiScheduler {
    pub fn new(capacity: usize) -> Result<Self, RuntimeError> {
        if capacity == 0 {
            return Err(RuntimeError::InvalidConfiguration);
        }
        Ok(Self {
            capacity,
            queue: VecDeque::with_capacity(capacity),
            cancelled: HashSet::new(),
            dropped_stale: 0,
        })
    }

    pub fn enqueue(
        &mut self,
        request: WorkRequest,
        now: Instant,
    ) -> Result<EnqueueOutcome, RuntimeError> {
        request.roi.validate()?;
        if request.deadline <= now {
            return Err(RuntimeError::DeadlineExceeded);
        }
        if self.cancelled.contains(&request.key) {
            return Err(RuntimeError::Cancelled);
        }
        if let Some(existing) = self
            .queue
            .iter_mut()
            .find(|existing| existing.key == request.key)
        {
            if request.session_generation < existing.session_generation {
                self.dropped_stale = self.dropped_stale.saturating_add(1);
                return Ok(EnqueueOutcome::DroppedStale);
            }
            *existing = request;
            return Ok(EnqueueOutcome::Coalesced);
        }
        if self.queue.len() == self.capacity {
            let replace_index = self
                .queue
                .iter()
                .enumerate()
                .min_by_key(|(_, item)| item.priority)
                .map(|(index, _)| index);
            let Some(replace_index) = replace_index else {
                return Err(RuntimeError::ResourceExhausted);
            };
            if self.queue[replace_index].priority >= request.priority {
                self.dropped_stale = self.dropped_stale.saturating_add(1);
                return Ok(EnqueueOutcome::DroppedStale);
            }
            self.queue.remove(replace_index);
            self.dropped_stale = self.dropped_stale.saturating_add(1);
        }
        self.queue.push_back(request);
        Ok(EnqueueOutcome::Accepted)
    }

    pub fn cancel(&mut self, key: WorkKey) {
        self.cancelled.insert(key.clone());
        self.queue.retain(|request| request.key != key);
    }

    pub fn next(&mut self, now: Instant, current_generation: u64) -> Option<WorkRequest> {
        let mut selected: Option<WorkRequest> = None;
        let mut retained = VecDeque::with_capacity(self.capacity);
        while let Some(request) = self.queue.pop_front() {
            if request.deadline <= now
                || request.session_generation != current_generation
                || self.cancelled.contains(&request.key)
            {
                self.dropped_stale = self.dropped_stale.saturating_add(1);
                continue;
            }
            match &selected {
                Some(current) if current.priority >= request.priority => {
                    retained.push_back(request)
                }
                Some(current) => {
                    retained.push_back(current.clone());
                    selected = Some(request);
                }
                None => selected = Some(request),
            }
        }
        self.queue = retained;
        selected
    }

    pub fn dropped_stale(&self) -> u64 {
        self.dropped_stale
    }
}

#[derive(Debug)]
pub struct FrameEnvelope<T> {
    pub frame_id: u64,
    pub session_generation: u64,
    pub payload: T,
}

/// A single-slot handoff for streaming camera work. A slow consumer receives the
/// newest usable frame rather than creating an unbounded backlog.
#[derive(Debug, Default)]
pub struct LatestFrame<T> {
    latest: Option<FrameEnvelope<T>>,
    dropped: u64,
}

impl<T> LatestFrame<T> {
    pub fn publish(&mut self, frame: FrameEnvelope<T>) {
        if self.latest.replace(frame).is_some() {
            self.dropped = self.dropped.saturating_add(1);
        }
    }

    pub fn take_for_generation(&mut self, generation: u64) -> Option<FrameEnvelope<T>> {
        let frame = self.latest.take()?;
        if frame.session_generation == generation {
            Some(frame)
        } else {
            self.dropped = self.dropped.saturating_add(1);
            None
        }
    }

    pub fn dropped(&self) -> u64 {
        self.dropped
    }
}

#[derive(Debug, Clone)]
pub struct StorageQuota {
    capacity_bytes: u64,
    used_bytes: u64,
}

impl StorageQuota {
    pub fn new(capacity_bytes: u64) -> Result<Self, RuntimeError> {
        if capacity_bytes == 0 {
            return Err(RuntimeError::InvalidConfiguration);
        }
        Ok(Self {
            capacity_bytes,
            used_bytes: 0,
        })
    }

    pub fn reserve(&mut self, bytes: u64) -> Result<(), RuntimeError> {
        let next = self
            .used_bytes
            .checked_add(bytes)
            .ok_or(RuntimeError::ResourceExhausted)?;
        if next > self.capacity_bytes {
            return Err(RuntimeError::ResourceExhausted);
        }
        self.used_bytes = next;
        Ok(())
    }

    pub fn release(&mut self, bytes: u64) -> Result<(), RuntimeError> {
        self.used_bytes = self
            .used_bytes
            .checked_sub(bytes)
            .ok_or(RuntimeError::InvalidConfiguration)?;
        Ok(())
    }

    pub fn remaining_bytes(&self) -> u64 {
        self.capacity_bytes - self.used_bytes
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum ThermalState {
    Nominal,
    Constrained,
    Critical,
}

#[derive(Debug, Clone)]
pub struct ThermalPolicy {
    allowed_concurrency: HashMap<ThermalState, usize>,
}

impl ThermalPolicy {
    pub fn new(allowed_concurrency: HashMap<ThermalState, usize>) -> Result<Self, RuntimeError> {
        if allowed_concurrency.is_empty() {
            return Err(RuntimeError::InvalidConfiguration);
        }
        Ok(Self {
            allowed_concurrency,
        })
    }

    pub fn concurrency_for(&self, state: ThermalState) -> Result<usize, RuntimeError> {
        self.allowed_concurrency
            .get(&state)
            .copied()
            .ok_or(RuntimeError::InvalidConfiguration)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;
    use std::time::Duration;

    fn request(key: &str, priority: WorkPriority, deadline: Instant) -> WorkRequest {
        WorkRequest {
            key: WorkKey {
                session_id: "session".into(),
                tool_call_id: key.into(),
            },
            session_generation: 0,
            priority,
            deadline,
            roi: RegionOfInterest {
                frame_id: 1,
                x: 0.0,
                y: 0.0,
                width: 1.0,
                height: 1.0,
            },
        }
    }

    #[test]
    fn suspend_invalidates_work_and_resume_requires_calibration() {
        let mut session = Session::new();
        session
            .transition(LifecycleSignal::StartCalibration)
            .unwrap();
        session
            .transition(LifecycleSignal::CalibrationAccepted { degraded: false })
            .unwrap();
        session.transition(LifecycleSignal::Suspend).unwrap();
        assert_eq!(session.generation(), 1);
        assert_eq!(
            session.transition(LifecycleSignal::Resume),
            Ok(SessionState::Calibrating)
        );
    }

    #[test]
    fn execution_returns_to_the_prior_degraded_state() {
        let mut session = Session::new();
        session
            .transition(LifecycleSignal::StartCalibration)
            .unwrap();
        session
            .transition(LifecycleSignal::CalibrationAccepted { degraded: true })
            .unwrap();
        session.transition(LifecycleSignal::BeginExecution).unwrap();
        assert_eq!(
            session.transition(LifecycleSignal::CompleteExecution),
            Ok(SessionState::Degraded)
        );
    }

    #[test]
    fn active_work_can_stop_without_a_terminal_observation() {
        let mut session = Session::new();
        session
            .transition(LifecycleSignal::StartCalibration)
            .unwrap();
        session
            .transition(LifecycleSignal::CalibrationAccepted { degraded: false })
            .unwrap();
        session.transition(LifecycleSignal::BeginExecution).unwrap();
        assert_eq!(
            session.transition(LifecycleSignal::Stop),
            Ok(SessionState::Stopping)
        );
    }

    #[test]
    fn empty_capabilities_fail_closed() {
        let manifest = CapabilityManifest::new("contract", []).unwrap();
        assert_eq!(
            manifest.require(&ToolId("locate".into())),
            Err(RuntimeError::UnsupportedCapability)
        );
    }

    #[test]
    fn contract_call_has_one_valid_terminal_outcome() {
        let now = Instant::now();
        let manifest = CapabilityManifest::new("contract.v1", [ToolId("inspect".into())]).unwrap();
        let call = ToolCall {
            schema_version: "contract.v1".into(),
            tool_call_id: "call-1".into(),
            session_id: "session-1".into(),
            context_revision: "context-1".into(),
            tool: ToolId("inspect".into()),
            deadline: now + Duration::from_secs(1),
        };
        assert_eq!(call.validate(&manifest, "context-1", now), Ok(()));

        let observation = Observation {
            observation_id: "observation-1".into(),
            tool_call_id: call.tool_call_id.clone(),
            session_id: call.session_id.clone(),
            context_revision: call.context_revision.clone(),
            coordinate_frame_id: Some(10),
            registration_revision: Some(4),
            confidence: Some(0.9),
            evidence: vec![EvidenceReference {
                digest: "sha256:example".into(),
                kind: "frame".into(),
                retention: "session".into(),
            }],
            provenance: Provenance {
                runtime_revision: "runtime-revision".into(),
                model_bundle_digest: "sha256:model".into(),
                preprocessing_digest: "sha256:preprocess".into(),
                backend: "selected-by-policy".into(),
                device_profile: "profile-revision".into(),
            },
        };
        let mut ledger = CallLedger::default();
        assert_eq!(
            ledger.finish(&call, TerminalOutcome::Observation(observation.clone())),
            Ok(())
        );
        assert_eq!(
            ledger.finish(&call, TerminalOutcome::Observation(observation)),
            Err(RuntimeError::DuplicateTerminalOutcome)
        );
    }

    #[test]
    fn contract_rejects_ambiguous_visual_observations() {
        let call = ToolCall {
            schema_version: "contract.v1".into(),
            tool_call_id: "call-2".into(),
            session_id: "session-2".into(),
            context_revision: "context-2".into(),
            tool: ToolId("inspect".into()),
            deadline: Instant::now() + Duration::from_secs(1),
        };
        let invalid = Observation {
            observation_id: "observation-2".into(),
            tool_call_id: call.tool_call_id.clone(),
            session_id: call.session_id.clone(),
            context_revision: call.context_revision.clone(),
            coordinate_frame_id: Some(10),
            registration_revision: None,
            confidence: Some(0.9),
            evidence: Vec::new(),
            provenance: Provenance {
                runtime_revision: "runtime-revision".into(),
                model_bundle_digest: "sha256:model".into(),
                preprocessing_digest: "sha256:preprocess".into(),
                backend: "selected-by-policy".into(),
                device_profile: "profile-revision".into(),
            },
        };
        assert_eq!(
            CallLedger::default().finish(&call, TerminalOutcome::Observation(invalid)),
            Err(RuntimeError::InvalidConfiguration)
        );
    }

    #[test]
    fn registration_requires_caller_supplied_confidence() {
        let registration = Registration {
            frame_id: 1,
            revision: 1,
            confidence: 0.4,
            required_confidence: 0.5,
            homography: [[1.0, 0.0, 0.0], [0.0, 1.0, 0.0], [0.0, 0.0, 1.0]],
        };
        assert_eq!(
            registration.map(Point { x: 2.0, y: 3.0 }),
            Err(RuntimeError::RegistrationLost)
        );
    }

    #[test]
    fn scheduler_coalesces_and_drops_expired_work() {
        let now = Instant::now();
        let mut scheduler = BoundedRoiScheduler::new(1).unwrap();
        assert_eq!(
            scheduler.enqueue(
                request("a", WorkPriority::Background, now + Duration::from_secs(1)),
                now
            ),
            Ok(EnqueueOutcome::Accepted)
        );
        assert_eq!(
            scheduler.enqueue(
                request("a", WorkPriority::Interactive, now + Duration::from_secs(2)),
                now
            ),
            Ok(EnqueueOutcome::Coalesced)
        );
        assert_eq!(
            scheduler.next(now, 0).unwrap().priority,
            WorkPriority::Interactive
        );
        assert_eq!(
            scheduler.enqueue(request("expired", WorkPriority::Interactive, now), now),
            Err(RuntimeError::DeadlineExceeded)
        );
    }

    #[test]
    fn frame_handoff_keeps_only_the_latest_current_generation() {
        let mut frames = LatestFrame::default();
        frames.publish(FrameEnvelope {
            frame_id: 1,
            session_generation: 3,
            payload: "first",
        });
        frames.publish(FrameEnvelope {
            frame_id: 2,
            session_generation: 3,
            payload: "second",
        });
        assert_eq!(frames.dropped(), 1);
        assert_eq!(frames.take_for_generation(3).unwrap().payload, "second");

        frames.publish(FrameEnvelope {
            frame_id: 3,
            session_generation: 2,
            payload: "stale",
        });
        assert!(frames.take_for_generation(3).is_none());
        assert_eq!(frames.dropped(), 2);
    }

    #[test]
    fn storage_quota_is_explicit_and_bounded() {
        let mut quota = StorageQuota::new(64).unwrap();
        assert_eq!(quota.reserve(48), Ok(()));
        assert_eq!(quota.reserve(17), Err(RuntimeError::ResourceExhausted));
        assert_eq!(quota.remaining_bytes(), 16);
        assert_eq!(quota.release(16), Ok(()));
        assert_eq!(quota.remaining_bytes(), 32);
    }

    #[test]
    fn thermal_limits_are_entirely_policy_driven() {
        let policy = ThermalPolicy::new(HashMap::from([
            (ThermalState::Nominal, 2),
            (ThermalState::Constrained, 1),
            (ThermalState::Critical, 0),
        ]))
        .unwrap();
        assert_eq!(policy.concurrency_for(ThermalState::Critical), Ok(0));
    }
}
