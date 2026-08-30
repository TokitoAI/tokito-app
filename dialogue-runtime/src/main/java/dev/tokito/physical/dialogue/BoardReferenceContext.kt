package dev.tokito.physical.dialogue

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.math.BigDecimal
import java.security.MessageDigest

data class BoardReferenceIdentity(
    val manifestId: String,
    val manufacturer: String,
    val model: String,
    val revision: String?,
    val placementBound: Boolean,
    val contextRevision: String,
)

data class BoardReferenceContext(
    val identity: BoardReferenceIdentity,
    val index: LocalDesignContextIndex,
) {
    companion object {
        const val ACTIVE_RELATIVE_PATH = "contexts/active.board-reference.json"
        private const val MAX_MANIFEST_BYTES = 2_000_000L

        fun loadIfPresent(filesDir: File): BoardReferenceContext? {
            val file = File(filesDir, ACTIVE_RELATIVE_PATH)
            if (!file.exists()) return null
            require(file.isFile && file.length() in 1..MAX_MANIFEST_BYTES) {
                "active board reference is not a bounded regular file"
            }
            return parse(file.readText(Charsets.UTF_8))
        }

        fun parse(raw: String): BoardReferenceContext {
            require(raw.toByteArray(Charsets.UTF_8).size.toLong() in 1..MAX_MANIFEST_BYTES) {
                "board reference exceeds its byte limit"
            }
            return BoardReferenceParser(raw).parse()
        }
    }
}

private class BoardReferenceParser(private val raw: String) {
    private val root = try {
        JSONObject(raw)
    } catch (failure: JSONException) {
        throw IllegalArgumentException("board reference is not valid JSON", failure)
    }
    private lateinit var sources: Map<String, Source>

    fun parse(): BoardReferenceContext {
        exactKeys(
            root,
            setOf(
                "schema_version", "manifest_id", "board", "placement_binding",
                "sources", "landmarks", "connectors", "integrity",
            ),
            "manifest",
        )
        require(text(root, "schema_version") == "1.0") { "unsupported board-reference schema" }
        verifyDigest(raw)

        val manifestId = text(root, "manifest_id")
        val board = objectValue(root, "board")
        exactKeys(board, setOf("manufacturer", "model", "revision"), "board", optional = setOf("revision"))
        val manufacturer = text(board, "manufacturer")
        val model = text(board, "model")
        val revision = optionalText(board, "revision")

        sources = parseSources(array(root, "sources"))
        val binding = parseBinding(objectValue(root, "placement_binding"))
        val records = mutableListOf<DesignContextRecord>()
        records += boardRecord(manifestId, manufacturer, model, revision, binding)
        records += parseConnectors(array(root, "connectors"), manufacturer, model, binding)
        records += parseLandmarks(array(root, "landmarks"), manufacturer, model)
        require(records.size <= MAX_RECORDS) { "board reference contains too many records" }

        val contextRevision = "$manifestId@${text(objectValue(root, "integrity"), "manifest_digest")}"
        return BoardReferenceContext(
            identity = BoardReferenceIdentity(
                manifestId = manifestId,
                manufacturer = manufacturer,
                model = model,
                revision = revision,
                placementBound = binding.status == "authored",
                contextRevision = contextRevision,
            ),
            index = LocalDesignContextIndex(contextRevision, records),
        )
    }

    private fun verifyDigest(raw: String) {
        val integrity = objectValue(root, "integrity")
        exactKeys(integrity, setOf("manifest_digest"), "integrity")
        val declared = text(integrity, "manifest_digest")
        require(SHA256.matches(declared)) { "manifest digest has an invalid shape" }
        val actual = "sha256:" + MessageDigest.getInstance("SHA-256")
            .digest(canonicalBoardReferencePayload(raw).toByteArray(Charsets.UTF_8))
            .joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
        require(actual == declared) { "board-reference manifest digest mismatch: expected $declared, computed $actual" }
    }

    private fun parseSources(values: JSONArray): Map<String, Source> {
        require(values.length() in 1..MAX_SOURCES) { "board reference has an invalid source count" }
        val result = linkedMapOf<String, Source>()
        for (index in 0 until values.length()) {
            val value = objectValue(values, index, "source")
            exactKeys(
                value,
                setOf(
                    "source_id", "kind", "media_sha256", "locator", "license_status",
                    "redistributable", "review",
                ),
                "source",
            )
            val id = text(value, "source_id")
            val kind = text(value, "kind")
            require(kind in SOURCE_KINDS) { "source $id has an unsupported kind" }
            require(SHA256.matches(text(value, "media_sha256"))) { "source $id has an invalid digest" }
            text(value, "locator")
            val license = text(value, "license_status")
            require(license in LICENSE_STATES) { "source $id has an invalid license state" }
            val redistributable = boolean(value, "redistributable")
            require(license == "approved" || !redistributable) {
                "source $id cannot be redistributable before license approval"
            }
            val review = objectValue(value, "review")
            exactKeys(review, setOf("state", "reviewer", "reviewed_at"), "source review")
            val reviewState = text(review, "state")
            require(reviewState in REVIEW_STATES) { "source $id has an invalid review state" }
            text(review, "reviewer")
            text(review, "reviewed_at")
            require(result.put(id, Source(id, kind, reviewState)) == null) { "duplicate source ID: $id" }
        }
        return result
    }

    private fun parseBinding(value: JSONObject): Binding {
        exactKeys(
            value,
            setOf("status", "reason", "coordinate_frame_id", "source_id"),
            "placement binding",
            optional = setOf("coordinate_frame_id", "source_id"),
        )
        val status = text(value, "status")
        require(status == "unbound" || status == "authored") { "invalid placement binding" }
        val reason = text(value, "reason")
        val sourceId = optionalText(value, "source_id")
        val frameId = optionalText(value, "coordinate_frame_id")
        if (status == "authored") {
            require(frameId != null) { "authored placement requires a coordinate frame" }
            require(sources[sourceId]?.kind == "authored_design") {
                "authored placement must cite an authored-design source"
            }
        } else {
            require(sourceId == null && frameId == null) { "unbound placement cannot claim a frame or source" }
        }
        return Binding(status, reason)
    }

    private fun boardRecord(
        manifestId: String,
        manufacturer: String,
        model: String,
        revision: String?,
        binding: Binding,
    ): DesignContextRecord {
        val sourceIds = sources.values.filter { it.reviewState == "reviewed" }.mapTo(sortedSetOf()) { it.id }
        require(sourceIds.isNotEmpty()) { "board identity has no reviewed evidence" }
        val revisionText = revision?.let { ", revision $it" }.orEmpty()
        return DesignContextRecord(
            recordId = "board-$manifestId",
            authority = DesignFactAuthority.SUPPORTING,
            exactKeys = canonicalKeys(manufacturer, model),
            text = "$manufacturer $model$revisionText. Placement binding is ${binding.status}: ${binding.reason}",
            sourceIds = sourceIds,
            spokenAliases = setOf(manufacturer, model, "$manufacturer $model"),
            spokenSummary = "$manufacturer $model$revisionText is the active verified board reference. " +
                "Placement binding is ${binding.status}.",
        )
    }

    private fun parseConnectors(
        values: JSONArray,
        manufacturer: String,
        model: String,
        binding: Binding,
    ): List<DesignContextRecord> {
        require(values.length() <= MAX_CONNECTORS) { "board reference has too many connectors" }
        val seen = mutableSetOf<String>()
        return (0 until values.length()).map { index ->
            val connector = objectValue(values, index, "connector")
            exactKeys(
                connector,
                setOf("connector_id", "display_name", "pin_count", "pins", "evidence_refs"),
                "connector",
            )
            val id = text(connector, "connector_id")
            require(seen.add(id)) { "duplicate connector ID: $id" }
            val displayName = text(connector, "display_name")
            val pinCount = integer(connector, "pin_count", 1, MAX_PINS)
            val connectorEvidence = evidenceRefs(connector, "evidence_refs")
            val pins = array(connector, "pins")
            require(pins.length() == pinCount) { "connector $id pin count does not match its pins" }
            val pinTexts = mutableListOf<String>()
            val pinAnswers = mutableListOf<DesignContextAnswer>()
            val exactKeys = canonicalKeys(id)
            val numbers = mutableSetOf<Int>()
            val allEvidence = connectorEvidence.toMutableSet()
            for (pinIndex in 0 until pins.length()) {
                val pin = objectValue(pins, pinIndex, "connector pin")
                exactKeys(
                    pin,
                    setOf("number", "signal_name", "aliases", "functions", "electrical_role", "evidence_refs"),
                    "connector pin",
                )
                val number = integer(pin, "number", 1, pinCount)
                require(numbers.add(number)) { "connector $id contains duplicate pin $number" }
                val signal = text(pin, "signal_name")
                val aliases = stringArray(pin, "aliases")
                val functions = stringArray(pin, "functions")
                val role = text(pin, "electrical_role")
                require(role in ELECTRICAL_ROLES) { "connector $id pin $number has an invalid role" }
                require(role != "ground" || signal.uppercase() == "GND") { "ground pin must use GND" }
                require(role != "not_connected" || signal.uppercase() == "NC") { "not-connected pin must use NC" }
                require(role in setOf("ground", "not_connected") || signal.uppercase() !in setOf("GND", "NC")) {
                    "connector $id pin $number has a conflicting reserved signal"
                }
                val pinEvidence = evidenceRefs(pin, "evidence_refs")
                allEvidence += pinEvidence
                exactKeys += canonicalKeys(signal, *aliases.toTypedArray())
                val details = (listOf(role) + functions + aliases).distinct().joinToString("/")
                pinTexts += "$number=$signal [$details]"
                val functionsText = functions.takeIf { it.isNotEmpty() }
                    ?.joinToString(prefix = " Functions: ", postfix = ".")
                    .orEmpty()
                val aliasesText = aliases.takeIf { it.isNotEmpty() }
                    ?.joinToString(prefix = " Aliases: ", postfix = ".")
                    .orEmpty()
                pinAnswers += DesignContextAnswer(
                    requiredTokens = setOf(number.toString()),
                    text = "$id pin $number is $signal. Electrical role: $role.$functionsText$aliasesText",
                )
            }
            require(numbers == (1..pinCount).toSet()) { "connector $id pins must cover 1..$pinCount" }
            requireReviewed(allEvidence, "connector $id")
            val caveat = if (binding.status == "unbound") " No authored board-plane placement is available." else ""
            DesignContextRecord(
                recordId = "connector-${id.lowercase()}",
                authority = DesignFactAuthority.SUPPORTING,
                exactKeys = exactKeys,
                text = "$manufacturer $model $displayName pin map: ${pinTexts.joinToString("; ")}.$caveat",
                sourceIds = allEvidence,
                spokenAliases = setOf(id, displayName),
                spokenSummary = "$displayName has $pinCount pins.$caveat",
                detailAnswers = pinAnswers,
            )
        }
    }

    private fun parseLandmarks(
        values: JSONArray,
        manufacturer: String,
        model: String,
    ): List<DesignContextRecord> {
        require(values.length() <= MAX_LANDMARKS) { "board reference has too many landmarks" }
        val seen = mutableSetOf<String>()
        return (0 until values.length()).map { index ->
            val landmark = objectValue(values, index, "landmark")
            exactKeys(
                landmark,
                setOf(
                    "landmark_id", "display_name", "category", "identifiers", "guide_targets",
                    "evidence_refs", "query_aliases", "board_target",
                ),
                "landmark",
                optional = setOf("query_aliases", "board_target"),
            )
            val id = text(landmark, "landmark_id")
            require(seen.add(id)) { "duplicate landmark ID: $id" }
            val displayName = text(landmark, "display_name")
            val category = text(landmark, "category")
            require(category in LANDMARK_CATEGORIES) { "landmark $id has an invalid category" }
            val identifiers = objectValue(landmark, "identifiers")
            val identifierValues = identifiers.keys().asSequence().sorted().map { key ->
                text(identifiers, key)
            }.toList()
            val queryAliases = if (landmark.has("query_aliases")) {
                stringArray(landmark, "query_aliases")
            } else {
                emptyList()
            }
            val evidence = evidenceRefs(landmark, "evidence_refs").toMutableSet()
            val boardTarget = landmark.optJSONObject("board_target")?.let { target ->
                exactKeys(
                    target,
                    setOf("reference_designator", "x_normalized", "y_normalized", "source_id"),
                    "board target",
                )
                val referenceDesignator = text(target, "reference_designator")
                require(KEY.matches(referenceDesignator.uppercase())) { "board target has an invalid reference designator" }
                val sourceId = text(target, "source_id")
                require(sources[sourceId]?.kind == "authored_design") {
                    "board target for $id must cite authored design evidence"
                }
                require(sources[sourceId]?.reviewState == "reviewed") {
                    "board target for $id cites unreviewed evidence"
                }
                evidence += sourceId
                DesignBoardTarget(
                    label = referenceDesignator,
                    xNormalized = decimal(target, "x_normalized", BigDecimal.ZERO, BigDecimal.ONE).toDouble(),
                    yNormalized = decimal(target, "y_normalized", BigDecimal.ZERO, BigDecimal.ONE).toDouble(),
                )
            }
            val targets = array(landmark, "guide_targets")
            (0 until targets.length()).forEach { targetIndex ->
                val target = objectValue(targets, targetIndex, "guide target")
                exactKeys(target, setOf("source_id", "x_percent", "y_percent"), "guide target")
                val sourceId = text(target, "source_id")
                require(sources[sourceId]?.kind == "printed_guide_photo") {
                    "guide target for $id must cite a printed-guide source"
                }
                evidence += sourceId
                decimal(target, "x_percent", BigDecimal.ZERO, HUNDRED)
                decimal(target, "y_percent", BigDecimal.ZERO, HUNDRED)
            }
            requireReviewed(evidence, "landmark $id")
            val authoredTarget = boardTarget?.let { " Authored AR target: ${it.label}." }.orEmpty()
            val identifiersText = identifierValues.takeIf { it.isNotEmpty() }
                ?.joinToString(prefix = " Identifiers: ", postfix = ".")
                .orEmpty()
            DesignContextRecord(
                recordId = "landmark-$id",
                authority = DesignFactAuthority.SUPPORTING,
                exactKeys = canonicalKeys(displayName, *identifierValues.flatMap(::splitIdentifier).toTypedArray()),
                text = "$manufacturer $model landmark $displayName is a $category.$identifiersText$authoredTarget",
                sourceIds = evidence,
                spokenAliases = buildSet {
                    add(displayName)
                    add(category)
                    addAll(identifierValues)
                    addAll(queryAliases)
                },
                spokenSummary = buildString {
                    append("$displayName is the $category")
                    boardTarget?.let { append(" at ${it.label}") }
                    append(" on $manufacturer $model.")
                },
                boardTarget = boardTarget,
            )
        }
    }

    private fun requireReviewed(evidence: Set<String>, label: String) {
        require(evidence.isNotEmpty()) { "$label has no evidence" }
        evidence.forEach { id ->
            require(sources[id]?.reviewState == "reviewed") { "$label cites missing or unreviewed source $id" }
        }
    }

    private fun evidenceRefs(value: JSONObject, key: String): Set<String> =
        stringArray(value, key).toSet().also { require(it.isNotEmpty()) { "$key must not be empty" } }

    private fun stringArray(value: JSONObject, key: String): List<String> {
        val array = array(value, key)
        val result = (0 until array.length()).map { index ->
            array.opt(index).takeIf { it is String }?.let { canonicalText(it as String, key) }
                ?: throw IllegalArgumentException("$key must contain strings")
        }
        require(result.distinct().size == result.size) { "$key contains duplicates" }
        return result
    }

    private fun canonicalKeys(vararg values: String): MutableSet<String> = values
        .map(String::uppercase)
        .filter(KEY::matches)
        .toCollection(sortedSetOf())

    private fun splitIdentifier(value: String): List<String> = value.split(',', ' ', '/')
        .map(String::trim)
        .filter(String::isNotEmpty)

    private fun exactKeys(value: JSONObject, expected: Set<String>, label: String, optional: Set<String> = emptySet()) {
        val actual = value.keys().asSequence().toSet()
        require(actual.all(expected::contains) && actual.containsAll(expected - optional)) {
            "$label fields do not match the schema"
        }
    }

    private fun objectValue(value: JSONObject, key: String): JSONObject =
        value.optJSONObject(key) ?: throw IllegalArgumentException("$key must be an object")

    private fun objectValue(value: JSONArray, index: Int, label: String): JSONObject =
        value.optJSONObject(index) ?: throw IllegalArgumentException("$label must be an object")

    private fun array(value: JSONObject, key: String): JSONArray =
        value.optJSONArray(key) ?: throw IllegalArgumentException("$key must be an array")

    private fun text(value: JSONObject, key: String): String =
        value.opt(key).takeIf { it is String }?.let { canonicalText(it as String, key) }
            ?: throw IllegalArgumentException("$key must be a string")

    private fun optionalText(value: JSONObject, key: String): String? =
        if (value.has(key)) text(value, key) else null

    private fun canonicalText(value: String, label: String): String = value.also {
        require(it.isNotEmpty() && it == it.trim() && it.length <= MAX_TEXT_CHARS && it.none(Char::isISOControl)) {
            "$label must be bounded canonical text"
        }
    }

    private fun boolean(value: JSONObject, key: String): Boolean =
        value.opt(key).takeIf { it is Boolean } as? Boolean
            ?: throw IllegalArgumentException("$key must be a boolean")

    private fun integer(value: JSONObject, key: String, minimum: Int, maximum: Int): Int {
        val number = value.opt(key) as? Number ?: throw IllegalArgumentException("$key must be an integer")
        val decimal = number.toString().toBigDecimalOrNull() ?: throw IllegalArgumentException("$key is invalid")
        val result = try { decimal.intValueExact() } catch (_: ArithmeticException) {
            throw IllegalArgumentException("$key must be an integer")
        }
        require(result in minimum..maximum) { "$key is outside its range" }
        return result
    }

    private fun decimal(value: JSONObject, key: String, minimum: BigDecimal, maximum: BigDecimal): BigDecimal {
        val number = value.opt(key) as? Number ?: throw IllegalArgumentException("$key must be a number")
        val result = number.toString().toBigDecimalOrNull() ?: throw IllegalArgumentException("$key is invalid")
        require(result >= minimum && result <= maximum) { "$key is outside its range" }
        return result
    }

    private fun format(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()

    private data class Source(val id: String, val kind: String, val reviewState: String)
    private data class Binding(val status: String, val reason: String)

    private companion object {
        const val MAX_SOURCES = 128
        const val MAX_CONNECTORS = 128
        const val MAX_PINS = 512
        const val MAX_LANDMARKS = 1_024
        const val MAX_RECORDS = 1_200
        const val MAX_TEXT_CHARS = 4_096
        val HUNDRED = BigDecimal("100")
        val SHA256 = Regex("sha256:[0-9a-f]{64}")
        val KEY = Regex("[A-Z][A-Z0-9_.-]{0,63}")
        val SOURCE_KINDS = setOf("printed_guide_photo", "datasheet", "authored_design")
        val LICENSE_STATES = setOf("approved", "pending", "rejected")
        val REVIEW_STATES = setOf("reviewed", "pending", "rejected")
        val ELECTRICAL_ROLES = setOf(
            "ground", "power_rail", "power_input", "reset", "analog", "gpio", "bus",
            "clock", "not_connected", "other",
        )
        val LANDMARK_CATEGORIES = setOf(
            "port", "header", "switch", "led", "processor", "debugger", "sensor",
            "transceiver", "memory",
        )
    }
}

internal fun canonicalBoardReferencePayload(raw: String): String {
    val parsed = Json.parseToJsonElement(raw).jsonObject
    val integrity = parsed["integrity"]?.jsonObject
        ?: throw IllegalArgumentException("integrity must be an object")
    val unsigned = JsonObject(parsed + ("integrity" to JsonObject(integrity - "manifest_digest")))
    return canonicalJson(unsigned)
}

private fun canonicalJson(value: JsonElement): String = when (value) {
    is JsonObject -> value.entries.sortedBy(Map.Entry<String, JsonElement>::key)
        .joinToString(separator = ",", prefix = "{", postfix = "}") { (key, child) ->
            "${JsonPrimitive(key)}:${canonicalJson(child)}"
        }
    is JsonArray -> value.joinToString(separator = ",", prefix = "[", postfix = "]", transform = ::canonicalJson)
    is JsonPrimitive -> value.toString()
    JsonNull -> "null"
}
