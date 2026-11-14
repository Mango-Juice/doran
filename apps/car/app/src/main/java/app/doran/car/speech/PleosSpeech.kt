package app.doran.car.speech

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import app.doran.car.domain.GenerationGate
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class SpeechFailure(val kind: String, message: String) : IOException(message)

/** Generation checks isolate callbacks; cancelled package-scoped TTS stops also require acknowledgement. */
class PleosSpeech(private val context: Context, private val onInterrupted: () -> Unit) : SpeechEngine {
    private val main = Handler(Looper.getMainLooper())
    private val generation = GenerationGate()
    private val audio = context.getSystemService(AudioManager::class.java)
    private var focus: AudioFocusRequest? = null
    private var stt: SpeechToText? = null
    private var sttListener: ResultListener? = null

    // Also inspected by debug-only instrumentation to verify SDK ownership and stale callbacks.
    private var tts: TextToSpeech? = null
    private var ttsListener: ai.pleos.playground.tts.listener.EventListener? = null
    private var ttsResources: TtsResources? = null
    private var pendingTtsStop: TtsResources? = null
    private var cancelPending: (() -> Unit)? = null
    private var focusOwner: Long? = null
    private var sttOwner: Long? = null
    private var ttsOwner: Long? = null
    private var cancellationOwner: Long? = null

    private fun trace(kind: String, id: Long, event: String, startedAt: Long) {
        Log.i("DoranSpeech", "kind=$kind request=$id event=$event elapsed_ms=${SystemClock.elapsedRealtime() - startedAt}")
    }

    private fun acquireFocus(owner: Long) {
        releaseFocus()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change ->
                if (change < 0 && focusOwner == owner) {
                    pause()
                    onInterrupted()
                }
            }, main).build()
        if (audio.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            throw SpeechFailure("audio_busy", "다른 음성 안내가 끝난 뒤 다시 시작해 주세요")
        }
        focus = request
        focusOwner = owner
    }
    private fun releaseFocus(owner: Long? = null) {
        if (owner != null && focusOwner != owner) return
        val previous = focus
        focus = null
        focusOwner = null
        previous?.let { audio.abandonAudioFocusRequest(it) }
    }
    private fun releaseStt(owner: Long? = null) {
        if (owner != null && sttOwner != owner) return
        sttOwner = null
        val old = stt
        val listener = sttListener
        stt = null
        sttListener = null
        runCatching { if (listener != null) old?.removeListener(listener) }
        runCatching { old?.stop() }
        runCatching { old?.release() }
    }
    private fun releaseTts(owner: Long? = null, completed: Boolean = false) {
        if (owner != null && ttsOwner != owner) return
        ttsOwner = null
        val resources = ttsResources
        ttsResources = null
        tts = null
        ttsListener = null
        resources?.close(completed)
        if (resources != null && !resources.isSettledSuccessfully) {
            pendingTtsStop = resources
            if (resources.isStopping) main.postDelayed({ resources.stopTimedOut() }, 3_000)
        }
    }

    private suspend fun awaitTtsStop() {
        val pending = pendingTtsStop ?: return
        pending.awaitReleased()
        if (pendingTtsStop === pending) pendingTtsStop = null
        // A timed-out instance stays failed. Recreating an Activity may retain its ViewModel;
        // the SDK has no request ID to prove that a previous process's queued stop has drained.
    }

    override suspend fun listen(onReady: () -> Unit, onPartial: (String) -> Unit): String = withContext(Dispatchers.Main.immediate) {
        withTimeout(45_000) {
            val id = generation.advance()
            val startedAt = SystemClock.elapsedRealtime()
            trace("stt", id, "listen_requested", startedAt)
            awaitTtsStop()
            if (!generation.accepts(id)) throw CancellationException()
            acquireFocus(id)
            try {
                suspendCancellableCoroutine { continuation ->
                    var readyDelivered = false
                    fun notifyReady() {
                        main.post {
                            if (!generation.accepts(id) || !continuation.isActive || readyDelivered) return@post
                            readyDelivered = true
                            trace("stt", id, "accepted_ready", startedAt)
                            onReady()
                        }
                    }
                    fun fail(code: String) {
                        if (!generation.accepts(id) || !continuation.isActive) return
                        generation.advance()
                        releaseStt()
                        continuation.resumeWithException(SpeechFailure(code, "잘 듣지 못했어요. 다시 시작해 주세요"))
                    }
                    cancellationOwner = id
                    cancelPending = { if (continuation.isActive) continuation.cancel() }
                    continuation.invokeOnCancellation {
                        main.post {
                            if (generation.accepts(id)) {
                                generation.advance()
                                releaseStt()
                            }
                        }
                    }
                    try {
                        val sdk = SpeechToText(context, ai.pleos.playground.stt.constant.Mode.ON_DEVICE)
                        stt = sdk
                        sttOwner = id
                        val listener = object : ResultListener {
                            override fun onReady() {
                                trace("stt", id, "callback_ready", startedAt)
                                notifyReady()
                            }
                            override fun onStartedRecognition() {
                                trace("stt", id, "callback_started", startedAt)
                                notifyReady()
                            }
                            override fun onEndedRecognition() = trace("stt", id, "callback_ended", startedAt)
                            override fun onUpdated(text: String, completed: Boolean) {
                                if (completed) trace("stt", id, "callback_final", startedAt)
                                main.post {
                                    if (!generation.accepts(id) || !continuation.isActive) return@post
                                    onPartial(text)
                                    if (completed) {
                                        trace("stt", id, "accepted_final", startedAt)
                                        generation.advance()
                                        releaseStt()
                                        if (text.isBlank()) {
                                            continuation.resumeWithException(SpeechFailure("empty", "말씀이 들리지 않았어요"))
                                        } else {
                                            continuation.resume(text.trim())
                                        }
                                    }
                                }
                            }
                            override fun onUpdatedEpdData(on: Long, off: Long) = Unit
                            override fun onUpdatedRms(rms: Double) = Unit
                            override fun onError(errorType: ai.pleos.playground.stt.constant.ErrorType) {
                                main.post { fail("recognition_${errorType.name}") }
                            }
                        }
                        sttListener = listener
                        sdk.initialize(object : ai.pleos.playground.stt.listener.OnInitializeListener {
                            private var configured = false
                            override fun onSuccess() {
                                trace("stt", id, "sdk_initialized", startedAt)
                                main.post {
                                    if (!generation.accepts(id) || !continuation.isActive) return@post
                                    if (configured) return@post
                                    configured = true
                                    runCatching {
                                        sdk.setLocale(Locale.KOREA)
                                        sdk.addListener(listener)
                                        sdk.request()
                                        trace("stt", id, "microphone_request_dispatched", startedAt)
                                    }
                                        .onFailure { fail("recognition_unavailable") }
                                }
                            }
                            override fun onFailure(errorType: ai.pleos.playground.stt.constant.ErrorType) {
                                main.post { fail("recognition_init_${errorType.name}") }
                            }
                        })
                    } catch (_: Exception) {
                        fail("recognition_unavailable")
                    }
                }
            } finally {
                if (cancellationOwner == id) {
                    cancelPending = null
                    cancellationOwner = null
                }
                releaseStt(id)
                releaseFocus(id)
            }
        }
    }

    override suspend fun speak(text: String) = withContext(Dispatchers.Main.immediate) {
        if (text.isBlank()) return@withContext
        withTimeout(60_000) {
            val id = generation.advance()
            val startedAt = SystemClock.elapsedRealtime()
            trace("tts", id, "speak_requested", startedAt)
            awaitTtsStop()
            if (!generation.accepts(id)) throw CancellationException()
            acquireFocus(id)
            try {
                suspendCancellableCoroutine<Unit> { continuation ->
                    fun fail(code: String, alreadyStopped: Boolean = false) {
                        if (!generation.accepts(id) || !continuation.isActive) return
                        trace("tts", id, "failure_$code", startedAt)
                        generation.advance()
                        releaseTts(id, completed = alreadyStopped)
                        continuation.resumeWithException(SpeechFailure(code, "음성을 재생하지 못했어요. 다시 시작해 주세요"))
                    }
                    cancellationOwner = id
                    cancelPending = { if (continuation.isActive) continuation.cancel() }
                    continuation.invokeOnCancellation {
                        main.post {
                            if (generation.accepts(id)) {
                                generation.advance()
                                releaseTts()
                            }
                        }
                    }
                    try {
                        val sdk = TextToSpeech(context, ai.pleos.playground.tts.constant.Mode.ON_DEVICE)
                        tts = sdk
                        ttsOwner = id
                        lateinit var resources: TtsResources
                        val listener = object : ai.pleos.playground.tts.listener.EventListener {
                            override fun onReady() = trace("tts", id, "callback_ready", startedAt)
                            override fun onStart() = trace("tts", id, "callback_started", startedAt)
                            override fun onDone() {
                                trace("tts", id, "callback_done", startedAt)
                                main.post {
                                    if (generation.accepts(id) &&
                                        continuation.isActive
                                    ) {
                                        trace("tts", id, "accepted_done", startedAt)
                                        generation.advance()
                                        // SDK 3.3.1 stop is asynchronous and package-scoped. A completed
                                        // utterance must not enqueue a stop that can hit the next one.
                                        releaseTts(id, completed = true)
                                        continuation.resume(Unit)
                                    }
                                }
                            }
                            override fun onStop() {
                                main.post {
                                    // Stop has no utterance ID. This acknowledgement is accepted only
                                    // while the old binding is retained and no successor can register.
                                    if (resources.isStopping) {
                                        trace("tts", id, "stop_acknowledged", startedAt)
                                        resources.acknowledgeStop()
                                    } else {
                                        fail("playback_stopped", alreadyStopped = true)
                                    }
                                }
                            }
                            override fun onError(errorType: ai.pleos.playground.tts.constant.ErrorType) {
                                main.post { fail("playback_${errorType.name}") }
                            }
                            override fun onUpdatedRms(rms: Double) = Unit
                        }
                        ttsListener = listener
                        resources = TtsResources(
                            detachListener = { sdk.removeEventListener(listener) },
                            stop = {
                                trace("tts", id, "stop_requested", startedAt)
                                sdk.stop()
                            },
                            release = { sdk.release() }
                        )
                        ttsResources = resources
                        sdk.initialize(object : ai.pleos.playground.tts.listener.OnInitializeListener {
                            private var configured = false
                            override fun onSuccess() {
                                trace("tts", id, "sdk_initialized", startedAt)
                                main.post {
                                    if (!generation.accepts(id) || !continuation.isActive) return@post
                                    if (configured) return@post
                                    configured = true
                                    runCatching {
                                        sdk.setLocale(Locale.KOREA)
                                        sdk.addEventListener(listener)
                                        CoroutineScope(continuation.context).launch {
                                            try {
                                                // Open the initialized assistant route with 160ms of zero PCM.
                                                trace("tts", id, "preroll_started", startedAt)
                                                withContext(Dispatchers.IO) { preRoll() }
                                                trace("tts", id, "preroll_done", startedAt)
                                                if (generation.accepts(id) && continuation.isActive && focusOwner == id) {
                                                    resources.markSpeakDispatched()
                                                    sdk.speak(text, true)
                                                    trace("tts", id, "sdk_speak_dispatched", startedAt)
                                                }
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (_: Exception) {
                                                fail("playback_unavailable")
                                            }
                                        }
                                    }
                                        .onFailure { fail("playback_unavailable") }
                                }
                            }
                            override fun onFailure(errorType: ai.pleos.playground.tts.constant.ErrorType) {
                                main.post { fail("playback_init_${errorType.name}") }
                            }
                        })
                    } catch (_: Exception) {
                        fail("playback_unavailable")
                    }
                }
            } finally {
                if (cancellationOwner == id) {
                    cancelPending = null
                    cancellationOwner = null
                }
                releaseTts(id)
                releaseFocus(id)
            }
        }
    }
    private fun preRoll() {
        val frames = 2560
        val track = AudioTrack.Builder().setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        )
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(frames * 2).build()
        try {
            track.write(ByteArray(frames * 2), 0, frames * 2)
            track.play()
            Thread.sleep(160)
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }
    override fun pause() {
        generation.advance()
        cancelPending?.invoke()
        cancelPending = null
        cancellationOwner = null
        releaseStt()
        releaseTts()
        releaseFocus()
    }
}
