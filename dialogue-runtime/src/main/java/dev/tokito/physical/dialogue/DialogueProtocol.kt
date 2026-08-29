package dev.tokito.physical.dialogue

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal class DialogueProtocol(private val limits: DialogueLimits) {
    fun systemPrompt(): String = """
        You are Tokito's fully local physical-work assistant. Never invent measurements,
        observations, tool names, or tool results. Return exactly one JSON object and no
        markdown. Use /no_think. Allowed shapes:
        {"kind":"response","text":"..."}
        {"kind":"clarification","question":"..."}
        {"kind":"tool_proposal","id":"...","tool":"...","version":1,
         "arguments":{},"deadline_ms":4000,"rationale":"..."}
        A proposal is not execution. Only propose a capability supplied in the request.
        If evidence or required arguments are missing, ask a clarification.
    """.trimIndent()

    fun requestPrompt(request: DialogueRequest): String {
        validateRequest(request)
        val root = JSONObject()
            .put("utterance", request.utterance)
            .put("history", JSONArray(request.history.map {
                JSONObject().put("role", it.role.name.lowercase()).put("text", it.text)
            }))
            .put("observations", JSONArray(request.observations.map {
                JSONObject()
                    .put("kind", it.kind)
                    .put("summary", it.summary)
                    .put("confidence", it.confidence)
                    .put("observed_at_elapsed_ms", it.observedAtElapsedMs)
            }))
            .put("capabilities", JSONArray(request.capabilities.map { capabilityJson(it) }))
        request.designContext?.let { root.put("design_context", it) }
        return root.toString().also {
            if (it.length > limits.maxPromptChars) fail("Serialized request exceeds prompt limit")
        }
    }

    fun parse(raw: String, request: DialogueRequest): DialogueOutcome {
        if (raw.length > limits.maxOutputChars) {
            throw DialogueFailure(DialogueFailureCode.OUTPUT_LIMIT, "Model output exceeds limit")
        }
        val value = try {
            JSONObject(extractSingleObject(raw))
        } catch (e: JSONException) {
            throw DialogueFailure(DialogueFailureCode.INVALID_MODEL_OUTPUT, "Model returned invalid JSON", e)
        }
        return when (requiredString(value, "kind")) {
            "response" -> {
                exactKeys(value, setOf("kind", "text"))
                DialogueOutcome.Response(boundedText(value, "text"))
            }
            "clarification" -> {
                exactKeys(value, setOf("kind", "question"))
                DialogueOutcome.Clarification(boundedText(value, "question"))
            }
            "tool_proposal" -> parseTool(value, request)
            else -> invalid("Unknown output kind")
        }
    }

    private fun parseTool(value: JSONObject, request: DialogueRequest): DialogueOutcome.ToolProposal {
        exactKeys(value, setOf("kind", "id", "tool", "version", "arguments", "deadline_ms", "rationale"))
        val id = requiredString(value, "id")
        if (!id.matches(Regex("[A-Za-z0-9_-]{8,64}"))) invalid("Invalid proposal id")
        val name = requiredString(value, "tool")
        val version = value.optInt("version", -1)
        val capability = request.capabilities.singleOrNull { it.name == name && it.version == version }
            ?: policy("Tool is not declared by the host")
        val deadline = value.optLong("deadline_ms", -1)
        if (deadline !in 1..minOf(request.deadlineMs, capability.maxDeadlineMs)) {
            policy("Tool deadline is outside the authorized bound")
        }
        val input = value.optJSONObject("arguments") ?: invalid("arguments must be an object")
        val allowed = capability.arguments.associateBy { it.name }
        val keys = input.keys().asSequence().toSet()
        if (!allowed.keys.containsAll(keys)) policy("Tool contains undeclared arguments")
        capability.arguments.filter { it.required }.forEach {
            if (!input.has(it.name) || input.isNull(it.name)) policy("Missing required argument: ${it.name}")
        }
        val normalized = keys.associateWith { key -> validateArgument(input, allowed.getValue(key)) }
        return DialogueOutcome.ToolProposal(
            id = id,
            tool = name,
            version = version,
            arguments = normalized,
            deadlineMs = deadline,
            rationale = boundedText(value, "rationale"),
        )
    }

    private fun validateArgument(input: JSONObject, spec: ToolArgument): Any = when (spec.type) {
        ArgumentType.STRING -> requiredString(input, spec.name).also {
            if (it.length > spec.maxLength) policy("Argument ${spec.name} exceeds its limit")
        }
        ArgumentType.NUMBER -> input.opt(spec.name).takeIf { it is Number }
            ?: policy("Argument ${spec.name} must be a number")
        ArgumentType.BOOLEAN -> input.opt(spec.name).takeIf { it is Boolean }
            ?: policy("Argument ${spec.name} must be a boolean")
    }

    private fun validateRequest(request: DialogueRequest) {
        if (request.utterance.isBlank() || request.utterance.length > limits.maxTurnChars) fail("Invalid utterance")
        if (request.history.size > limits.maxHistoryTurns || request.history.any { it.text.length > limits.maxTurnChars }) fail("History exceeds bounds")
        if (request.observations.size > limits.maxObservationCount || request.observations.any {
                it.summary.length > limits.maxObservationChars || it.confidence !in 0.0..1.0
            }) fail("Observations exceed bounds")
        if ((request.designContext?.length ?: 0) > limits.maxDesignContextChars) fail("Design context exceeds bounds")
        if (request.capabilities.size > limits.maxCapabilities || request.capabilities.map { it.name to it.version }.toSet().size != request.capabilities.size) fail("Capabilities are invalid")
        if (request.deadlineMs !in 1..limits.maxDeadlineMs) fail("Deadline is invalid")
    }

    private fun capabilityJson(capability: Capability) = JSONObject()
        .put("name", capability.name)
        .put("version", capability.version)
        .put("description", capability.description)
        .put("max_deadline_ms", capability.maxDeadlineMs)
        .put("arguments", JSONArray(capability.arguments.map {
            JSONObject().put("name", it.name).put("type", it.type.name.lowercase()).put("required", it.required)
        }))

    private fun extractSingleObject(raw: String): String {
        val text = raw.trim()
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end < start || text.substring(0, start).isNotBlank() || text.substring(end + 1).isNotBlank()) invalid("Expected one JSON object")
        return text.substring(start, end + 1)
    }

    private fun exactKeys(value: JSONObject, expected: Set<String>) {
        if (value.keys().asSequence().toSet() != expected) invalid("Output fields do not match the schema")
    }

    private fun requiredString(value: JSONObject, key: String): String = value.opt(key).takeIf { it is String }
        ?.let { it as String }?.takeIf { it.isNotBlank() } ?: invalid("$key must be a non-empty string")

    private fun boundedText(value: JSONObject, key: String) = requiredString(value, key).also {
        if (it.length > limits.maxTurnChars) invalid("$key exceeds its limit")
    }

    private fun fail(message: String): Nothing = throw DialogueFailure(DialogueFailureCode.INVALID_REQUEST, message)
    private fun invalid(message: String): Nothing = throw DialogueFailure(DialogueFailureCode.INVALID_MODEL_OUTPUT, message)
    private fun policy(message: String): Nothing = throw DialogueFailure(DialogueFailureCode.POLICY_REJECTED, message)
}
