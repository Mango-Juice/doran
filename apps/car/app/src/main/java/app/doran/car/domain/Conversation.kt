package app.doran.car.domain

/** The UI renders this state; callbacks never directly mutate views. */
enum class Phase { CONNECTING, UNPAIRED, READY, STARTING, LISTENING, THINKING, SPEAKING, PAUSED, SAVING, SAVED, ERROR }

data class ConversationState(
    val phase: Phase = Phase.CONNECTING,
    val title: String = "도란을 준비하고 있어요",
    val detail: String = "잠시만 기다려 주세요",
    val transcript: String = "",
    val reply: String = "",
    val code: String = "",
    val pairingId: String? = null,
    val claimant: String? = null,
    val paired: Boolean = false,
    val sessionId: String? = null,
    val pendingCount: Int = 0,
    val uxKnown: Boolean = false,
    val driving: Boolean = true,
    val canSetup: Boolean = false,
    val canShowTranscript: Boolean = false,
    val textLimit: Int? = null,
    val vehicleConsent: Boolean = false,
    val soloAsserted: Boolean = false,
    val passengerDetected: Boolean = false,
    val errorCode: String? = null
)

/** The platform limit also applies to status labels, without leaving a split surrogate pair. */
fun restrictCarText(text: String, maxLength: Int?): String {
    if (maxLength == null || text.length <= maxLength) return text
    if (maxLength <= 0) return ""
    val prefix = text.take(maxLength - 1).dropLastWhile { it.isHighSurrogate() }
    return "$prefix…"
}

/** Every asynchronous speech/network completion belongs to a single generation. */
class GenerationGate {
    private var generation = 0L
    fun advance(): Long = ++generation
    fun current(): Long = generation
    fun accepts(value: Long): Boolean = value == generation
}

object VoiceCommands {
    fun isFinish(text: String): Boolean = text.trim().trimEnd('.', '!', '?').replace(" ", "") in
        setOf("대화끝", "대화끝내줘", "여기까지기록해줘", "그만할게", "대화종료", "도란그만")
}

/** Finalization may outlive a network reconnect; all three scopes must still own the result. */
data class SessionOperation(val ownerId: String?, val sessionId: String, val generation: Long) {
    fun matches(currentOwner: String?, currentSession: String?, currentGeneration: Long): Boolean =
        ownerId == currentOwner && sessionId == currentSession && generation == currentGeneration
}
