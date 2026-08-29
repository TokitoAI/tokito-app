//! Native, dependency-free safety core for the Android integration layer.
//!
//! Values that depend on device evidence, calibration, models, or release policy
//! are supplied by callers. This crate deliberately has no default capability,
//! inference backend, threshold, queue size, or thermal cutoff.

use std::collections::{HashMap, HashSet, VecDeque};
use std::time::Instant;

use sha2::{Digest, Sha256};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RuntimeError {
    InvalidArgument,
    InvalidTransition,
    InvalidConfiguration,
    UnsupportedCapability,
    PermissionDenied,
    LifecycleUnavailable,
    DeadlineExceeded,
    Cancelled,
    RegistrationLost,
    InvalidGeometry,
    ResourceExhausted,
    ThermalLimited,
    ModelUnavailable,
    BackendFailure,
    InternalInvariantViolation,
    DuplicateTerminalOutcome,
    ContextRevisionMismatch,
    IntegrityFailure,
    IncompatibleSchema,
    DuplicateReference,
    ConflictingDesignTruth,
}

impl RuntimeError {
    pub fn retryable(self) -> bool {
        matches!(
            self,
            Self::LifecycleUnavailable
                | Self::DeadlineExceeded
                | Self::RegistrationLost
                | Self::ResourceExhausted
                | Self::ThermalLimited
                | Self::ModelUnavailable
                | Self::BackendFailure
        )
    }

    pub fn recovery_guidance(self) -> &'static str {
        match self {
            Self::PermissionDenied => "request the required permission in an active session",
            Self::LifecycleUnavailable => "resume an active session and reacquire sensors",
            Self::RegistrationLost => "recalibrate before requesting registered guidance",
            Self::ResourceExhausted | Self::ThermalLimited => {
                "wait for available local resources before retrying"
            }
            Self::ModelUnavailable => "install a verified compatible local model bundle",
            Self::DeadlineExceeded | Self::Cancelled => "submit a new request if work is still needed",
            Self::BackendFailure => "record backend evidence and use an accepted fallback only",
            _ => "correct the request or report the runtime failure without exposing sensitive diagnostics",
        }
    }
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
    admitted: HashMap<String, CallIdentity>,
    terminal: HashMap<String, TerminalOutcome>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct CallIdentity {
    session_id: String,
    context_revision: String,
    tool: ToolId,
}

#[derive(Debug, Clone, PartialEq)]
pub enum CallAdmission {
    Accepted,
    Pending,
    Completed(TerminalOutcome),
}

impl CallLedger {
    pub fn admit(&mut self, call: &ToolCall) -> Result<CallAdmission, RuntimeError> {
        let identity = CallIdentity {
            session_id: call.session_id.clone(),
            context_revision: call.context_revision.clone(),
            tool: call.tool.clone(),
        };
        if let Some(existing) = self.admitted.get(&call.tool_call_id) {
            if existing != &identity {
                return Err(RuntimeError::ContextRevisionMismatch);
            }
            return Ok(self
                .terminal
                .get(&call.tool_call_id)
                .cloned()
                .map(CallAdmission::Completed)
                .unwrap_or(CallAdmission::Pending));
        }
        self.admitted.insert(call.tool_call_id.clone(), identity);
        Ok(CallAdmission::Accepted)
    }

    pub fn finish(
        &mut self,
        call: &ToolCall,
        outcome: TerminalOutcome,
    ) -> Result<(), RuntimeError> {
        if self.terminal.contains_key(&call.tool_call_id) {
            return Err(RuntimeError::DuplicateTerminalOutcome);
        }
        let Some(identity) = self.admitted.get(&call.tool_call_id) else {
            return Err(RuntimeError::InvalidConfiguration);
        };
        if identity.session_id != call.session_id
            || identity.context_revision != call.context_revision
            || identity.tool != call.tool
        {
            return Err(RuntimeError::ContextRevisionMismatch);
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

    pub fn replace(
        &mut self,
        released_bytes: u64,
        reserved_bytes: u64,
    ) -> Result<(), RuntimeError> {
        let retained = self
            .used_bytes
            .checked_sub(released_bytes)
            .ok_or(RuntimeError::InvalidConfiguration)?;
        let next = retained
            .checked_add(reserved_bytes)
            .ok_or(RuntimeError::ResourceExhausted)?;
        if next > self.capacity_bytes {
            return Err(RuntimeError::ResourceExhausted);
        }
        self.used_bytes = next;
        Ok(())
    }

    pub fn remaining_bytes(&self) -> u64 {
        self.capacity_bytes - self.used_bytes
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SchemaVersion {
    pub major: u16,
    pub minor: u16,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EvidenceRole {
    AuthoredPlacement,
    Datasheet,
    Supporting,
}

#[derive(Debug, Clone, PartialEq)]
pub struct SourceEvidence {
    pub source_id: String,
    pub role: EvidenceRole,
    pub license: String,
    pub digest: String,
}

#[derive(Debug, Clone, PartialEq)]
pub struct ComponentPlacement {
    pub reference_designator: String,
    pub footprint: String,
    pub centroid: Point,
    pub rotation_degrees: f64,
    pub side: String,
    pub polarity_or_pin_one: Option<String>,
    pub alternates: Vec<String>,
    pub placement_source_id: String,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Landmark {
    pub landmark_id: String,
    pub position: Point,
}

#[derive(Debug, Clone, PartialEq)]
pub struct BoardIdentity {
    pub board_id: String,
    pub revision: String,
    pub coordinate_frame_id: String,
    pub units: String,
    pub handedness: String,
    pub outline: Vec<Point>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct BundleIntegrity {
    /// Canonical `sha256:<lowercase-hex>` of the exact imported payload.
    pub payload_sha256: String,
    pub signature_id: String,
}

pub trait SignatureVerifier {
    fn verify(&self, payload: &[u8], signature_id: &str) -> bool;
}

#[derive(Debug, Clone, PartialEq)]
pub struct AssemblyContext {
    pub schema_version: SchemaVersion,
    pub board: BoardIdentity,
    pub components: Vec<ComponentPlacement>,
    pub landmarks: Vec<Landmark>,
    pub sources: Vec<SourceEvidence>,
    pub integrity: BundleIntegrity,
}

impl AssemblyContext {
    pub fn validate(&self, supported_major: u16) -> Result<(), RuntimeError> {
        if self.schema_version.major != supported_major
            || self.board.board_id.trim().is_empty()
            || self.board.revision.trim().is_empty()
            || self.board.coordinate_frame_id.trim().is_empty()
            || self.board.units.trim().is_empty()
            || self.board.handedness.trim().is_empty()
            || self.board.outline.len() < 3
            || self
                .board
                .outline
                .iter()
                .any(|point| !point.x.is_finite() || !point.y.is_finite())
            || outline_is_degenerate(&self.board.outline)
            || self.integrity.signature_id.trim().is_empty()
            || !is_canonical_sha256(&self.integrity.payload_sha256)
        {
            return Err(RuntimeError::InvalidConfiguration);
        }

        let mut source_ids = HashMap::new();
        for source in &self.sources {
            if source.source_id.trim().is_empty()
                || source.license.trim().is_empty()
                || !is_canonical_sha256(&source.digest)
            {
                return Err(RuntimeError::InvalidConfiguration);
            }
            if source_ids
                .insert(source.source_id.as_str(), source.role)
                .is_some()
            {
                return Err(RuntimeError::ConflictingDesignTruth);
            }
        }

        let mut references = HashSet::new();
        for component in &self.components {
            if component.reference_designator.trim().is_empty()
                || component.footprint.trim().is_empty()
                || component.side.trim().is_empty()
                || !component.centroid.x.is_finite()
                || !component.centroid.y.is_finite()
                || !component.rotation_degrees.is_finite()
                || !references.insert(component.reference_designator.as_str())
            {
                return Err(RuntimeError::DuplicateReference);
            }
            if source_ids.get(component.placement_source_id.as_str())
                != Some(&EvidenceRole::AuthoredPlacement)
            {
                return Err(RuntimeError::ConflictingDesignTruth);
            }
        }

        let mut landmark_ids = HashSet::new();
        for landmark in &self.landmarks {
            if landmark.landmark_id.trim().is_empty()
                || !landmark.position.x.is_finite()
                || !landmark.position.y.is_finite()
                || !landmark_ids.insert(landmark.landmark_id.as_str())
            {
                return Err(RuntimeError::InvalidConfiguration);
            }
        }
        Ok(())
    }

    pub fn verify_integrity(
        &self,
        payload: &[u8],
        verifier: &impl SignatureVerifier,
    ) -> Result<(), RuntimeError> {
        let actual = format!("sha256:{:x}", Sha256::digest(payload));
        if actual != self.integrity.payload_sha256
            || !verifier.verify(payload, &self.integrity.signature_id)
        {
            return Err(RuntimeError::IntegrityFailure);
        }
        Ok(())
    }
}

#[derive(Debug, Default)]
pub struct AssemblyContextStore {
    active: Option<AssemblyContext>,
    active_bytes: u64,
}

impl AssemblyContextStore {
    pub fn import_atomically(
        &mut self,
        candidate: AssemblyContext,
        payload: &[u8],
        supported_major: u16,
        verifier: &impl SignatureVerifier,
        quota: &mut StorageQuota,
    ) -> Result<(), RuntimeError> {
        candidate.validate(supported_major)?;
        candidate.verify_integrity(payload, verifier)?;
        let candidate_bytes =
            u64::try_from(payload.len()).map_err(|_| RuntimeError::ResourceExhausted)?;
        quota.replace(self.active_bytes, candidate_bytes)?;
        self.active = Some(candidate);
        self.active_bytes = candidate_bytes;
        Ok(())
    }

    pub fn active(&self) -> Option<&AssemblyContext> {
        self.active.as_ref()
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct ModelManifest {
    pub schema_version: SchemaVersion,
    pub model_id: String,
    pub model_revision: String,
    pub artifact_sha256: String,
    pub preprocessing_sha256: String,
    pub license: String,
    pub minimum_runtime_revision: String,
    pub supported_abis: Vec<String>,
    pub signature_id: String,
}

impl ModelManifest {
    pub fn validate(
        &self,
        supported_major: u16,
        runtime_revision: &str,
        abi: &str,
    ) -> Result<(), RuntimeError> {
        if self.schema_version.major != supported_major {
            return Err(RuntimeError::IncompatibleSchema);
        }
        if self.model_id.trim().is_empty()
            || self.model_revision.trim().is_empty()
            || self.license.trim().is_empty()
            || self.minimum_runtime_revision.trim().is_empty()
            || self.signature_id.trim().is_empty()
            || !is_canonical_sha256(&self.artifact_sha256)
            || !is_canonical_sha256(&self.preprocessing_sha256)
            || self.supported_abis.is_empty()
            || self
                .supported_abis
                .iter()
                .any(|value| value.trim().is_empty())
        {
            return Err(RuntimeError::InvalidConfiguration);
        }
        if self.minimum_runtime_revision != runtime_revision
            || !self.supported_abis.iter().any(|value| value == abi)
        {
            return Err(RuntimeError::UnsupportedCapability);
        }
        Ok(())
    }

    pub fn verify_artifact(
        &self,
        artifact: &[u8],
        verifier: &impl SignatureVerifier,
    ) -> Result<(), RuntimeError> {
        let actual = format!("sha256:{:x}", Sha256::digest(artifact));
        if actual != self.artifact_sha256 || !verifier.verify(artifact, &self.signature_id) {
            return Err(RuntimeError::IntegrityFailure);
        }
        Ok(())
    }
}

#[derive(Debug, Default)]
pub struct ModelRegistry {
    active: Option<ModelManifest>,
    active_bytes: u64,
}

impl ModelRegistry {
    pub fn activate_atomically(
        &mut self,
        candidate: ModelManifest,
        artifact: &[u8],
        supported_major: u16,
        runtime_revision: &str,
        abi: &str,
        verifier: &impl SignatureVerifier,
        quota: &mut StorageQuota,
    ) -> Result<(), RuntimeError> {
        candidate.validate(supported_major, runtime_revision, abi)?;
        candidate.verify_artifact(artifact, verifier)?;
        let candidate_bytes =
            u64::try_from(artifact.len()).map_err(|_| RuntimeError::ResourceExhausted)?;
        quota.replace(self.active_bytes, candidate_bytes)?;
        self.active = Some(candidate);
        self.active_bytes = candidate_bytes;
        Ok(())
    }

    pub fn active(&self) -> Option<&ModelManifest> {
        self.active.as_ref()
    }
}

fn is_canonical_sha256(value: &str) -> bool {
    let Some(hex) = value.strip_prefix("sha256:") else {
        return false;
    };
    hex.len() == 64
        && hex
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

fn outline_is_degenerate(outline: &[Point]) -> bool {
    let mut twice_signed_area = 0.0;
    for (point, next) in outline
        .iter()
        .zip(outline.iter().cycle().skip(1))
        .take(outline.len())
    {
        twice_signed_area += point.x * next.y - next.x * point.y;
    }
    !twice_signed_area.is_finite() || twice_signed_area == 0.0
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

    struct AcceptingVerifier;

    impl SignatureVerifier for AcceptingVerifier {
        fn verify(&self, _payload: &[u8], signature_id: &str) -> bool {
            signature_id == "verified-signature"
        }
    }

    fn assembly_context(payload: &[u8]) -> AssemblyContext {
        AssemblyContext {
            schema_version: SchemaVersion { major: 1, minor: 0 },
            board: BoardIdentity {
                board_id: "board".into(),
                revision: "revision".into(),
                coordinate_frame_id: "board-plane".into(),
                units: "declared-units".into(),
                handedness: "declared-handedness".into(),
                outline: vec![
                    Point { x: 0.0, y: 0.0 },
                    Point { x: 1.0, y: 0.0 },
                    Point { x: 0.0, y: 1.0 },
                ],
            },
            components: vec![ComponentPlacement {
                reference_designator: "R1".into(),
                footprint: "footprint".into(),
                centroid: Point { x: 0.5, y: 0.5 },
                rotation_degrees: 0.0,
                side: "declared-side".into(),
                polarity_or_pin_one: None,
                alternates: Vec::new(),
                placement_source_id: "placement".into(),
            }],
            landmarks: vec![Landmark {
                landmark_id: "landmark".into(),
                position: Point { x: 0.0, y: 0.0 },
            }],
            sources: vec![
                SourceEvidence {
                    source_id: "placement".into(),
                    role: EvidenceRole::AuthoredPlacement,
                    license: "declared-license".into(),
                    digest:
                        "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                            .into(),
                },
                SourceEvidence {
                    source_id: "datasheet".into(),
                    role: EvidenceRole::Datasheet,
                    license: "declared-license".into(),
                    digest:
                        "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                            .into(),
                },
            ],
            integrity: BundleIntegrity {
                payload_sha256: format!("sha256:{:x}", Sha256::digest(payload)),
                signature_id: "verified-signature".into(),
            },
        }
    }

    fn model_manifest(artifact: &[u8]) -> ModelManifest {
        ModelManifest {
            schema_version: SchemaVersion { major: 1, minor: 0 },
            model_id: "model".into(),
            model_revision: "revision".into(),
            artifact_sha256: format!("sha256:{:x}", Sha256::digest(artifact)),
            preprocessing_sha256:
                "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd".into(),
            license: "declared-license".into(),
            minimum_runtime_revision: "runtime-revision".into(),
            supported_abis: vec!["declared-abi".into()],
            signature_id: "verified-signature".into(),
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
    fn typed_errors_keep_recovery_guidance_safe_and_actionable() {
        assert!(RuntimeError::ThermalLimited.retryable());
        assert!(!RuntimeError::PermissionDenied.retryable());
        assert_eq!(
            RuntimeError::ModelUnavailable.recovery_guidance(),
            "install a verified compatible local model bundle"
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
        assert_eq!(ledger.admit(&call), Ok(CallAdmission::Accepted));
        assert_eq!(
            ledger.finish(&call, TerminalOutcome::Observation(observation.clone())),
            Ok(())
        );
        assert_eq!(
            ledger.finish(&call, TerminalOutcome::Observation(observation)),
            Err(RuntimeError::DuplicateTerminalOutcome)
        );
        assert!(matches!(
            ledger.admit(&call),
            Ok(CallAdmission::Completed(TerminalOutcome::Observation(_)))
        ));
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
        let mut ledger = CallLedger::default();
        assert_eq!(ledger.admit(&call), Ok(CallAdmission::Accepted));
        assert_eq!(
            ledger.finish(&call, TerminalOutcome::Observation(invalid)),
            Err(RuntimeError::InvalidConfiguration)
        );
    }

    #[test]
    fn duplicate_pending_call_does_not_create_a_second_execution() {
        let call = ToolCall {
            schema_version: "contract.v1".into(),
            tool_call_id: "call-3".into(),
            session_id: "session-3".into(),
            context_revision: "context-3".into(),
            tool: ToolId("inspect".into()),
            deadline: Instant::now() + Duration::from_secs(1),
        };
        let mut ledger = CallLedger::default();
        assert_eq!(ledger.admit(&call), Ok(CallAdmission::Accepted));
        assert_eq!(ledger.admit(&call), Ok(CallAdmission::Pending));

        let mut conflicting_retry = call.clone();
        conflicting_retry.session_id = "other-session".into();
        assert_eq!(
            ledger.admit(&conflicting_retry),
            Err(RuntimeError::ContextRevisionMismatch)
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
    fn assembly_context_import_is_verified_and_atomic() {
        let payload = b"valid assembly context";
        let context = assembly_context(payload);
        let mut store = AssemblyContextStore::default();
        let mut quota = StorageQuota::new(128).unwrap();
        assert_eq!(
            store.import_atomically(context.clone(), payload, 1, &AcceptingVerifier, &mut quota),
            Ok(())
        );
        assert_eq!(store.active(), Some(&context));

        let mut invalid = context.clone();
        invalid.integrity.payload_sha256 =
            "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc".into();
        assert_eq!(
            store.import_atomically(invalid, payload, 1, &AcceptingVerifier, &mut quota),
            Err(RuntimeError::IntegrityFailure)
        );
        assert_eq!(store.active(), Some(&context));
    }

    #[test]
    fn assembly_context_rejects_datasheet_as_placement_truth() {
        let payload = b"context";
        let mut context = assembly_context(payload);
        context.components[0].placement_source_id = "datasheet".into();
        assert_eq!(
            context.validate(1),
            Err(RuntimeError::ConflictingDesignTruth)
        );
    }

    #[test]
    fn assembly_context_rejects_degenerate_outlines() {
        let payload = b"context";
        let mut context = assembly_context(payload);
        context.board.outline = vec![
            Point { x: 0.0, y: 0.0 },
            Point { x: 1.0, y: 1.0 },
            Point { x: 2.0, y: 2.0 },
        ];
        assert_eq!(context.validate(1), Err(RuntimeError::InvalidConfiguration));
    }

    #[test]
    fn model_activation_requires_integrity_and_declared_compatibility() {
        let artifact = b"model artifact";
        let manifest = model_manifest(artifact);
        let mut registry = ModelRegistry::default();
        let mut quota = StorageQuota::new(128).unwrap();
        assert_eq!(
            registry.activate_atomically(
                manifest.clone(),
                artifact,
                1,
                "runtime-revision",
                "declared-abi",
                &AcceptingVerifier,
                &mut quota,
            ),
            Ok(())
        );
        assert_eq!(registry.active(), Some(&manifest));
        assert_eq!(
            registry.activate_atomically(
                manifest,
                artifact,
                1,
                "runtime-revision",
                "different-abi",
                &AcceptingVerifier,
                &mut quota,
            ),
            Err(RuntimeError::UnsupportedCapability)
        );
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
