package dev.tokito.physical

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComponentTensorContractTest {
    @Test fun acceptsOnlyFiniteProbabilities() {
        listOf(0f, 0.5f, 1f).forEach { assertTrue(ComponentTensorContract.isProbability(it)) }
        listOf(-1f, 1.01f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)
            .forEach { assertFalse(ComponentTensorContract.isProbability(it)) }
    }

    @Test fun acceptsTypicalYoloOutput() { ComponentTensorContract.requireBoundedOutput(8400, 5) }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsIntegerOverflowSizedOutput() { ComponentTensorContract.requireBoundedOutput(Int.MAX_VALUE, 64) }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyOutput() { ComponentTensorContract.requireBoundedOutput(0, 3) }
}
