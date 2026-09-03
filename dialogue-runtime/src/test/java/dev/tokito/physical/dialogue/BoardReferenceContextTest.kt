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
        val context = checkedInFrdmContext()

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
        val grounded = context.index.groundedAnswer("Where is the main chip?")!!
        assertEquals(1, JSONObject(grounded.designContext).getJSONArray("records").length())
        val target = grounded.boardTarget
        assertEquals("U1", target!!.label)
        assertEquals(0.539737329, target.xNormalized, 0.000000001)
        assertEquals(0.5, target.yNormalized, 0.000000001)
        assertNull(context.index.retrieve("Where is R3?"))
        assertNull(context.index.answer("Focus on your goal"))
    }

    @Test
    fun retrievesOfficialKnowledgeAcrossBoardDomains() {
        val index = checkedInFrdmContext().index
        val cases = listOf(
            "Does this have a timer?" to "CTIMER0 through CTIMER4",
            "Does this have a cunter?" to "CTIMER0 through CTIMER4",
            "What CPU and memory does the main chip have?" to "150-megahertz",
            "How many GPIOs does the main chip support?" to "106 GPIOs",
            "Which communication interfaces does it have?" to "eight low-power Flexcomm",
            "What types of timers are available?" to "real-time-clock",
            "Does the main chip support secure boot?" to "EdgeLock Secure Enclave",
            "What can the PWM timer do?" to "capture",
            "How much external flash is installed?" to "8-megabyte",
            "What crystal frequencies are on the board?" to "24 MHz",
            "Can the USB port act as a host?" to "J11",
            "How many I/O ports does this have?" to "J10",
            "Does it support CAN FD?" to "CAN1",
            "What accelerometer is fitted?" to "FXLS8974CFR3",
            "Does it have an ambient light sensor?" to "Q2",
            "What microphone is populated?" to "U7",
            "Which expansion headers are available?" to "J1 through J4",
            "Can I connect an OV7670 camera?" to "J9",
            "What does the MCU-Link debug probe support?" to "SWD",
            "What do the push buttons do?" to "SW1",
            "Which pins drive the RGB LED?" to "P4_18",
            "How can I power the board?" to "J11",
            "What is the operating temperature range?" to "minus 40",
            "Are there any EdgeLock 2GO board errata?" to "Revision C",
            "Where is pin32?" to "P0_19",
        )

        cases.forEach { (query, expected) ->
            val answer = index.answer(query)
            assertNotNull("No answer for: $query", answer)
            assert(answer!!.contains(expected, ignoreCase = true)) {
                "Expected '$expected' for '$query', got '$answer'"
            }
        }
        val flashContext = JSONObject(index.retrieve("How much external flash is installed?")!!)
            .getJSONArray("records")
        val flashRecordIds = (0 until flashContext.length())
            .map { flashContext.getJSONObject(it).getString("record_id") }
        assertEquals("knowledge-external-flash", flashRecordIds.first())
        assertFalse(flashRecordIds.contains("knowledge-target-mcu-package-and-memory"))
        assertNull(index.answer("Does this board have Bluetooth?"))
        assertNull(index.answer("Where is the Ethernet jack?"))
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
        assertEquals("The board uses 3.3 volt logic.", context.index.answer("What is the logic voltage?"))
        val knowledge = JSONObject(context.index.retrieve("What is the logic voltage?")!!)
            .getJSONArray("records").getJSONObject(0)
        assertEquals("datasheet-1", knowledge.getJSONArray("evidence_locators").getJSONObject(0).getString("source_id"))
        assertEquals(1, knowledge.getJSONArray("evidence_locators").getJSONObject(0).getInt("page"))
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

    @Test
    fun rejectsNontechnicalKnowledgeEvidence() {
        val changed = JSONObject(frozenManifest())
        changed.getJSONArray("sources").getJSONObject(1).put("kind", "authored_design")
        seal(changed)
        assertThrows(IllegalArgumentException::class.java) {
            BoardReferenceContext.parse(changed.toString())
        }
    }

    private fun checkedInFrdmContext(): BoardReferenceContext {
        val relative = "reference/boards/frdm-mcxn236.board-reference.json"
        val manifest = generateSequence<File>(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
            .map { File(it, relative) }
            .first { it.isFile }
        return BoardReferenceContext.parse(manifest.readText())
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
        val datasheet = JSONObject(source.toString())
            .put("source_id", "datasheet-1")
            .put("kind", "datasheet")
            .put("locator", "official reference-board datasheet")
            .put("license_status", "approved")
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
            .put("sources", JSONArray().put(source).put(datasheet))
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
            .put(
                "knowledge_graph",
                JSONObject()
                    .put(
                        "entities",
                        JSONArray().put(
                            JSONObject()
                                .put("entity_id", "reference-board")
                                .put("display_name", "Reference Board")
                                .put("entity_type", "board")
                                .put("aliases", JSONArray().put("test board"))
                                .put("identifiers", JSONArray()),
                        ),
                    )
                    .put(
                        "facts",
                        JSONArray().put(
                            JSONObject()
                                .put("fact_id", "logic-voltage")
                                .put("subject_id", "reference-board")
                                .put("predicate", "uses_voltage")
                                .put("value", "The board uses a 3.3 V logic rail.")
                                .put("spoken_summary", "The board uses 3.3 volt logic.")
                                .put("query_aliases", JSONArray().put("logic voltage"))
                                .put("related_entity_ids", JSONArray())
                                .put(
                                    "evidence",
                                    JSONArray().put(
                                        JSONObject()
                                            .put("source_id", "datasheet-1")
                                            .put("page", 1)
                                            .put("locator", "power table"),
                                    ),
                                ),
                        ),
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
