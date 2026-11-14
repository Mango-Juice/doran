package app.doran.car.qa

import android.os.Looper
import app.doran.car.speech.SpeechEngine
import app.doran.car.speech.SpeechFailure
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine

/** In-memory input only. This class never constructs an STT/TTS SDK or opens an audio device. */
class SyntheticSpeech(private val onInterrupted: () -> Unit) : SpeechEngine {
    private data class Listening(val ready: () -> Unit, val partial: (String) -> Unit, val continuation: CancellableContinuation<String>)
    val events = CopyOnWriteArrayList<String>()
    var listenCount = 0
        private set
    var speakCount = 0
        private set
    var autoCompleteSpeech = false
    var failNextSpeech = false
    private var listening: Listening? = null
    private var retiredListening: Listening? = null
    private var speaking: CancellableContinuation<Unit>? = null

    override suspend fun listen(onReady: () -> Unit, onPartial: (String) -> Unit): String = suspendCancellableCoroutine { continuation ->
        checkMain()
        listenCount += 1
        val request = Listening(onReady, onPartial, continuation)
        listening = request
        events.add("listen_ready")
        continuation.invokeOnCancellation {
            retiredListening = request
            if (listening === request) listening = null
            events.add("listen_cancelled")
        }
        onReady()
    }

    fun submit(text: String) {
        checkMain()
        val request = checkNotNull(listening) { "No synthetic listening request" }
        check(request.continuation.isActive)
        request.partial(text)
        listening = null
        retiredListening = request
        events.add("synthetic_final")
        request.continuation.resume(text)
    }

    fun updatePartial(text: String) {
        checkMain()
        val request = checkNotNull(listening)
        check(request.continuation.isActive)
        request.partial(text)
        events.add("synthetic_partial")
    }

    /** Deliberately bypasses SDK guards to exercise the ViewModel's generation boundary. */
    fun emitRetiredCallbacks() {
        checkMain()
        val old = checkNotNull(retiredListening)
        events.add("retired_ready_and_partial_attempted")
        old.ready()
        old.partial("합성 시험: 이미 끝난 요청의 늦은 부분 결과")
    }

    override suspend fun speak(text: String) {
        checkMain()
        speakCount += 1
        events.add("synthetic_speak_requested")
        if (failNextSpeech) {
            failNextSpeech = false
            throw SpeechFailure("synthetic_playback_failure", "합성 재생 실패")
        }
        if (autoCompleteSpeech) {
            events.add("synthetic_speak_completed")
            return
        }
        suspendCancellableCoroutine<Unit> { continuation ->
            speaking = continuation
            continuation.invokeOnCancellation {
                if (speaking === continuation) speaking = null
                events.add("synthetic_speak_cancelled")
            }
        }
    }

    fun completeSpeech() {
        checkMain()
        val request = checkNotNull(speaking)
        speaking = null
        events.add("synthetic_speak_completed")
        if (request.isActive) request.resume(Unit)
    }

    fun interruptFocus() {
        checkMain()
        events.add("synthetic_focus_interruption")
        onInterrupted()
    }

    override fun pause() {
        checkMain()
        events.add("pause")
        listening?.continuation?.cancel()
        speaking?.cancel()
    }

    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper())
}
