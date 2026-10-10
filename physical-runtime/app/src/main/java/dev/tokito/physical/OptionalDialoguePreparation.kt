package dev.tokito.physical

import kotlinx.coroutines.CancellationException

/** Missing optional generation may leave verified board answers available;
 * lifecycle cancellation must never be mistaken for completed preparation.
 */
internal suspend fun prepareOptionalDialogue(
    prepare: suspend () -> Unit,
    unavailable: (Throwable) -> Unit,
) {
    try {
        prepare()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        unavailable(failure)
    }
}
