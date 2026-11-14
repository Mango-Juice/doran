package app.doran.car.speech

/** The conversation owns speech requests; SDK callbacks stay inside the implementation. */
interface SpeechEngine {
    suspend fun listen(onReady: () -> Unit = {}, onPartial: (String) -> Unit): String
    suspend fun speak(text: String)
    fun pause()
}
