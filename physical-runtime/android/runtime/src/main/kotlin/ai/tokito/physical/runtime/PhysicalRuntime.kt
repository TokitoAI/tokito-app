package ai.tokito.physical.runtime

import java.util.UUID
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull

/**
 * Standalone lifecycle and capability boundary. Operations are supplied only
 * after the platform integration has accepted their model and device evidence.
 * The wire shape mirrors the canonical `capabilityHandshake` definition in
 * [contracts/physical-runtime.v1.schema.json](../../../../../../../contracts/physical-runtime.v1.schema.json);
 * it carries no backend, model, or tensor detail.
 */
class PhysicalRuntime(private val configuration: RuntimeConfiguration) {
    fun capabilities(): CapabilityHandshake = CapabilityHandshake(
        contractVersion = configuration.contractVersion,
        deviceProfile = configuration.deviceProfileId,
        capabilities = configuration.capabilities.toList(),
    )

    fun openSession(): RuntimeSession = RuntimeSession(UUID.randomUUID().toString())
}

/**
 * A single capability entry as defined by the canonical schema's
 * `capability` object. An operation absent from the advertised list is not
 * representable as a fake-supported entry; `modelAvailable = false` only
 * describes an accepted operation whose model bundle is not currently
 * resident.
 */
@Serializable
data class Capability(
    val operation: String,
    val version: String,
    @SerialName("model_available") val modelAvailable: Boolean,
    @SerialName("resource_limits") val resourceLimits: Map<String, ResourceLimitValue> = emptyMap(),
) {
    init {
        require(operation.isNotBlank()) { "operation must not be blank" }
        require(version.isNotBlank()) { "version must not be blank" }
        require(resourceLimits.keys.none { it.isBlank() }) { "resource limit keys must not be blank" }
    }
}

/**
 * One resource-limit value. The canonical schema permits integer, number,
 * string, or boolean here; callers declare only the shapes their evidence
 * actually supports instead of one runtime-wide numeric assumption.
 */
@Serializable(with = ResourceLimitValueSerializer::class)
sealed interface ResourceLimitValue {
    data class IntegerLimit(val value: Long) : ResourceLimitValue
    data class NumberLimit(val value: Double) : ResourceLimitValue
    data class TextLimit(val value: String) : ResourceLimitValue
    data class FlagLimit(val value: Boolean) : ResourceLimitValue
}

object ResourceLimitValueSerializer : KSerializer<ResourceLimitValue> {
    // A JsonElement passthrough, not a structured type: the primitive kind is
    // metadata only and is never used to drive (de)serialization here.
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ResourceLimitValue", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ResourceLimitValue) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: error("ResourceLimitValue can only be encoded as JSON")
        val element = when (value) {
            is ResourceLimitValue.IntegerLimit -> JsonPrimitive(value.value)
            is ResourceLimitValue.NumberLimit -> JsonPrimitive(value.value)
            is ResourceLimitValue.TextLimit -> JsonPrimitive(value.value)
            is ResourceLimitValue.FlagLimit -> JsonPrimitive(value.value)
        }
        jsonEncoder.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): ResourceLimitValue {
        val jsonDecoder = decoder as? JsonDecoder
            ?: error("ResourceLimitValue can only be decoded from JSON")
        val element = jsonDecoder.decodeJsonElement()
        val primitive = element as? JsonPrimitive
            ?: error("resource limit values must be JSON primitives, got $element")
        return when {
            !primitive.isString && primitive.booleanOrNull != null -> ResourceLimitValue.FlagLimit(primitive.boolean)
            !primitive.isString && primitive.longOrNull != null -> ResourceLimitValue.IntegerLimit(primitive.long)
            !primitive.isString && primitive.doubleOrNull != null -> ResourceLimitValue.NumberLimit(primitive.double)
            primitive.isString -> ResourceLimitValue.TextLimit(primitive.content)
            else -> error("unsupported resource limit primitive: $primitive")
        }
    }
}

/**
 * The Android integration's accepted configuration. `capabilities` lists only
 * operations whose model, backend evidence, permission policy, and resource
 * policy have already been accepted elsewhere; nothing here fabricates a
 * default operation set.
 */
data class RuntimeConfiguration(
    val contractVersion: String,
    val deviceProfileId: String,
    val capabilities: List<Capability> = emptyList(),
) {
    init {
        require(contractVersion.isNotBlank()) { "contractVersion must not be blank" }
        require(deviceProfileId.isNotBlank()) { "deviceProfileId must not be blank" }
        val operations = capabilities.map { it.operation }
        require(operations.size == operations.toSet().size) {
            "capabilities must not declare the same operation twice"
        }
    }
}

/**
 * The capability handshake returned to a client. Field names and shape mirror
 * the canonical `capabilityHandshake` schema so Android, native, and future
 * clients share one source of truth instead of three hand-maintained copies.
 */
@Serializable
data class CapabilityHandshake(
    @SerialName("contract_version") val contractVersion: String,
    @SerialName("device_profile") val deviceProfile: String,
    val capabilities: List<Capability>,
)

enum class RuntimeSessionState {
    Created,
    Calibrating,
    Ready,
    Degraded,
    Suspended,
    Stopping,
    Closed,
}

sealed interface LifecycleEvent {
    data object StartCalibration : LifecycleEvent
    data class CalibrationAccepted(val degraded: Boolean) : LifecycleEvent
    data object PermissionLost : LifecycleEvent
    data object CameraLost : LifecycleEvent
    data object Resume : LifecycleEvent
    data object Stop : LifecycleEvent
    data object Close : LifecycleEvent
}

/**
 * An opaque lifecycle session with deterministic transitions. Every rejected
 * transition throws the same [IllegalStateException] regardless of how many
 * times an equivalent call is retried, so duplicate or late-arriving
 * lifecycle callbacks are consistently rejected rather than silently
 * accepted on a retry.
 */
class RuntimeSession internal constructor(val id: String) {
    private var state = RuntimeSessionState.Created
    private var generation = 0L

    fun state(): RuntimeSessionState = synchronized(this) { state }

    fun generation(): Long = synchronized(this) { generation }

    fun transition(event: LifecycleEvent): RuntimeSessionState = synchronized(this) {
        val next = when {
            state == RuntimeSessionState.Created && event == LifecycleEvent.StartCalibration -> {
                RuntimeSessionState.Calibrating
            }
            state == RuntimeSessionState.Calibrating && event is LifecycleEvent.CalibrationAccepted -> {
                if (event.degraded) RuntimeSessionState.Degraded else RuntimeSessionState.Ready
            }
            (state == RuntimeSessionState.Ready || state == RuntimeSessionState.Degraded) &&
                (event == LifecycleEvent.PermissionLost || event == LifecycleEvent.CameraLost) -> {
                RuntimeSessionState.Suspended
            }
            state == RuntimeSessionState.Suspended && event == LifecycleEvent.Resume -> {
                RuntimeSessionState.Calibrating
            }
            (state == RuntimeSessionState.Ready ||
                state == RuntimeSessionState.Degraded ||
                state == RuntimeSessionState.Calibrating ||
                state == RuntimeSessionState.Suspended) && event == LifecycleEvent.Stop -> {
                RuntimeSessionState.Stopping
            }
            state == RuntimeSessionState.Stopping && event == LifecycleEvent.Close -> RuntimeSessionState.Closed
            else -> throw IllegalStateException("invalid lifecycle transition: $state + $event")
        }
        if (next == RuntimeSessionState.Suspended || event == LifecycleEvent.Resume) {
            generation = Math.addExact(generation, 1)
        }
        state = next
        next
    }
}
