package app.doran.car

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.doran.car.speech.PleosSpeech
import app.doran.car.speech.SpeechFailure
import app.doran.car.vehicle.DrivingRestrictions
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One opt-in TTS case per instrumentation invocation. No microphone, AI, auth, preferences,
 * database, release-app access, or audio capture. An external recorder owns the digital capture.
 * Run baseline and product under the same explicitly reported reset/output conditions.
 * A failed case must stop the comparison; it must not automatically fall through to another case.
 */
@RunWith(AndroidJUnit4::class)
class TtsPathComparisonTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext
    private val main = Handler(Looper.getMainLooper())

    @Test
    fun captureSelectedTtsPath(): Unit = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit opt-in required", arguments.getString("run_tts_comparison") == "true")
        val path = arguments.getString("tts_path")
        assertTrue("Select exactly one baseline or product case", path in setOf("baseline", "product"))
        val reset = arguments.getString("reset_condition")
        assertTrue("Declare the actual reset condition", reset in setOf("fresh_avd_boot", "fresh_process_same_vm"))
        assertEquals("Use the independent debug package", "app.doran.car.debug", target.packageName)
        assertTrue(target.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        assertTrue(target.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE))
        assertTrue(Build.FINGERPRINT.contains("avd", true) || Build.HARDWARE in setOf("ranchu", "goldfish"))

        val started = SystemClock.elapsedRealtime()
        val directory = File(target.filesDir, "tts-path-comparison/$path-$started").apply { mkdirs() }
        val events = CopyOnWriteArrayList<JSONObject>()
        val report = JSONObject()
            .put("path", path).put("started_at", Instant.now().toString())
            .put("guest_elapsed_realtime_ms", started).put("process_start_elapsed_realtime_ms", Process.getStartElapsedRealtime())
            .put("operator_reset_condition", reset).put("reset_condition_independently_proven", false)
            .put("sdk_service_warm_state", "not inferred from process reset")
            .put("sdk_mode", "ON_DEVICE").put("locale", "ko_KR")
            .put("utterance", PHRASE).put("utterance_sha256", sha256(PHRASE))
            .put("sdk_replay_parameter", true)
            .put("sdk_audio_attributes", "SDK 3.3.1 default: CONTENT_TYPE_SPEECH / USAGE_MEDIA; not overridden")
            .put("app_focus", if (path == "product") "USAGE_ASSISTANT / GAIN_TRANSIENT_MAY_DUCK" else "none requested by harness")
            .put("app_preroll", if (path == "product") "production 160 ms zero PCM" else "none")
            .put("microphone_or_ai_called", false).put("release_account_or_storage_accessed", false)
            .put("physical_audibility_verified", false)
            .put("digital_capture_complete", false).put("external_capture_review_required", true)
            .put("latency_comparison_valid", false).put("planned_order", "baseline then product in the same VM")
            .put("digital_capture", "external recorder; collection success is not asserted here")
        fun mark(event: String) {
            val elapsed = SystemClock.elapsedRealtime() - started
            events.add(JSONObject().put("event", event).put("elapsed_ms", elapsed).put("at", Instant.now().toString()))
            Log.i("DoranTtsCompare", "path=$path event=$event elapsed_ms=$elapsed")
        }

        var activity: Activity? = null
        try {
            activity = instrumentation.startActivitySync(
                Intent(target, SdkVerificationActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            instrumentation.waitForIdleSync()
            assertParked()
            mark("case_start")
            if (path == "baseline") baseline(report, ::mark) else product(report, ::mark)
            report.put("status", "SDK_DONE_RECEIVED").put("case_completed", true)
                .put("next_case_allowed", true)
            mark("case_completed")
        } catch (error: Throwable) {
            report.put("status", if (error is TimeoutCancellationException) "TIMEOUT" else "FAILED")
                .put("error_kind", if (error is SpeechFailure) error.kind else error.javaClass.simpleName)
                .put("case_completed", false).put("next_case_allowed", false)
                .put("digital_capture_status", "incomplete or unverified; never infer silence from a failed case")
            mark("case_failed")
            throw error
        } finally {
            withContext(NonCancellable) {
                activity?.let { host -> instrumentation.runOnMainSync { host.finish() } }
                report.put("events", JSONArray(events)).put("finished_at", Instant.now().toString())
                    .put("elapsed_ms", SystemClock.elapsedRealtime() - started)
                File(directory, "report.json").writeText(report.toString(2))
            }
        }
        Unit
    }

    private suspend fun assertParked() = withContext(Dispatchers.Main) {
        val restrictions = DrivingRestrictions(target)
        try {
            restrictions.start()
            val state = withTimeout(10_000) { restrictions.state.filter { it.known }.first() }
            assertTrue("Park and setup permission are required", state.canSetup)
        } finally {
            restrictions.close()
        }
    }

    private suspend fun product(report: JSONObject, mark: (String) -> Unit) {
        val interrupted = AtomicBoolean()
        val speech = withContext(Dispatchers.Main) { PleosSpeech(target) { interrupted.set(true) } }
        try {
            mark("production_speak_called")
            withTimeout(65_000) { speech.speak(PHRASE) }
            mark("production_speak_returned_after_sdk_done")
            assertTrue("Unexpected audio focus interruption", !interrupted.get())
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                speech.pause()
                report.put("production_pause_cleanup_called", true)
                    .put("audio_focus_interrupted", interrupted.get())
            }
        }
    }

    /** Official minimal SDK sequence, without the app's focus or preroll wrapper. */
    private suspend fun baseline(report: JSONObject, mark: (String) -> Unit) = withContext(Dispatchers.Main) {
        val sdk = TextToSpeech(target, Mode.ON_DEVICE)
        val terminal = AtomicBoolean()
        val stopAck = CompletableDeferred<Unit>()
        var completed = false
        var speakIssued = false
        var listener: EventListener? = null
        try {
            withTimeout(60_000) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    fun fail(kind: String) {
                        if (!continuation.isActive || !terminal.compareAndSet(false, true)) return
                        mark(kind)
                        continuation.resumeWithException(SpeechFailure(kind, "SDK comparison case failed"))
                    }
                    val callback = object : EventListener {
                        override fun onReady() {
                            main.post { mark("sdk_ready") }
                        }
                        override fun onStart() {
                            main.post { mark("sdk_started") }
                        }
                        override fun onDone() {
                            main.post {
                                mark("sdk_done_callback")
                                if (continuation.isActive && terminal.compareAndSet(false, true)) {
                                    completed = true
                                    mark("sdk_done_accepted")
                                    continuation.resume(Unit)
                                }
                            }
                        }
                        override fun onStop() {
                            main.post {
                                mark("sdk_stop_callback")
                                stopAck.complete(Unit)
                                fail("sdk_stopped")
                            }
                        }
                        override fun onError(errorType: ErrorType) {
                            main.post { fail("sdk_error_${errorType.name}") }
                        }
                        override fun onUpdatedRms(rms: Double) = Unit
                    }
                    listener = callback
                    try {
                        mark("sdk_initialize_called")
                        sdk.initialize(object : OnInitializeListener {
                            private var configured = false
                            override fun onSuccess() {
                                main.post {
                                    if (!continuation.isActive || terminal.get() || configured) return@post
                                    configured = true
                                    mark("sdk_initialized")
                                    try {
                                        sdk.setLocale(Locale.KOREA)
                                        sdk.addEventListener(callback)
                                        speakIssued = true
                                        mark("sdk_speak_called")
                                        sdk.speak(PHRASE, true)
                                        mark("sdk_speak_returned")
                                    } catch (_: Exception) {
                                        fail("sdk_speak_failed")
                                    }
                                }
                            }
                            override fun onFailure(errorType: ErrorType) {
                                main.post { fail("sdk_init_${errorType.name}") }
                            }
                        })
                    } catch (_: Exception) {
                        fail("sdk_initialize_failed")
                    }
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                terminal.set(true)
                // Keep the old listener during failure cleanup to observe its stop. Never start
                // a second case automatically if this request failed or the stop was unconfirmed.
                var stopRequested = false
                if (!completed && speakIssued && !stopAck.isCompleted) {
                    stopRequested = true
                    mark("cleanup_stop_requested")
                    runCatching { sdk.stop() }
                    withTimeoutOrNull(3_000) { stopAck.await() }
                }
                report.put("normal_sdk_done", completed).put("speak_issued", speakIssued)
                    .put("cleanup_stop_requested", stopRequested)
                    .put("stop_callback_observed", stopAck.isCompleted)
                val detached = listener?.let { runCatching { sdk.removeEventListener(it) }.isSuccess } ?: true
                val released = runCatching { sdk.release() }.isSuccess
                report.put("sdk_listener_detach_succeeded", detached).put("sdk_release_succeeded", released)
                mark(if (detached && released) "sdk_listener_detached_and_released" else "sdk_cleanup_failed")
                if (completed && (!detached || !released)) {
                    throw SpeechFailure("sdk_cleanup_failed", "SDK comparison cleanup failed")
                }
            }
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    companion object {
        private const val PHRASE = "안녕하세요, 도란 음성 확인입니다."
    }
}
