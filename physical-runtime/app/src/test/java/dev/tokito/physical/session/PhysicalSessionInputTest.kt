package dev.tokito.physical.session

import org.junit.Assert.*
import org.junit.Test

class PhysicalSessionInputTest {
    @Test fun absentAndNullableContextRemainProjectless() {
        listOf(null, emptyMap(), mapOf("projectId" to null, "designId" to null, "contextRevision" to null)).forEach {
            assertEquals(PhysicalSessionContext(), PhysicalSessionInput.decode(it))
        }
    }

    @Test fun opaqueIdentityAndOptionalFieldsArePreserved() {
        val project = "opaque/project-α"
        val design = "design:radio"
        val revision = "sha256:revision"
        assertEquals(PhysicalSessionContext(project), PhysicalSessionInput.decode(mapOf("projectId" to project)))
        assertEquals(PhysicalSessionContext(project, design, revision), PhysicalSessionInput.decode(
            mapOf("projectId" to project, "designId" to design, "contextRevision" to revision),
        ))
    }

    @Test fun malformedInputsFailBeforeSessionCreation() {
        val inputs = listOf(
            mapOf("unknown" to "value"), mapOf("projectId" to 123), mapOf("projectId" to true),
            mapOf("projectId" to emptyMap<String, Any?>()), mapOf("projectId" to listOf("id")),
            mapOf("projectId" to " "), mapOf("projectId" to "id\u0000"),
            mapOf("projectId" to "é".repeat(PhysicalSessionInput.MAX_VALUE_BYTES / 2 + 1)),
            mapOf("designId" to "design"), mapOf("contextRevision" to "revision"),
        )
        inputs.forEach { input ->
            assertTrue(input.toString(), runCatching { PhysicalSessionInput.decode(input) }.exceptionOrNull() is IllegalArgumentException)
        }
    }
}
