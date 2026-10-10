package dev.tokito.physical.dialogue

import org.junit.Assert.*
import org.junit.Test

class SegmentMaskDecoderTest {
    private fun predictions() = arrayOf(
        floatArrayOf(2f,2f), floatArrayOf(2f,2f), floatArrayOf(2f,2f), floatArrayOf(2f,2f),
        floatArrayOf(.9f,.8f), floatArrayOf(1f,1f))
    @Test fun reconstructsAndCropsMaskAndSuppressesDuplicateBox() {
        val result = SegmentMaskDecoder.decode(predictions(), arrayOf(Array(4){FloatArray(4){1f}}),1,4,.25f)
        assertEquals(1,result.count)
        assertEquals(4,result.colors.count { it != 0 })
        assertEquals(0,result.colors[0])
        assertTrue(result.colors[5] != 0)
        assertEquals(1,result.boxes.size)
        assertEquals(.9f,result.boxes.single().score,0f)
        assertEquals(1f,result.boxes.single().left,0f)
        assertEquals(3f,result.boxes.single().right,0f)
    }
    @Test fun negativePrototypeDoesNotInventMask() {
        assertEquals(0,SegmentMaskDecoder.decode(predictions(),arrayOf(Array(4){FloatArray(4){-1f}}),1,4,.25f).count)
    }
    @Test fun nonFinitePredictionRejected() {
        val pred = predictions(); pred[0].fill(Float.NaN)
        assertEquals(0,SegmentMaskDecoder.decode(pred,arrayOf(Array(4){FloatArray(4){1f}}),1,4,.25f).count)
    }
    @Test fun lowConfidenceDoesNotInventMask() {
        val pred = predictions(); pred[4].fill(.1f)
        assertEquals(0,SegmentMaskDecoder.decode(pred,arrayOf(Array(4){FloatArray(4){1f}}),1,4,.25f).count)
    }
}
