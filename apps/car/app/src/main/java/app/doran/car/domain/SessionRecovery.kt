package app.doran.car.domain

/** Clear these together after acknowledged completion or account replacement. */
val SESSION_FINISH_KEYS: Array<String>
    get() = arrayOf("active_session", "finish_wanted", "finish_request", "start_request")

data class SessionRecovery(val sessionId: String?, val finishWanted: Boolean, val clearSessionKeys: Boolean)

/** Repairs metadata left by older non-atomic completion cleanup; never removes pending utterances. */
fun recoverSession(sessionId: String?, finishWanted: Boolean, finishRequest: String?): SessionRecovery {
    if (!sessionId.isNullOrBlank()) return SessionRecovery(sessionId, finishWanted, false)
    val orphaned = sessionId != null || finishWanted || finishRequest != null
    return SessionRecovery(null, false, orphaned)
}
