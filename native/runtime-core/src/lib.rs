//! Native, dependency-free safety core for the Android integration layer.
//!
//! Values that depend on device evidence, calibration, models, or release policy
//! are supplied by callers. This crate deliberately has no default capability,
//! inference backend, threshold, queue size, or thermal cutoff.

use std::collections::{HashMap, HashSet, VecDeque};
use std::sync::{
    atomic::{AtomicBool, Ordering},
    Arc,
};
use std::time::Instant;

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
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
    SessionExpired,
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
            Self::SessionExpired => "open a new session; an expired session cannot be extended or reused",
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
    owner_id: String,
    expires_at: Option<Instant>,
    state: SessionState,
    execution_return_state: Option<SessionState>,
    generation: u64,
}

impl Session {
    /// `owner_id` must be non-blank: session ownership is explicit, not
    /// inferred. `expires_at` is `None` for a session with no fixed expiry;
    /// callers that require bounded lifetime supply an explicit deadline
    /// rather than relying on an implicit default.
    pub fn new(
        owner_id: impl Into<String>,
        expires_at: Option<Instant>,
    ) -> Result<Self, RuntimeError> {
        let owner_id = owner_id.into();
        if owner_id.trim().is_empty() {
            return Err(RuntimeError::InvalidConfiguration);
        }
        Ok(Self {
            owner_id,
            expires_at,
            state: SessionState::Created,
            execution_return_state: None,
            generation: 0,
        })
    }

    pub fn owner_id(&self) -> &str {
        &self.owner_id
    }

    pub fn state(&self) -> SessionState {
        self.state
    }

    pub fn generation(&self) -> u64 {
        self.generation
    }

    /// Explicit ownership and expiry gate. A caller must pass this before
    /// `transition` or tool-call admission is authorized on this session:
    /// a mismatched owner is a permission failure, and an elapsed deadline
    /// is a distinct, non-retryable expiry rather than a generic lifecycle
    /// failure. Expiry is caller-timed (`now` is explicit, never sampled
    /// internally) so authorization stays deterministic and testable.
    pub fn authorize(&self, caller_id: &str, now: Instant) -> Result<(), RuntimeError> {
        if caller_id != self.owner_id {
            return Err(RuntimeError::PermissionDenied);
        }
        if let Some(expires_at) = self.expires_at {
            if now >= expires_at {
                return Err(RuntimeError::SessionExpired);
            }
        }
        Ok(())
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

#[derive(Debug, Clone, PartialEq, Eq, Hash, Serialize, Deserialize)]
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

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum RetentionScope {
    Ephemeral,
    Session,
    Persisted,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct RetentionPolicy {
    pub scope: RetentionScope,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub expires_at_unix_ms: Option<u64>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct EvidenceReference {
    pub digest: String,
    pub kind: String,
    pub retention: RetentionPolicy,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum EvidenceAvailability {
    Available,
    Redacted,
    Unavailable,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Provenance {
    pub runtime_revision: String,
    pub model_bundle_digest: String,
    pub preprocessing_digest: String,
    pub backend: String,
    pub device_profile: String,
}

/// Mirrors the canonical schema's `observation.status` enum. Distinct from
/// [`EvidenceAvailability`]: status describes the observation's own
/// completion/redaction state, while evidence availability describes only
/// its attached evidence items. An observation can be `Completed` with no
/// available evidence, or `Redacted` as a whole.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ObservationStatus {
    Completed,
    Redacted,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum DesignBinding {
    Unbound,
    Registered,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct DesignIdentity {
    pub binding: DesignBinding,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub project_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub board_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reference_designator: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Observation {
    pub schema_version: String,
    pub observation_id: String,
    pub tool_call_id: String,
    pub session_id: String,
    pub context_revision: String,
    /// The capability/tool this observation resulted from. Validated against
    /// the originating call's tool identity, never taken on trust alone.
    pub kind: String,
    pub source: String,
    pub captured_monotonic_ms: u64,
    pub captured_wall_time_unix_ms: u64,
    pub design_identity: DesignIdentity,
    pub status: ObservationStatus,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub coordinate_frame_id: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub registration_revision: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub confidence: Option<f64>,
    pub evidence_availability: EvidenceAvailability,
    pub evidence: Vec<EvidenceReference>,
    pub provenance: Provenance,
}

impl Observation {
    pub fn to_json(&self, max_bytes: usize) -> Result<Vec<u8>, RuntimeError> {
        if max_bytes == 0 {
            return Err(RuntimeError::InvalidConfiguration);
        }
        validate_observation_intrinsic(self)?;
        let bytes =
            serde_json::to_vec(self).map_err(|_| RuntimeError::InternalInvariantViolation)?;
        if bytes.len() > max_bytes {
            return Err(RuntimeError::ResourceExhausted);
        }
        Ok(bytes)
    }

    pub fn from_json(bytes: &[u8], max_bytes: usize) -> Result<Self, RuntimeError> {
        if max_bytes == 0 {
            return Err(RuntimeError::InvalidConfiguration);
        }
        if bytes.len() > max_bytes {
            return Err(RuntimeError::ResourceExhausted);
        }
        let observation: Self =
            serde_json::from_slice(bytes).map_err(|_| RuntimeError::InvalidConfiguration)?;
        validate_observation_intrinsic(&observation)?;
        Ok(observation)
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Abstention {
    pub reason: RuntimeError,
    pub recoverable: bool,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub enum TerminalOutcome {
    Observation(Box<Observation>),
    Abstained(Abstention),
    Cancelled,
    Error(RuntimeError),
}

const CALL_LEDGER_SNAPSHOT_VERSION: u16 = 1;

#[derive(Debug)]
pub struct CallLedger {
    capacity: usize,
    admitted: HashMap<String, CallIdentity>,
    terminal: HashMap<String, TerminalOutcome>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
struct CallIdentity {
    schema_version: String,
    session_id: String,
    context_revision: String,
    tool: ToolId,
}

#[derive(Debug, Serialize, Deserialize)]
struct CallLedgerSnapshot {
    version: u16,
    entries: Vec<CallLedgerSnapshotEntry>,
}

#[derive(Debug, Serialize, Deserialize)]
struct CallLedgerSnapshotEntry {
    tool_call_id: String,
    identity: CallIdentity,
    terminal: Option<TerminalOutcome>,
}

#[derive(Debug, Clone, PartialEq)]
pub enum CallAdmission {
    Accepted,
    Pending,
    Completed(TerminalOutcome),
}

impl CallLedger {
    pub fn new(capacity: usize) -> Result<Self, RuntimeError> {
        if capacity == 0 {
            return Err(RuntimeError::InvalidConfiguration);
        }
        Ok(Self {
            capacity,
            admitted: HashMap::new(),
            terminal: HashMap::new(),
        })
    }

    pub fn admit(&mut self, call: &ToolCall) -> Result<CallAdmission, RuntimeError> {
        let identity = CallIdentity {
            schema_version: call.schema_version.clone(),
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
        if self.admitted.len() >= self.capacity {
            return Err(RuntimeError::ResourceExhausted);
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
        if identity.schema_version != call.schema_version
            || identity.session_id != call.session_id
            || identity.context_revision != call.context_revision
            || identity.tool != call.tool
        {
            return Err(RuntimeError::ContextRevisionMismatch);
        }
        validate_terminal_outcome(
            &CallIdentity {
                schema_version: call.schema_version.clone(),
                session_id: call.session_id.clone(),
                context_revision: call.context_revision.clone(),
                tool: call.tool.clone(),
            },
            &call.tool_call_id,
            &outcome,
        )?;
        self.terminal.insert(call.tool_call_id.clone(), outcome);
        Ok(())
    }

    /// Produces a versioned, deterministic snapshot suitable for an
    /// app-private atomic persistence layer. Entries are sorted by call ID so
    /// identical ledger state always yields identical bytes.
    pub fn snapshot_json(&self, max_snapshot_bytes: usize) -> Result<Vec<u8>, RuntimeError> {
        if max_snapshot_bytes == 0 {
            return Err(RuntimeError::InvalidConfiguration);
        }
        let mut call_ids: Vec<_> = self.admitted.keys().collect();
        call_ids.sort();
        let entries = call_ids
            .into_iter()
            .map(|tool_call_id| CallLedgerSnapshotEntry {
                tool_call_id: tool_call_id.clone(),
                identity: self.admitted[tool_call_id].clone(),
                terminal: self.terminal.get(tool_call_id).cloned(),
            })
            .collect();
        let snapshot = serde_json::to_vec(&CallLedgerSnapshot {
            version: CALL_LEDGER_SNAPSHOT_VERSION,
            entries,
        })
        .map_err(|_| RuntimeError::InternalInvariantViolation)?;
        if snapshot.len() > max_snapshot_bytes {
            return Err(RuntimeError::ResourceExhausted);
        }
        Ok(snapshot)
    }

    /// Restores only validated idempotency state. Unknown versions, duplicate
    /// IDs, malformed identities, and terminal outcomes that do not match the
    /// admitted call fail closed before any state is returned.
    pub fn restore_json(
        snapshot: &[u8],
        max_snapshot_bytes: usize,
        capacity: usize,
    ) -> Result<Self, RuntimeError> {
        if max_snapshot_bytes == 0 || capacity == 0 {
            return Err(RuntimeError::InvalidConfiguration);
        }
        if snapshot.len() > max_snapshot_bytes {
            return Err(RuntimeError::ResourceExhausted);
        }
        let snapshot: CallLedgerSnapshot =
            serde_json::from_slice(snapshot).map_err(|_| RuntimeError::InvalidConfiguration)?;
        if snapshot.version != CALL_LEDGER_SNAPSHOT_VERSION {
            return Err(RuntimeError::IncompatibleSchema);
        }
        if snapshot.entries.len() > capacity {
            return Err(RuntimeError::ResourceExhausted);
        }
        let mut ledger = Self::new(capacity)?;
        for entry in snapshot.entries {
            if entry.tool_call_id.trim().is_empty()
                || entry.identity.schema_version.trim().is_empty()
                || entry.identity.session_id.trim().is_empty()
                || entry.identity.context_revision.trim().is_empty()
                || entry.identity.tool.0.trim().is_empty()
                || ledger.admitted.contains_key(&entry.tool_call_id)
            {
                return Err(RuntimeError::InvalidConfiguration);
            }
            if let Some(outcome) = &entry.terminal {
                validate_terminal_outcome(&entry.identity, &entry.tool_call_id, outcome)?;
            }
            ledger
                .admitted
                .insert(entry.tool_call_id.clone(), entry.identity);
            if let Some(outcome) = entry.terminal {
                ledger.terminal.insert(entry.tool_call_id, outcome);
            }
        }
        Ok(ledger)
    }

    pub fn terminal(&self, tool_call_id: &str) -> Option<&TerminalOutcome> {
        self.terminal.get(tool_call_id)
    }
}

fn validate_terminal_outcome(
    identity: &CallIdentity,
    tool_call_id: &str,
    outcome: &TerminalOutcome,
) -> Result<(), RuntimeError> {
    if let TerminalOutcome::Observation(observation) = outcome {
        validate_observation_intrinsic(observation)?;
        if observation.schema_version != identity.schema_version
            || observation.tool_call_id != tool_call_id
            || observation.session_id != identity.session_id
            || observation.context_revision != identity.context_revision
            || observation.kind != identity.tool.0
        {
            return Err(RuntimeError::InvalidConfiguration);
        }
    }
    Ok(())
}

fn validate_observation_intrinsic(observation: &Observation) -> Result<(), RuntimeError> {
    let design_identity_invalid = match observation.design_identity.binding {
        DesignBinding::Unbound => {
            observation.design_identity.project_id.is_some()
                || observation.design_identity.board_id.is_some()
                || observation.design_identity.reference_designator.is_some()
        }
        DesignBinding::Registered => {
            observation
                .design_identity
                .project_id
                .as_deref()
                .map_or(true, |value| value.trim().is_empty())
                || observation
                    .design_identity
                    .board_id
                    .as_deref()
                    .map_or(true, |value| value.trim().is_empty())
                || observation
                    .design_identity
                    .reference_designator
                    .as_deref()
                    .is_some_and(|value| value.trim().is_empty())
        }
    };
    let retention_invalid = observation.evidence.iter().any(|evidence| {
        let invalid_expiry = match evidence.retention.scope {
            RetentionScope::Ephemeral | RetentionScope::Session => {
                evidence.retention.expires_at_unix_ms.is_some()
            }
            RetentionScope::Persisted => evidence
                .retention
                .expires_at_unix_ms
                .map_or(true, |expiry| {
                    expiry <= observation.captured_wall_time_unix_ms
                }),
        };
        !is_canonical_sha256(&evidence.digest) || evidence.kind.trim().is_empty() || invalid_expiry
    });

    if observation.schema_version.trim().is_empty()
        || observation.observation_id.trim().is_empty()
        || observation.tool_call_id.trim().is_empty()
        || observation.session_id.trim().is_empty()
        || observation.context_revision.trim().is_empty()
        || observation.kind.trim().is_empty()
        || observation.source.trim().is_empty()
        || design_identity_invalid
        || (observation.status == ObservationStatus::Redacted)
            != (observation.evidence_availability == EvidenceAvailability::Redacted)
        || observation
            .confidence
            .is_some_and(|value| !value.is_finite() || !(0.0..=1.0).contains(&value))
        || observation.coordinate_frame_id.is_some() != observation.registration_revision.is_some()
        || (observation.coordinate_frame_id.is_some() && observation.confidence.is_none())
        || (observation.evidence_availability == EvidenceAvailability::Available
            && observation.evidence.is_empty())
        || (observation.evidence_availability != EvidenceAvailability::Available
            && !observation.evidence.is_empty())
        || retention_invalid
        || observation.provenance.runtime_revision.trim().is_empty()
        || !is_canonical_sha256(&observation.provenance.model_bundle_digest)
        || !is_canonical_sha256(&observation.provenance.preprocessing_digest)
        || observation.provenance.backend.trim().is_empty()
        || observation.provenance.device_profile.trim().is_empty()
    {
        return Err(RuntimeError::InvalidConfiguration);
    }
    Ok(())
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ExecutionStage {
    Camera,
    Audio,
    Preprocessing,
    Inference,
}

#[derive(Debug, Clone, Default)]
pub struct CancellationSignal(Arc<AtomicBool>);

impl CancellationSignal {
    pub fn cancel(&self) {
        self.0.store(true, Ordering::Release);
    }

    pub fn is_cancelled(&self) -> bool {
        self.0.load(Ordering::Acquire)
    }
}

#[derive(Debug, Clone)]
pub struct ExecutionGuard {
    deadline: Instant,
    cancellation: CancellationSignal,
}

impl ExecutionGuard {
    pub fn new(deadline: Instant, cancellation: CancellationSignal) -> Self {
        Self {
            deadline,
            cancellation,
        }
    }

    /// The same guard is passed through every execution stage. Stage identity
    /// is explicit for instrumentation while cancellation and deadline
    /// semantics remain identical across camera, audio, preprocessing, and
    /// inference implementations.
    pub fn enter(
        &self,
        stage: ExecutionStage,
        now: Instant,
    ) -> Result<ExecutionStage, RuntimeError> {
        if self.cancellation.is_cancelled() {
            return Err(RuntimeError::Cancelled);
        }
        if now >= self.deadline {
            return Err(RuntimeError::DeadlineExceeded);
        }
        Ok(stage)
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

#[derive(Debug, Clone, PartialEq)]
pub struct BackendEvidence {
    pub device_profile: String,
    pub abi: String,
    pub conformance_digest: String,
    pub lifecycle_digest: String,
    pub thermal_digest: String,
    pub silent_fallback_detected: bool,
}

#[derive(Debug, Clone, PartialEq)]
pub struct BackendCandidate {
    pub candidate_id: String,
    pub backend: String,
    pub evidence: BackendEvidence,
}

#[derive(Debug, Clone, PartialEq)]
pub struct BackendSelection {
    pub candidate_id: String,
    pub backend: String,
    pub evidence: BackendEvidence,
}

pub fn select_measured_backend(
    preference_order: &[String],
    candidates: &[BackendCandidate],
    device_profile: &str,
    abi: &str,
) -> Result<BackendSelection, RuntimeError> {
    if preference_order.is_empty() || device_profile.trim().is_empty() || abi.trim().is_empty() {
        return Err(RuntimeError::InvalidConfiguration);
    }
    for preferred_id in preference_order {
        let Some(candidate) = candidates
            .iter()
            .find(|candidate| &candidate.candidate_id == preferred_id)
        else {
            continue;
        };
        if candidate.candidate_id.trim().is_empty()
            || candidate.backend.trim().is_empty()
            || candidate.evidence.device_profile != device_profile
            || candidate.evidence.abi != abi
            || candidate.evidence.silent_fallback_detected
            || !is_canonical_sha256(&candidate.evidence.conformance_digest)
            || !is_canonical_sha256(&candidate.evidence.lifecycle_digest)
            || !is_canonical_sha256(&candidate.evidence.thermal_digest)
        {
            continue;
        }
        return Ok(BackendSelection {
            candidate_id: candidate.candidate_id.clone(),
            backend: candidate.backend.clone(),
            evidence: candidate.evidence.clone(),
        });
    }
    Err(RuntimeError::ModelUnavailable)
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

/// One declared entry from an archive input (model bundle, assembly-context
/// bundle, or any other packaged artifact). Format-agnostic: the caller's
/// unpacking layer supplies these from whatever container format it reads,
/// without this validator branching on which one.
#[derive(Debug, Clone, PartialEq)]
pub struct ArchiveEntry {
    pub path: String,
    pub is_symlink: bool,
    pub compressed_bytes: u64,
    pub uncompressed_bytes: u64,
}

/// Rejects the archive-input classes `SECURITY_AND_PRIVACY.md` requires be
/// rejected: absolute paths, traversal, symlinks, duplicate entries, and
/// decompression bombs (both per-entry ratio and total expanded size).
/// Entries are validated before any byte is written to disk; this function
/// performs no I/O itself.
pub fn validate_archive_entries(
    entries: &[ArchiveEntry],
    max_total_uncompressed_bytes: u64,
    max_compression_ratio: u64,
) -> Result<(), RuntimeError> {
    if max_total_uncompressed_bytes == 0 || max_compression_ratio == 0 {
        return Err(RuntimeError::InvalidConfiguration);
    }
    let mut seen_paths = HashSet::new();
    let mut total_uncompressed: u64 = 0;
    for entry in entries {
        let starts_with_drive_letter = entry.path.len() >= 2
            && entry.path.as_bytes()[0].is_ascii_alphabetic()
            && entry.path.as_bytes()[1] == b':';
        if entry.path.trim().is_empty()
            || entry.path.starts_with('/')
            || entry.path.starts_with('\\')
            || starts_with_drive_letter
            || entry.path.split(['/', '\\']).any(|segment| segment == "..")
            || entry.is_symlink
        {
            return Err(RuntimeError::InvalidArgument);
        }
        if !seen_paths.insert(entry.path.clone()) {
            return Err(RuntimeError::DuplicateReference);
        }
        if entry.uncompressed_bytes > 0 {
            if entry.compressed_bytes == 0 {
                // Infinite expansion ratio: an unambiguous bomb signature.
                return Err(RuntimeError::ResourceExhausted);
            }
            if entry.uncompressed_bytes / entry.compressed_bytes > max_compression_ratio {
                return Err(RuntimeError::ResourceExhausted);
            }
        }
        total_uncompressed = total_uncompressed
            .checked_add(entry.uncompressed_bytes)
            .ok_or(RuntimeError::ResourceExhausted)?;
        if total_uncompressed > max_total_uncompressed_bytes {
            return Err(RuntimeError::ResourceExhausted);
        }
    }
    Ok(())
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
    use std::sync::{Arc, Mutex};
    use std::thread;
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

    fn backend_candidate(fallback_detected: bool) -> BackendCandidate {
        BackendCandidate {
            candidate_id: "candidate".into(),
            backend: "declared-backend".into(),
            evidence: BackendEvidence {
                device_profile: "profile".into(),
                abi: "abi".into(),
                conformance_digest:
                    "sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee".into(),
                lifecycle_digest:
                    "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff".into(),
                thermal_digest:
                    "sha256:1111111111111111111111111111111111111111111111111111111111111111".into(),
                silent_fallback_detected: fallback_detected,
            },
        }
    }

    #[test]
    fn session_ownership_is_explicit_and_rejects_other_callers() {
        let session = Session::new("owner-a", None).unwrap();
        assert_eq!(session.authorize("owner-a", Instant::now()), Ok(()));
        assert_eq!(
            session.authorize("owner-b", Instant::now()),
            Err(RuntimeError::PermissionDenied)
        );
    }

    #[test]
    fn session_expiry_is_explicit_and_caller_timed() {
        let now = Instant::now();
        let session = Session::new("owner", Some(now + Duration::from_secs(60))).unwrap();
        assert_eq!(session.authorize("owner", now), Ok(()));
        assert_eq!(
            session.authorize("owner", now + Duration::from_secs(60)),
            Err(RuntimeError::SessionExpired)
        );
        assert!(!RuntimeError::SessionExpired.retryable());
    }

    #[test]
    fn a_blank_owner_id_is_rejected_at_construction() {
        assert_eq!(
            Session::new("   ", None).err(),
            Some(RuntimeError::InvalidConfiguration)
        );
    }

    #[test]
    fn suspend_invalidates_work_and_resume_requires_calibration() {
        let mut session = Session::new("owner", None).unwrap();
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
        let mut session = Session::new("owner", None).unwrap();
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
        let mut session = Session::new("owner", None).unwrap();
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
    fn a_different_contract_version_is_rejected_not_silently_upgraded() {
        // Additive/minor contract changes never bump `contract_version` (see
        // RUNTIME_CONTRACT.md's Compatibility section), so any observed
        // mismatch here is a genuine major/incompatible change and must be
        // rejected rather than treated as an implicit upgrade.
        let now = Instant::now();
        let manifest = CapabilityManifest::new("contract.v1", [ToolId("inspect".into())]).unwrap();
        let call = ToolCall {
            schema_version: "contract.v2".into(),
            tool_call_id: "call-version".into(),
            session_id: "session-version".into(),
            context_revision: "context-version".into(),
            tool: ToolId("inspect".into()),
            deadline: now + Duration::from_secs(1),
        };
        assert_eq!(
            call.validate(&manifest, "context-version", now),
            Err(RuntimeError::InvalidConfiguration)
        );
    }

    #[test]
    fn failure_categories_stay_distinct_across_the_contract() {
        // #22 requires that "unsupported capability" is never confused with
        // unavailable model, invalid input, overload, or inference failure.
        // Each comes from an independent code path; assert they cannot
        // collapse onto the same variant.
        let categories = [
            RuntimeError::UnsupportedCapability,
            RuntimeError::ModelUnavailable,
            RuntimeError::InvalidArgument,
            RuntimeError::ResourceExhausted,
            RuntimeError::BackendFailure,
        ];
        for (i, a) in categories.iter().enumerate() {
            for (j, b) in categories.iter().enumerate() {
                assert_eq!(i == j, a == b, "{a:?} vs {b:?} distinctness mismatch");
            }
        }

        let manifest = CapabilityManifest::new("contract.v1", []).unwrap();
        assert_eq!(
            manifest.require(&ToolId("inspect".into())),
            Err(RuntimeError::UnsupportedCapability)
        );
        assert_eq!(
            select_measured_backend(&["candidate".into()], &[], "profile", "abi"),
            Err(RuntimeError::ModelUnavailable)
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
            schema_version: call.schema_version.clone(),
            observation_id: "observation-1".into(),
            tool_call_id: call.tool_call_id.clone(),
            session_id: call.session_id.clone(),
            context_revision: call.context_revision.clone(),
            kind: call.tool.0.clone(),
            source: "camera".into(),
            captured_monotonic_ms: 100,
            captured_wall_time_unix_ms: 1_000,
            design_identity: DesignIdentity {
                binding: DesignBinding::Registered,
                project_id: Some("project".into()),
                board_id: Some("board".into()),
                reference_designator: Some("U1".into()),
            },
            status: ObservationStatus::Completed,
            coordinate_frame_id: Some(10),
            registration_revision: Some(4),
            confidence: Some(0.9),
            evidence_availability: EvidenceAvailability::Available,
            evidence: vec![EvidenceReference {
                digest: "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                    .into(),
                kind: "frame".into(),
                retention: RetentionPolicy {
                    scope: RetentionScope::Session,
                    expires_at_unix_ms: None,
                },
            }],
            provenance: Provenance {
                runtime_revision: "runtime-revision".into(),
                model_bundle_digest:
                    "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb".into(),
                preprocessing_digest:
                    "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc".into(),
                backend: "selected-by-policy".into(),
                device_profile: "profile-revision".into(),
            },
        };
        let mut ledger = CallLedger::new(8).unwrap();
        assert_eq!(ledger.admit(&call), Ok(CallAdmission::Accepted));
        assert_eq!(
            ledger.finish(
                &call,
                TerminalOutcome::Observation(Box::new(observation.clone()))
            ),
            Ok(())
        );
        assert_eq!(
            ledger.finish(&call, TerminalOutcome::Observation(Box::new(observation))),
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
            schema_version: call.schema_version.clone(),
            observation_id: "observation-2".into(),
            tool_call_id: call.tool_call_id.clone(),
            session_id: call.session_id.clone(),
            context_revision: call.context_revision.clone(),
            kind: call.tool.0.clone(),
            source: "camera".into(),
            captured_monotonic_ms: 100,
            captured_wall_time_unix_ms: 1_000,
            design_identity: DesignIdentity {
                binding: DesignBinding::Unbound,
                project_id: None,
                board_id: None,
                reference_designator: None,
            },
            status: ObservationStatus::Completed,
            coordinate_frame_id: Some(10),
            registration_revision: None,
            confidence: Some(0.9),
            evidence_availability: EvidenceAvailability::Unavailable,
            evidence: Vec::new(),
            provenance: Provenance {
                runtime_revision: "runtime-revision".into(),
                model_bundle_digest:
                    "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb".into(),
                preprocessing_digest:
                    "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc".into(),
                backend: "selected-by-policy".into(),
                device_profile: "profile-revision".into(),
            },
        };
        let mut ledger = CallLedger::new(8).unwrap();
        assert_eq!(ledger.admit(&call), Ok(CallAdmission::Accepted));
        assert_eq!(
            ledger.finish(&call, TerminalOutcome::Observation(Box::new(invalid))),
            Err(RuntimeError::InvalidConfiguration)
        );
    }

    fn base_observation(call: &ToolCall) -> Observation {
        Observation {
            schema_version: call.schema_version.clone(),
            observation_id: "observation-envelope".into(),
            tool_call_id: call.tool_call_id.clone(),
            session_id: call.session_id.clone(),
            context_revision: call.context_revision.clone(),
            kind: call.tool.0.clone(),
            source: "camera".into(),
            captured_monotonic_ms: 100,
            captured_wall_time_unix_ms: 1_000,
            design_identity: DesignIdentity {
                binding: DesignBinding::Unbound,
                project_id: None,
                board_id: None,
                reference_designator: None,
            },
            status: ObservationStatus::Completed,
            coordinate_frame_id: None,
            registration_revision: None,
            confidence: None,
            evidence_availability: EvidenceAvailability::Unavailable,
            evidence: Vec::new(),
            provenance: Provenance {
                runtime_revision: "runtime-revision".into(),
                model_bundle_digest:
                    "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb".into(),
                preprocessing_digest:
                    "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc".into(),
                backend: "selected-by-policy".into(),
                device_profile: "profile-revision".into(),
            },
        }
    }

    #[test]
    fn observation_kind_must_match_the_tool_that_was_actually_called() {
        let call = ToolCall {
            schema_version: "contract.v1".into(),
            tool_call_id: "call-kind".into(),
            session_id: "session-kind".into(),
            context_revision: "context-kind".into(),
            tool: ToolId("inspect".into()),
            deadline: Instant::now() + Duration::from_secs(1),
        };
        let mut spoofed = base_observation(&call);
        spoofed.kind = "locate".into();
        let mut ledger = CallLedger::new(8).unwrap();
        assert_eq!(ledger.admit(&call), Ok(CallAdmission::Accepted));
        assert_eq!(
            ledger.finish(&call, TerminalOutcome::Observation(Box::new(spoofed))),
            Err(RuntimeError::InvalidConfiguration)
        );
    }

    #[test]
    fn observation_schema_version_must_match_the_originating_call() {
        let call = ToolCall {
            schema_version: "contract.v1".into(),
            tool_call_id: "call-schema".into(),
            session_id: "session-schema".into(),
            context_revision: "context-schema".into(),
            tool: ToolId("inspect".into()),
            deadline: Instant::now() + Duration::from_secs(1),
        };
        let mut mismatched = base_observation(&call);
        mismatched.schema_version = "contract.v2".into();
        let mut ledger = CallLedger::new(8).unwrap();
        assert_eq!(ledger.admit(&call), Ok(CallAdmission::Accepted));
        assert_eq!(
            ledger.finish(&call, TerminalOutcome::Observation(Box::new(mismatched))),
            Err(RuntimeError::InvalidConfiguration)
        );
    }

    #[test]
    fn observation_status_and_evidence_redaction_cannot_disagree() {
        let call = ToolCall {
            schema_version: "contract.v1".into(),
            tool_call_id: "call-redaction".into(),
            session_id: "session-redaction".into(),
            context_revision: "context-redaction".into(),
            tool: ToolId("inspect".into()),
            deadline: Instant::now() + Duration::from_secs(1),
        };
        // status: Completed but evidence_availability: Redacted must not be
        // representable as a valid terminal observation.
        let mut inconsistent = base_observation(&call);
        inconsistent.status = ObservationStatus::Completed;
        inconsistent.evidence_availability = EvidenceAvailability::Redacted;
        let mut ledger = CallLedger::new(8).unwrap();
        assert_eq!(ledger.admit(&call), Ok(CallAdmission::Accepted));
        assert_eq!(
            ledger.finish(&call, TerminalOutcome::Observation(Box::new(inconsistent))),
            Err(RuntimeError::InvalidConfiguration)
        );

        // Both marked Redacted together is consistent and accepted.
        let mut consistent = base_observation(&call);
        consistent.status = ObservationStatus::Redacted;
        consistent.evidence_availability = EvidenceAvailability::Redacted;
        let mut ledger = CallLedger::new(8).unwrap();
        assert_eq!(ledger.admit(&call), Ok(CallAdmission::Accepted));
        assert_eq!(
            ledger.finish(&call, TerminalOutcome::Observation(Box::new(consistent))),
            Ok(())
        );
    }

    #[test]
    fn observation_json_is_bounded_deterministic_and_forward_compatible() {
        let call = ToolCall {
            schema_version: "contract.v1".into(),
            tool_call_id: "call-json".into(),
            session_id: "session-json".into(),
            context_revision: "context-json".into(),
            tool: ToolId("inspect".into()),
            deadline: Instant::now() + Duration::from_secs(1),
        };
        let observation = base_observation(&call);
        let encoded = observation.to_json(16_384).unwrap();
        assert_eq!(encoded, observation.to_json(16_384).unwrap());
        assert_eq!(
            Observation::from_json(&encoded, 16_384),
            Ok(observation.clone())
        );

        let mut forward: serde_json::Value = serde_json::from_slice(&encoded).unwrap();
        forward
            .as_object_mut()
            .unwrap()
            .insert("future_optional_field".into(), serde_json::json!(true));
        let forward = serde_json::to_vec(&forward).unwrap();
        assert_eq!(Observation::from_json(&forward, 16_384), Ok(observation));
        assert_eq!(
            Observation::from_json(&encoded, 1),
            Err(RuntimeError::ResourceExhausted)
        );
    }

    #[test]
    fn design_binding_cannot_claim_partial_or_hidden_identity() {
        let call = ToolCall {
            schema_version: "contract.v1".into(),
            tool_call_id: "call-design".into(),
            session_id: "session-design".into(),
            context_revision: "context-design".into(),
            tool: ToolId("inspect".into()),
            deadline: Instant::now() + Duration::from_secs(1),
        };
        let mut observation = base_observation(&call);
        observation.design_identity.project_id = Some("hidden-project".into());
        assert_eq!(
            observation.to_json(16_384),
            Err(RuntimeError::InvalidConfiguration)
        );

        observation.design_identity = DesignIdentity {
            binding: DesignBinding::Registered,
            project_id: Some("project".into()),
            board_id: None,
            reference_designator: None,
        };
        assert_eq!(
            observation.to_json(16_384),
            Err(RuntimeError::InvalidConfiguration)
        );

        observation.design_identity.board_id = Some("board".into());
        assert!(observation.to_json(16_384).is_ok());
    }

    #[test]
    fn evidence_retention_is_explicit_and_independent_from_success() {
        let call = ToolCall {
            schema_version: "contract.v1".into(),
            tool_call_id: "call-retention".into(),
            session_id: "session-retention".into(),
            context_revision: "context-retention".into(),
            tool: ToolId("inspect".into()),
            deadline: Instant::now() + Duration::from_secs(1),
        };
        let mut observation = base_observation(&call);
        observation.evidence_availability = EvidenceAvailability::Available;
        observation.evidence.push(EvidenceReference {
            digest: "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                .into(),
            kind: "roi".into(),
            retention: RetentionPolicy {
                scope: RetentionScope::Ephemeral,
                expires_at_unix_ms: None,
            },
        });
        assert!(observation.to_json(16_384).is_ok());

        observation.evidence[0].retention = RetentionPolicy {
            scope: RetentionScope::Persisted,
            expires_at_unix_ms: None,
        };
        assert_eq!(
            observation.to_json(16_384),
            Err(RuntimeError::InvalidConfiguration)
        );
        observation.evidence[0].retention.expires_at_unix_ms =
            Some(observation.captured_wall_time_unix_ms + 1);
        assert!(observation.to_json(16_384).is_ok());
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
        let mut ledger = CallLedger::new(8).unwrap();
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
    fn concurrent_duplicate_admission_never_races_into_a_second_execution() {
        // Simulates concurrent retries/reconnects racing the same idempotency
        // key: only one admission may win Accepted, and every other caller
        // observes Pending or the eventual single terminal outcome, never a
        // second independent execution.
        let call = ToolCall {
            schema_version: "contract.v1".into(),
            tool_call_id: "call-race".into(),
            session_id: "session-race".into(),
            context_revision: "context-race".into(),
            tool: ToolId("inspect".into()),
            deadline: Instant::now() + Duration::from_secs(5),
        };
        let ledger = Arc::new(Mutex::new(CallLedger::new(8).unwrap()));
        let handles: Vec<_> = (0..8)
            .map(|_| {
                let ledger = Arc::clone(&ledger);
                let call = call.clone();
                thread::spawn(move || ledger.lock().unwrap().admit(&call))
            })
            .collect();
        let outcomes: Vec<_> = handles.into_iter().map(|h| h.join().unwrap()).collect();

        let accepted = outcomes
            .iter()
            .filter(|outcome| **outcome == Ok(CallAdmission::Accepted))
            .count();
        assert_eq!(accepted, 1, "exactly one caller must win admission");
        assert!(
            outcomes.iter().all(|outcome| matches!(
                outcome,
                Ok(CallAdmission::Accepted | CallAdmission::Pending)
            )),
            "every other caller must observe Pending, never a distinct execution: {outcomes:?}"
        );
    }

    #[test]
    fn reconnect_and_process_restart_preserve_idempotency_state() {
        let now = Instant::now();
        let completed = ToolCall {
            schema_version: "contract.v1".into(),
            tool_call_id: "completed-call".into(),
            session_id: "session".into(),
            context_revision: "context".into(),
            tool: ToolId("inspect".into()),
            deadline: now + Duration::from_secs(1),
        };
        let mut pending = completed.clone();
        pending.tool_call_id = "pending-call".into();

        let mut ledger = CallLedger::new(8).unwrap();
        assert_eq!(ledger.admit(&pending), Ok(CallAdmission::Accepted));
        assert_eq!(ledger.admit(&completed), Ok(CallAdmission::Accepted));
        assert_eq!(
            ledger.finish(
                &completed,
                TerminalOutcome::Error(RuntimeError::BackendFailure)
            ),
            Ok(())
        );

        let snapshot = ledger.snapshot_json(16_384).unwrap();
        assert_eq!(snapshot, ledger.snapshot_json(16_384).unwrap());
        let mut restored = CallLedger::restore_json(&snapshot, 16_384, 8).unwrap();
        assert_eq!(restored.admit(&pending), Ok(CallAdmission::Pending));
        assert_eq!(
            restored.admit(&completed),
            Ok(CallAdmission::Completed(TerminalOutcome::Error(
                RuntimeError::BackendFailure
            )))
        );

        let mut conflicting = completed;
        conflicting.session_id = "other-session".into();
        assert_eq!(
            restored.admit(&conflicting),
            Err(RuntimeError::ContextRevisionMismatch)
        );
    }

    #[test]
    fn malformed_or_unknown_ledger_snapshots_fail_closed() {
        assert_eq!(
            CallLedger::restore_json(br#"{"version":2,"entries":[]}"#, 16_384, 8).unwrap_err(),
            RuntimeError::IncompatibleSchema
        );
        assert_eq!(
            CallLedger::restore_json(br#"{"version":1,"entries":[{"tool_call_id":"","identity":{"schema_version":"contract.v1","session_id":"session","context_revision":"context","tool":"inspect"},"terminal":null}]}"#, 16_384, 8).unwrap_err(),
            RuntimeError::InvalidConfiguration
        );
        assert_eq!(
            CallLedger::restore_json(b"not-json", 16_384, 8).unwrap_err(),
            RuntimeError::InvalidConfiguration
        );
    }

    #[test]
    fn ledger_capacity_and_snapshot_size_are_caller_bounded() {
        let now = Instant::now();
        let mut first = ToolCall {
            schema_version: "contract.v1".into(),
            tool_call_id: "first".into(),
            session_id: "session".into(),
            context_revision: "context".into(),
            tool: ToolId("inspect".into()),
            deadline: now + Duration::from_secs(1),
        };
        let mut ledger = CallLedger::new(1).unwrap();
        assert_eq!(ledger.admit(&first), Ok(CallAdmission::Accepted));
        first.tool_call_id = "second".into();
        assert_eq!(ledger.admit(&first), Err(RuntimeError::ResourceExhausted));
        assert_eq!(
            ledger.snapshot_json(1),
            Err(RuntimeError::ResourceExhausted)
        );
        assert!(matches!(
            CallLedger::restore_json(br#"{"version":1,"entries":[]}"#, 1, 1),
            Err(RuntimeError::ResourceExhausted)
        ));
    }

    #[test]
    fn cancellation_and_deadline_gate_every_execution_stage() {
        let now = Instant::now();
        let stages = [
            ExecutionStage::Camera,
            ExecutionStage::Audio,
            ExecutionStage::Preprocessing,
            ExecutionStage::Inference,
        ];
        let cancellation = CancellationSignal::default();
        let active = ExecutionGuard::new(now + Duration::from_secs(1), cancellation.clone());
        for stage in stages {
            assert_eq!(active.enter(stage, now), Ok(stage));
        }

        cancellation.cancel();
        for stage in stages {
            assert_eq!(active.enter(stage, now), Err(RuntimeError::Cancelled));
        }

        let expired = ExecutionGuard::new(now, CancellationSignal::default());
        for stage in stages {
            assert_eq!(
                expired.enter(stage, now),
                Err(RuntimeError::DeadlineExceeded)
            );
        }
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
    fn backend_selection_requires_complete_measured_evidence() {
        let preference = vec!["candidate".into()];
        let accepted = backend_candidate(false);
        assert_eq!(
            select_measured_backend(
                &preference,
                std::slice::from_ref(&accepted),
                "profile",
                "abi"
            ),
            Ok(BackendSelection {
                candidate_id: accepted.candidate_id,
                backend: accepted.backend,
                evidence: accepted.evidence,
            })
        );
        assert_eq!(
            select_measured_backend(&preference, &[backend_candidate(true)], "profile", "abi"),
            Err(RuntimeError::ModelUnavailable)
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

    #[test]
    fn thermal_state_never_alters_a_registration_confidence_threshold() {
        // #11 requires thermal degradation to be explicit and never change a
        // confidence threshold. Registration validation takes no thermal
        // input at all, so run it under every declared thermal state and
        // prove the accept/reject boundary at a fixed confidence never moves.
        let policy = ThermalPolicy::new(HashMap::from([
            (ThermalState::Nominal, 4),
            (ThermalState::Constrained, 2),
            (ThermalState::Critical, 0),
        ]))
        .unwrap();
        let registration = Registration {
            frame_id: 1,
            revision: 1,
            confidence: 0.5,
            required_confidence: 0.5,
            homography: [[1.0, 0.0, 0.0], [0.0, 1.0, 0.0], [0.0, 0.0, 1.0]],
        };
        for state in [
            ThermalState::Nominal,
            ThermalState::Constrained,
            ThermalState::Critical,
        ] {
            // Thermal concurrency narrows independently...
            let _ = policy.concurrency_for(state).unwrap();
            // ...while the same registration validates identically regardless
            // of which thermal state the caller is currently observing.
            assert_eq!(registration.validate(), Ok(()));
        }
    }

    #[test]
    fn scheduler_and_storage_hold_their_invariants_under_rapid_contention() {
        // A bounded, deterministic stand-in for #11's resource-exhaustion
        // requirement: hammer the scheduler and storage quota with far more
        // concurrent demand than either can satisfy and assert the
        // documented invariants (bounded capacity, observable drops, no
        // silent overcommit) hold throughout rather than only in the
        // steady-state cases the other tests cover. This does not replace a
        // genuine 20-minute on-device stress run with real camera/inference
        // load, which needs #12 (camera integration) to exist first.
        let now = Instant::now();
        let mut scheduler = BoundedRoiScheduler::new(4).unwrap();
        for i in 0..2_000u64 {
            let priority = if i % 5 == 0 {
                WorkPriority::Interactive
            } else {
                WorkPriority::Background
            };
            let outcome = scheduler.enqueue(
                request(&format!("key-{i}"), priority, now + Duration::from_secs(1)),
                now,
            );
            assert!(
                outcome.is_ok(),
                "enqueue must never panic or corrupt state: {outcome:?}"
            );
        }
        assert!(
            scheduler.dropped_stale() > 0,
            "overcommitted work must be observably dropped, not silently discarded"
        );
        let mut remaining = 0;
        while scheduler.next(now, 0).is_some() {
            remaining += 1;
        }
        assert!(
            remaining <= 4,
            "queue must never exceed its declared capacity"
        );

        let mut quota = StorageQuota::new(1_024).unwrap();
        let mut reserved = 0u64;
        let mut exhausted = 0u64;
        for _ in 0..10_000 {
            match quota.reserve(7) {
                Ok(()) => reserved += 7,
                Err(RuntimeError::ResourceExhausted) => exhausted += 1,
                Err(other) => panic!("unexpected error under contention: {other:?}"),
            }
        }
        assert!(exhausted > 0, "quota must reject once capacity is exceeded");
        assert!(
            reserved <= 1_024,
            "quota must never overcommit its declared capacity"
        );
    }

    fn plain_entry(path: &str) -> ArchiveEntry {
        ArchiveEntry {
            path: path.into(),
            is_symlink: false,
            compressed_bytes: 100,
            uncompressed_bytes: 100,
        }
    }

    #[test]
    fn ordinary_archive_entries_are_accepted() {
        let entries = [
            plain_entry("model/weights.bin"),
            plain_entry("model/manifest.json"),
        ];
        assert_eq!(validate_archive_entries(&entries, 1_000_000, 100), Ok(()));
    }

    #[test]
    fn absolute_and_drive_letter_paths_are_rejected() {
        assert_eq!(
            validate_archive_entries(&[plain_entry("/etc/passwd")], 1_000_000, 100),
            Err(RuntimeError::InvalidArgument)
        );
        assert_eq!(
            validate_archive_entries(&[plain_entry("C:\\Windows\\System32")], 1_000_000, 100),
            Err(RuntimeError::InvalidArgument)
        );
    }

    #[test]
    fn path_traversal_is_rejected() {
        assert_eq!(
            validate_archive_entries(&[plain_entry("model/../../etc/passwd")], 1_000_000, 100),
            Err(RuntimeError::InvalidArgument)
        );
    }

    #[test]
    fn symlink_entries_are_rejected() {
        let mut entry = plain_entry("model/link");
        entry.is_symlink = true;
        assert_eq!(
            validate_archive_entries(&[entry], 1_000_000, 100),
            Err(RuntimeError::InvalidArgument)
        );
    }

    #[test]
    fn duplicate_entries_are_rejected() {
        let entries = [
            plain_entry("model/weights.bin"),
            plain_entry("model/weights.bin"),
        ];
        assert_eq!(
            validate_archive_entries(&entries, 1_000_000, 100),
            Err(RuntimeError::DuplicateReference)
        );
    }

    #[test]
    fn a_single_entry_decompression_bomb_is_rejected() {
        let bomb = ArchiveEntry {
            path: "model/weights.bin".into(),
            is_symlink: false,
            compressed_bytes: 10,
            uncompressed_bytes: 10_000_000,
        };
        assert_eq!(
            validate_archive_entries(&[bomb], u64::MAX, 100),
            Err(RuntimeError::ResourceExhausted)
        );
    }

    #[test]
    fn total_expansion_over_the_declared_limit_is_rejected_even_without_any_single_bomb() {
        let entries = [
            plain_entry("model/part-1.bin"),
            plain_entry("model/part-2.bin"),
        ];
        // Each entry individually is a normal 1:1 ratio, but the declared
        // total budget only allows 150 bytes across the whole archive.
        assert_eq!(
            validate_archive_entries(&entries, 150, 100),
            Err(RuntimeError::ResourceExhausted)
        );
    }
}
