package ai.tokito.physical.runtime

enum class SensorKind {
    Camera,
    Microphone,
}

data class SensorPreconditions(
    val permissionGranted: Boolean,
    val appInForeground: Boolean,
    val sessionActive: Boolean,
)

enum class SensorDenial {
    PermissionDenied,
    BackgroundUnavailable,
    SessionInactive,
}

sealed interface SensorAdmission {
    data class Granted(val lease: SensorLease) : SensorAdmission
    data class Denied(val reason: SensorDenial) : SensorAdmission
}

class SensorLease internal constructor(
    private val controller: SensorAccessController,
    private val sensor: SensorKind,
    private val generation: Long,
) : AutoCloseable {
    private var closed = false

    override fun close() {
        if (!closed) {
            closed = true
            controller.release(sensor, generation)
        }
    }
}

/**
 * Session-scoped sensor lease registry. The Android host supplies permission,
 * foreground, and session state from platform APIs at admission time. Every
 * state change publishes the complete visible sensor set to the host UI.
 */
class SensorAccessController(
    private val onVisibilityChanged: (Set<SensorKind>) -> Unit,
) {
    private val active = mutableMapOf<SensorKind, Long>()
    private var generation = 0L

    @Synchronized
    fun acquire(sensor: SensorKind, preconditions: SensorPreconditions): SensorAdmission {
        val denial = when {
            !preconditions.permissionGranted -> SensorDenial.PermissionDenied
            !preconditions.appInForeground -> SensorDenial.BackgroundUnavailable
            !preconditions.sessionActive -> SensorDenial.SessionInactive
            else -> null
        }
        if (denial != null) return SensorAdmission.Denied(denial)

        generation = Math.addExact(generation, 1)
        active[sensor] = generation
        publishVisibility()
        return SensorAdmission.Granted(SensorLease(this, sensor, generation))
    }

    @Synchronized
    fun revalidate(sensor: SensorKind, preconditions: SensorPreconditions): SensorDenial? {
        val denial = when {
            !preconditions.permissionGranted -> SensorDenial.PermissionDenied
            !preconditions.appInForeground -> SensorDenial.BackgroundUnavailable
            !preconditions.sessionActive -> SensorDenial.SessionInactive
            else -> null
        }
        if (denial != null && active.remove(sensor) != null) publishVisibility()
        return denial
    }

    @Synchronized
    fun suspendOrStop() {
        if (active.isNotEmpty()) {
            active.clear()
            generation = Math.addExact(generation, 1)
            publishVisibility()
        }
    }

    @Synchronized
    fun visibleSensors(): Set<SensorKind> = active.keys.toSet()

    @Synchronized
    internal fun release(sensor: SensorKind, leaseGeneration: Long) {
        if (active[sensor] == leaseGeneration) {
            active.remove(sensor)
            publishVisibility()
        }
    }

    private fun publishVisibility() {
        onVisibilityChanged(active.keys.toSet())
    }
}

enum class DiagnosticCode {
    PermissionDenied,
    LifecycleUnavailable,
    DeadlineExceeded,
    Cancelled,
    ResourceExhausted,
    IntegrityFailure,
    InternalFailure,
}

/** Deliberately cannot carry transcripts, frames, tokens, identifiers, or paths. */
data class SafeDiagnostic(
    val code: DiagnosticCode,
    val counters: Map<String, Long>,
) {
    init {
        require(counters.keys.none { it.isBlank() }) { "diagnostic counter names must not be blank" }
        require(counters.values.none { it < 0 }) { "diagnostic counters must not be negative" }
    }
}

enum class EvidenceRetentionScope {
    Ephemeral,
    Session,
    Persisted,
}

data class EvidenceRetentionPolicy(
    val scope: EvidenceRetentionScope,
    val expiresAtUnixMs: Long?,
) {
    init {
        require((scope == EvidenceRetentionScope.Persisted) == (expiresAtUnixMs != null)) {
            "only persisted evidence declares an expiry"
        }
        require(expiresAtUnixMs == null || expiresAtUnixMs >= 0) {
            "evidence expiry must not be negative"
        }
    }

    fun deleteAtSessionEnd(): Boolean = scope != EvidenceRetentionScope.Persisted

    fun expired(nowUnixMs: Long): Boolean =
        expiresAtUnixMs?.let { nowUnixMs >= it } ?: false
}

data class RetainedEvidence(
    val evidenceId: String,
    val capturedAtUnixMs: Long,
    val policy: EvidenceRetentionPolicy,
) {
    init {
        require(evidenceId.isNotBlank()) { "evidence ID must not be blank" }
        require(capturedAtUnixMs >= 0) { "capture time must not be negative" }
        require(
            policy.expiresAtUnixMs == null || policy.expiresAtUnixMs > capturedAtUnixMs,
        ) { "persisted evidence must expire after capture" }
    }

    fun shouldDelete(sessionEnded: Boolean, nowUnixMs: Long): Boolean =
        (sessionEnded && policy.deleteAtSessionEnd()) || policy.expired(nowUnixMs)
}
