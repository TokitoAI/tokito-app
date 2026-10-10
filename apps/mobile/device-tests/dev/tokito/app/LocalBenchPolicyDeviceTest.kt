package dev.tokito.app

import dev.tokito.physical.dialogue.*
import org.junit.Assert.*
import org.junit.Test

/** Production safety coverage, deliberately separate from raw model quality evaluation. */
class LocalBenchPolicyDeviceTest {
    @Test fun productionElectronicsAdviceRequiresAdmittedEvidence() {
        val questions = listOf(
            "In general, how does an inductor store energy?",
            "In general, what does a diode do?",
            "In general, why use a pull-up resistor on a digital input?",
            "In general, why put a decoupling capacitor near an IC power pin?",
            "In general, how is a short circuit different from an open circuit?",
            "In general, how does a battery store energy differently from a capacitor?",
            "In general, why does an LED need current limiting?",
            "In general, what happens to total resistance when two resistors are connected in series?",
            "In general, what does circuit ground mean?",
            "In general, how does capacitor impedance change as AC frequency increases?",
            "In general, why should power be disconnected before measuring resistance with a multimeter?",
            "In general, what controls conduction in a MOSFET?",
        )
        val fact = "J1 reviewed identifier datum."
        val index = LocalDesignContextIndex("reviewed-revision", listOf(DesignContextRecord(
            recordId = "reviewed-record", authority = DesignFactAuthority.DATASHEET,
            exactKeys = setOf("J1"), text = fact, sourceIds = setOf("reviewed-source"),
            evidenceLocators = listOf(DesignEvidenceLocator("reviewed-source", 1, "Reviewed identifier")),
        )))
        val policy = LocalBenchDialoguePolicy()
        questions.forEach { question ->
            listOf(null, index).forEach { context ->
                val answer = policy.answer(question, context)
                assertEquals(LocalBenchDialoguePolicy.Authority.ABSTENTION, answer.authority)
                assertNull(answer.boardTarget)
                assertTrue(answer.text.isNotBlank())
            }
        }
        val grounded = policy.answer("In general, explain J1", index)
        assertEquals(LocalBenchDialoguePolicy.Authority.ADMITTED_EVIDENCE, grounded.authority)
        assertEquals(fact, grounded.text)
    }
}
