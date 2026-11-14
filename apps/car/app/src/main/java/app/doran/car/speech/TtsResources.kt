package app.doran.car.speech

import kotlinx.coroutines.CompletableDeferred

/** Owns one TTS request; callers serialize lifecycle changes on the main thread. */
internal class TtsResources(private val detachListener: () -> Unit, private val stop: () -> Unit, private val release: () -> Unit) {
    private enum class Phase { ACTIVE, STOPPING, CLOSED }
    private var phase = Phase.ACTIVE
    private var speakDispatched = false
    private val released = CompletableDeferred<Unit>()

    val isStopping: Boolean get() = phase == Phase.STOPPING
    val isSettledSuccessfully: Boolean get() = released.isCompleted && !released.isCancelled

    fun markSpeakDispatched() {
        check(phase == Phase.ACTIVE)
        speakDispatched = true
    }

    fun close(completed: Boolean = false) {
        if (phase != Phase.ACTIVE) return
        if (completed || !speakDispatched) {
            settle()
            return
        }
        // SDK 3.3.1 stop is asynchronous and package-scoped. Keep this listener and
        // binding until its acknowledgement; a successor must not register meanwhile.
        phase = Phase.STOPPING
        runCatching { stop() }.onFailure {
            settle(SpeechFailure("playback_stop_failed", "음성 종료를 확인하지 못했어요. 앱을 다시 열어 주세요"))
        }
    }

    fun acknowledgeStop(): Boolean {
        if (phase != Phase.STOPPING) return false
        settle()
        return true
    }

    fun stopTimedOut() {
        if (phase != Phase.STOPPING) return
        // Release the abandoned binding, but never turn a missing stop acknowledgement
        // into permission to start another SDK request on this PleosSpeech instance.
        settle(SpeechFailure("playback_stop_timeout", "음성 종료를 확인하지 못했어요. 앱을 다시 열어 주세요"))
    }

    suspend fun awaitReleased() = released.await()

    private fun settle(cause: SpeechFailure? = null) {
        if (phase == Phase.CLOSED) return
        phase = Phase.CLOSED
        val detached = runCatching { detachListener() }.isSuccess
        val releasedSdk = runCatching { release() }.isSuccess
        val failure = cause ?: if (!detached || !releasedSdk) {
            SpeechFailure("playback_cleanup_failed", "음성 자원을 정리하지 못했어요. 앱을 다시 열어 주세요")
        } else {
            null
        }
        if (failure == null) released.complete(Unit) else released.completeExceptionally(failure)
    }
}
