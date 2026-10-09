package dev.tokito.physical.symbols

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Confined to the host's lifecycle dispatcher; cancellation stays busy through cleanup. */
class BoardSymbolOperation(
    private val scope: CoroutineScope,
    private val onChanged: () -> Unit,
) {
    private var operation: Job? = null
    val busy: Boolean get() = operation != null

    fun start(block: suspend CoroutineScope.() -> Unit): Boolean {
        if (busy) return false
        val job = scope.launch(start = CoroutineStart.LAZY, block = block)
        operation = job
        job.invokeOnCompletion {
            if (operation === job) {
                operation = null
                onChanged()
            }
        }
        onChanged()
        job.start()
        return true
    }

    fun cancel() { operation?.cancel() }
}
