package dev.tokito.physical.dialogue

/** Preserve the current question and verified evidence. Only the oldest quoted
 * dialogue is evicted when exact generator admission reports overflow.
 * Every candidate is fully re-formatted and re-tokenized by the caller.
 */
internal inline fun <T> admitConversationWindow(request: DialogueRequest, admit: (DialogueRequest) -> T): T {
    var candidate = request
    while (true) {
        try { return admit(candidate) }
        catch (failure: DialogueFailure) {
            if (failure.code != DialogueFailureCode.INVALID_REQUEST || candidate.history.isEmpty()) throw failure
            candidate = candidate.copy(history = candidate.history.drop(1))
        }
    }
}
