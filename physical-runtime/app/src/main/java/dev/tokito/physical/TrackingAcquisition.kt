package dev.tokito.physical

/** Requires consecutive fresh source matches before loading a different model. */
internal class TrackingAcquisition {
    private var identity: String? = null
    private var stamp = Long.MIN_VALUE
    private var count = 0
    fun reset() { identity = null; stamp = Long.MIN_VALUE; count = 0 }
    fun accept(candidate: String, capturedAtNanos: Long): Boolean {
        if (capturedAtNanos <= stamp) return false
        if (candidate != identity || (stamp != Long.MIN_VALUE && capturedAtNanos - stamp > 900_000_000L)) count = 0
        identity = candidate; stamp = capturedAtNanos; count++
        return count >= 3
    }
}
