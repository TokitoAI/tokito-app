package dev.tokito.physical.dialogue

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class BoardReferenceContextTest {
    @Test
    fun importsCheckedInFrdmReference() {
        val relative = "reference/boards/frdm-mcxn236.board-reference.json"
        val manifest = generateSequence<File>(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
            .map { File(it, relative) }
            .first { it.isFile }

        val context = BoardReferenceContext.parse(manifest.readText())

        assertEquals("NXP", context.identity.manufacturer)
        assertEquals("FRDM-MCXN236", context.identity.model)
        assertFalse(context.identity.placementBound)
        assertNotNull(context.index.retrieve("What is J9 pin 32?"))
        assertNotNull(context.index.retrieve("Where is the external flash?"))
        val processor = context.index.retrieve("Where is the main chip?")
        assertNotNull(processor)
        assert(processor!!.contains("MCXN236VDFT"))
        assert(context.index.answer("Where is the main chip?")!!.contains("MCXN236VDFT"))
        assert(context.index.answer("Where is the main trick?")!!.contains("MCXN236VDFT"))
        val target = context.index.groundedAnswer("Where is the main chip?")!!.boardTarget
        assertEquals("U1", target!!.label)
        assertEquals(0.539737329, target.xNormalized, 0.000000001)
        assertEquals(0.5, target.yNormalized, 0.000000001)
        assertNull(context.index.retrieve("Where is R3?"))
        assertNull(context.index.answer("Focus on your goal"))
    }

    @Test
    fun importsReviewedPinAndLandmarkFactsWithoutClaimingPlacement() {
        val context = BoardReferenceContext.parse(frozenManifest())

        assertEquals("Vendor", context.identity.manufacturer)
        assertEquals("Reference Board", context.identity.model)
        assertFalse(context.identity.placementBound)
        val connector = context.index.retrieve("What signal is J1 pin 2?")
        assertNotNull(connector)
        assert(connector!!.contains("2=GND"))
        assert(connector.contains("No authored board-plane placement"))
        assertEquals(
            "J1 pin 2 is GND. Electrical role: ground. Functions: POWER.",
            context.index.answer("What signal is J1 pin 2?"),
        )
        val groundedPin = context.index.groundedAnswer("What signal is J1 pin 2?")!!
        assertEquals(
            "J1 pin 2 is GND. Electrical role: ground. Functions: POWER.",
            org.json.JSONObject(groundedPin.designContext)
                .getJSONArray("records")
                .getJSONObject(0)
                .getString("text"),
        )
        val landmark = context.index.retrieve("Where is J1?")
        assertNotNull(landmark)
        assert(!landmark!!.contains("Printed-guide target"))
        assertNull(context.index.retrieve("Where is R3?"))
    }

    @Test
    fun rejectsDigestDrift() {
        val changed = JSONObject(frozenManifest())
        changed.getJSONObject("board").put("model", "Changed")
        assertThrows(IllegalArgumentException::class.java) {
            BoardReferenceContext.parse(changed.toString())
        }
    }

    @Test
    fun rejectsUnreviewedEvidence() {
        val changed = JSONObject(frozenManifest())
        changed.getJSONArray("sources").getJSONObject(0).getJSONObject("review").put("state", "pending")
        seal(changed)
        assertThrows(IllegalArgumentException::class.java) {
            BoardReferenceContext.parse(changed.toString())
        }
    }

    private fun frozenManifest(): String {
        val source = JSONObject()
            .put("source_id", "guide-1")
            .put("kind", "printed_guide_photo")
            .put("media_sha256", "sha256:" + "1".repeat(64))
            .put("locator", "locally reviewed guide")
            .put("license_status", "pending")
            .put("redistributable", false)
            .put(
                "review",
                JSONObject()
                    .put("state", "reviewed")
                    .put("reviewer", "reviewer@example.invalid")
                    .put("reviewed_at", "2026-08-30T00:00:00Z"),
            )
        val pin1 = pin(1, "P3V3", "power_rail")
        val pin2 = pin(2, "GND", "ground")
        val root = JSONObject()
            .put("schema_version", "1.0")
            .put("manifest_id", "reference-board-v1")
            .put("board", JSONObject().put("manufacturer", "Vendor").put("model", "Reference Board"))
            .put(
                "placement_binding",
                JSONObject().put("status", "unbound").put("reason", "guide has no authored coordinates"),
            )
            .put("sources", JSONArray().put(source))
            .put(
                "landmarks",
                JSONArray().put(
                    JSONObject()
                        .put("landmark_id", "power-header")
                        .put("display_name", "Power header")
                        .put("category", "header")
                        .put("identifiers", JSONObject().put("reference_designator", "J1"))
                        .put(
                            "guide_targets",
                            JSONArray().put(
                                JSONObject()
                                    .put("source_id", "guide-1")
                                    .put("x_percent", 25)
                                    .put("y_percent", 75),
                            ),
                        )
                        .put("evidence_refs", JSONArray().put("guide-1")),
                ),
            )
            .put(
                "connectors",
                JSONArray().put(
                    JSONObject()
                        .put("connector_id", "J1")
                        .put("display_name", "Power header J1")
                        .put("pin_count", 2)
                        .put("pins", JSONArray().put(pin1).put(pin2))
                        .put("evidence_refs", JSONArray().put("guide-1")),
                ),
            )
            .put("integrity", JSONObject().put("manifest_digest", "sha256:" + "0".repeat(64)))
        seal(root)
        return root.toString()
    }

    private fun pin(number: Int, signal: String, role: String): JSONObject = JSONObject()
        .put("number", number)
        .put("signal_name", signal)
        .put("aliases", JSONArray())
        .put("functions", JSONArray().put("POWER"))
        .put("electrical_role", role)
        .put("evidence_refs", JSONArray().put("guide-1"))

    private fun seal(root: JSONObject) {
        val integrity = root.getJSONObject("integrity")
        integrity.remove("manifest_digest")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical(root).toByteArray(Charsets.UTF_8))
            .joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
        integrity.put("manifest_digest", "sha256:$digest")
    }

    private fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().sorted().joinToString(separator = ",", prefix = "{", postfix = "}") { key ->
            "${JSONObject.quote(key)}:${canonical(value.get(key))}"
        }
        is JSONArray -> (0 until value.length()).joinToString(separator = ",", prefix = "[", postfix = "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        is Boolean -> value.toString()
        is Number -> JSONObject.numberToString(value)
        else -> error("unsupported value")
    }
}
