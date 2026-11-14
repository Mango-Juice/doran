package app.doran.car.speech

/** Owns one TTS request; callers serialize lifecycle changes on the main thread. */
internal class TtsResources(private val detachListener: () -> Unit, private val stop: () -> Unit, private val release: () -> Unit) {
    private var closed = false

    fun close(completed: Boolean = false) {
        if (closed) return
        closed = true
        runCatching { detachListener() }
        // Pleos 3.3.1 schedules stop by package. A completed request must not stop its successor.
        if (!completed) runCatching { stop() }
        runCatching { release() }
    }
}
