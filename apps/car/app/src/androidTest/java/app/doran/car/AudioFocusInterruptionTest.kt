package app.doran.car

import ai.pleos.playground.tts.listener.EventListener
import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.doran.car.data.SecureStorage
import app.doran.car.speech.PleosSpeech
import app.doran.car.speech.SpeechFailure
import app.doran.car.vehicle.DrivingRestrictions
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in, debug-target instrumentation. Uses production TTS and real AudioManager arbitration.
 * No STT/microphone, account bootstrap, network, telecom call, or navigation route is started.
 * Retained old onDone is deliberately replayed once; that part is synthetic callback testing.
 */
@RunWith(AndroidJUnit4::class)
class AudioFocusInterruptionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext
    private val audio = target.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val interruptions = AtomicInteger()
    private val interruptionTimes = CopyOnWriteArrayList<Long>()
    private val speech = PleosSpeech(target) {
        interruptionTimes.add(SystemClock.elapsedRealtime())
        interruptions.incrementAndGet()
    }
    private val running = mutableListOf<SpeechRun>()
    private val focusRequests = mutableListOf<AudioFocusRequest>()
    private val runMarker = "focus_probe_begin_${UUID.randomUUID()}"
    private val speechEvents = Regex("kind=tts request=(\\d+) event=([a-z_]+) elapsed_ms=(\\d+)")

    @Test
    fun capturePlatformFocusLossAndRejectStaleDone(): Unit = runBlocking {
        assumeTrue("Explicit opt-in required", InstrumentationRegistry.getArguments().getString("run_focus_probe") == "true")
        assertEquals("app.doran.car.debug", target.packageName)
        assertTrue(target.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        assertTrue(target.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE))
        assertTrue(Build.FINGERPRINT.contains("avd", true) || Build.HARDWARE in setOf("ranchu", "goldfish"))
        val secure = SecureStorage(target)
        assertTrue("Use an unpaired debug installation", secure.get("owner_id") == null && secure.get("active_session") == null)
        val report = JSONObject()
            .put("started_at", Instant.now().toString())
            .put("package", target.packageName)
            .put("fingerprint", Build.FINGERPRINT)
            .put(
                "scope",
                "Real same-UID/different-listener focus requests and production TTS; one synthetic old-done replay after each loss"
            )
            .put("microphone_used", false)
            .put("real_call_or_navigation_audio", false)
            .put("physical_speaker_audibility_verified", false)
        var activity: Activity? = null
        try {
            activity = instrumentation.startActivitySync(
                Intent(target, SdkVerificationActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            instrumentation.waitForIdleSync()
            assertParked()
            Log.i("DoranFocusProbe", runMarker)
            withTimeout(180_000) {
                val cases = JSONArray()
                report.put("cases", cases)
                val navigation = JSONObject().put("name", "navigation_guidance")
                cases.put(navigation)
                probeCompetition(
                    AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
                    requireInterruption = false,
                    report = navigation
                )
                val communication = JSONObject().put("name", "voice_communication")
                cases.put(communication)
                probeCompetition(
                    AudioAttributes.USAGE_VOICE_COMMUNICATION,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
                    requireInterruption = true,
                    report = communication
                )
                report.put("call_usage_interruption_verified", true).put("harness_completed", true)
            }
        } catch (error: Throwable) {
            report.put("failure_class", error.javaClass.simpleName)
            throw error
        } finally {
            withContext(NonCancellable) {
                withContext(Dispatchers.Main) {
                    speech.pause()
                    focusRequests.forEach { audio.abandonAudioFocusRequest(it) }
                }
                running.forEach { it.job.cancelAndJoin() }
                activity?.let { host -> instrumentation.runOnMainSync { host.finish() } }
                report.put("interruption_count", interruptions.get())
                    .put("interruption_elapsed_realtime_ms", JSONArray(interruptionTimes))
                    .put("finished_at", Instant.now().toString())
                File(target.filesDir, "audio-focus-verification.json").writeText(report.toString(2))
            }
        }
        Unit
    }

    private suspend fun assertParked() = withContext(Dispatchers.Main) {
        val restrictions = DrivingRestrictions(target)
        try {
            restrictions.start()
            val state = withTimeout(10_000) { restrictions.state.filter { it.known }.first() }
            assertTrue("The diagnostic requires parking and setup permission", state.canSetup)
        } finally {
            restrictions.close()
        }
    }

    private suspend fun probeCompetition(usage: Int, gain: Int, requireInterruption: Boolean, report: JSONObject) {
        val first = startSpeech()
        val owned = waitForOwnedSpeech(first)
        val owner = owned.copy(clientId = discoverPlatformClient(owned.managerPrefix))
        awaitSpeechEvent(first, owner.id, "callback_started")
        val oldDone = owner.listener
        assertNotNull("Production SDK listener must exist before interruption", oldDone)
        val platformBefore = readPlatformMembership(owner.clientId)
        val before = interruptions.get()
        val contenderEvents = CopyOnWriteArrayList<Int>()
        // A distinct listener gives this request a distinct AAOS client ID, even with the same UID.
        val contenderListener = AudioManager.OnAudioFocusChangeListener { contenderEvents.add(it) }
        val contender = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener(contenderListener, main)
            .build()
        assertNotSame(owner.focus, contender)
        focusRequests.add(contender)
        report.put("usage", usage).put("gain", gain).put("producer_request", owner.id)
            .put("same_uid_distinct_listener", true)
            .put("platform_client_identified", owner.clientId != null)
            .put("platform_membership_before", platformBefore.toJson())
        val result = withContext(Dispatchers.Main) { audio.requestAudioFocus(contender) }
        report.put("request_result", result).put("request_elapsed_realtime_ms", SystemClock.elapsedRealtime())
        try {
            val interrupted = withTimeoutOrNull(3_000) {
                while (interruptions.get() == before && !first.outcome.isCompleted) delay(20)
                interruptions.get() > before
            } == true
            report.put("interruption_observed", interrupted)
            if (requireInterruption) {
                assertEquals("CALL usage must be granted for this loss/cancellation check", AudioManager.AUDIOFOCUS_REQUEST_GRANTED, result)
                assertTrue("CALL usage did not produce an interruption; record a failed check rather than simulating it", interrupted)
            }
            if (interrupted) {
                val outcome = withTimeout(5_000) { first.outcome.await() }
                report.put("interrupted_speech_outcome", outcome.kind)
                assertEquals("CANCELLED", outcome.kind)
                assertReleased(report)
                verifyPlatformRelease(owner, platformBefore, report)
                withContext(Dispatchers.Main) { audio.abandonAudioFocusRequest(contender) }
                focusRequests.remove(contender)
                verifyNextRequestRejectsOldDone(owner, oldDone!!, report)
            } else {
                // AAOS's default VOICE_COMMAND -> NAVIGATION policy is rejection, not loss.
                // OEM policy may differ; preserve the observed return value without forcing loss.
                report.put(
                    "policy_observation",
                    if (result == AudioManager.AUDIOFOCUS_REQUEST_FAILED) "CONTENDER_REJECTED_NO_LOSS" else "NO_LOSS_OBSERVED"
                )
                withContext(Dispatchers.Main) { audio.abandonAudioFocusRequest(contender) }
                focusRequests.remove(contender)
                val outcome = withTimeout(60_000) { first.outcome.await() }
                report.put("uninterrupted_speech_outcome", outcome.kind).put("error_kind", outcome.errorKind ?: JSONObject.NULL)
                assertEquals("COMPLETED", outcome.kind)
                assertEquals(before, interruptions.get())
                assertReleased(report)
                verifyPlatformRelease(owner, platformBefore, report)
            }
        } finally {
            report.put("contender_focus_changes", JSONArray(contenderEvents))
            val captured = runCatching { eventsFor(owner.id) }
            if (captured.isSuccess) {
                report.put("production_events", JSONArray(captured.getOrThrow().map { it.toJson() }))
            } else {
                report.put("production_event_capture_error", captured.exceptionOrNull()?.javaClass?.simpleName)
            }
            withContext(Dispatchers.Main) { audio.abandonAudioFocusRequest(contender) }
            focusRequests.remove(contender)
        }
    }

    private suspend fun verifyNextRequestRejectsOldDone(old: SpeechOwner, oldDone: EventListener, report: JSONObject) {
        val replay = JSONObject().put("input_kind", "synthetic replay of the retained old SDK listener; not an observed late SDK event")
        report.put("stale_done_check", replay)
        val next = startSpeech()
        val current = waitForOwnedSpeech(next)
        assertTrue(current.id != old.id)
        assertNotSame(old.listener, current.listener)
        awaitSpeechEvent(next, current.id, "callback_started")
        replay.put("new_request", current.id)
            .put("old_done_callbacks_before_replay", eventsFor(old.id).count { it.event == "callback_done" })
        withContext(Dispatchers.Main) { oldDone.onDone() }
        replay.put("injected_done_count", 1)
        delay(300)
        assertFalse("An old done callback completed the next request", next.outcome.isCompleted)
        assertTrue(next.job.isActive)
        val retained = withContext(Dispatchers.Main) { inspectSpeech() }
        assertEquals(current.id, retained.id)
        assertTrue(retained.focus === current.focus)
        assertTrue(retained.listener === current.listener)
        assertEquals(0, eventsFor(old.id).count { it.event == "accepted_done" })
        val outcome = withTimeout(60_000) { next.outcome.await() }
        replay.put("next_outcome", outcome.kind)
        assertEquals("COMPLETED", outcome.kind)
        assertEquals(1, eventsFor(current.id).count { it.event == "accepted_done" })
        assertEquals(0, eventsFor(old.id).count { it.event == "accepted_done" })
        replay.put("old_accepted_done_count", 0).put("new_accepted_done_count", 1)
            .put("completed_only_after_new_sdk_done", true)
            .put("new_production_events", JSONArray(eventsFor(current.id).map { it.toJson() }))
        assertReleased(replay)
    }

    private suspend fun startSpeech(): SpeechRun {
        val outcome = CompletableDeferred<SpeechOutcome>()
        // Long enough to request focus after the real onStart without relying on a blind delay.
        val text = "도란 오디오 포커스 시험입니다. 다른 음성의 개입을 확인하고 있어요. " +
            "이 문장은 시험용 합성 음성입니다. 사용자의 실제 대화나 통화 내용은 사용하지 않습니다."
        val job = CoroutineScope(currentCoroutineContext()).launch(Dispatchers.Main) {
            try {
                speech.speak(text)
                outcome.complete(SpeechOutcome("COMPLETED"))
            } catch (cancelled: CancellationException) {
                outcome.complete(SpeechOutcome("CANCELLED"))
                throw cancelled
            } catch (failure: SpeechFailure) {
                outcome.complete(SpeechOutcome("FAILED", failure.kind))
            } catch (failure: Exception) {
                outcome.complete(SpeechOutcome("FAILED", failure.javaClass.simpleName))
            }
        }
        return SpeechRun(job, outcome).also { running.add(it) }
    }

    private suspend fun waitForOwnedSpeech(run: SpeechRun): SpeechOwner = withTimeout(5_000) {
        while (true) {
            val owner = withContext(Dispatchers.Main) { inspectSpeech() }
            if (owner.id != null && owner.focus != null && owner.listener != null) return@withTimeout owner
            assertFalse("Speech ended before it owned focus and an SDK listener", run.outcome.isCompleted)
            delay(20)
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    private suspend fun awaitSpeechEvent(run: SpeechRun, request: Long?, event: String) = withTimeout(25_000) {
        while (eventsFor(request).none { it.event == event }) {
            assertFalse("Speech ended before the real SDK $event", run.outcome.isCompleted)
            delay(50)
        }
    }

    private suspend fun eventsFor(request: Long?): List<SpeechEvent> = withContext(Dispatchers.IO) {
        val log = readShell("logcat -d --pid=${Process.myPid()} -v brief -s DoranSpeech:I DoranFocusProbe:I '*:S'")
        val marker = log.lastIndexOf(runMarker)
        check(marker >= 0) { "This run's log marker is absent; stale log events cannot be used" }
        speechEvents.findAll(log.substring(marker + runMarker.length)).map {
            SpeechEvent(it.groupValues[1].toLong(), it.groupValues[2], it.groupValues[3].toLong())
        }.filter { it.request == request }.toList()
    }

    private suspend fun assertReleased(report: JSONObject) {
        val released = withContext(Dispatchers.Main) { inspectSpeech() }
        assertTrue(released.id == null && released.focus == null && released.listener == null)
        assertFalse(released.ttsBound || released.sttBound || released.cancelPending)
        report.put("local_focus_and_sdk_references_released", true)
    }

    private suspend fun verifyPlatformRelease(owner: SpeechOwner, before: FocusMembership, report: JSONObject) {
        val started = SystemClock.elapsedRealtime()
        var after = readPlatformMembership(owner.clientId)
        while (before.supported &&
            before.holders > 0 &&
            after.supported &&
            after.total > 0 &&
            SystemClock.elapsedRealtime() - started < 3_000
        ) {
            delay(50)
            after = readPlatformMembership(owner.clientId)
        }
        report.put("platform_membership_after", after.toJson())
            .put("platform_release_observation_ms", SystemClock.elapsedRealtime() - started)
        val identifiable = before.supported && before.holders > 0 && after.supported
        report.put("platform_release_verified", if (identifiable) after.total == 0 else JSONObject.NULL)
        if (identifiable) assertEquals("Abandoned producer remains in the current AAOS focus entries", 0, after.total)
        // An absent or OEM-modified dump is explicitly unverified, never evidence of abandonment.
    }

    private suspend fun readPlatformMembership(clientId: String?): FocusMembership = withContext(Dispatchers.IO) {
        if (clientId == null) return@withContext FocusMembership(false)
        val dump = readShell("dumpsys car_service --services CarAudioService")
        val blocks = dump.split("*CarAudioFocus*").drop(1)
        if (blocks.isEmpty()) return@withContext FocusMembership(false)
        var holders = 0
        var losers = 0
        var delayed = 0
        for (block in blocks) {
            val lines = block.lines().map { it.trim() }
            val holderStart = lines.indexOf("Current Focus Holders:")
            val loserStart = lines.indexOf("Transient Focus Losers:")
            val queued = lines.indexOfFirst { it.startsWith("Queued Delayed Focus:") }
            val history = lines.indexOf("Focus Events:")
            if (holderStart < 0 || loserStart <= holderStart || queued <= loserStart || history <= queued) {
                return@withContext FocusMembership(false)
            }
            // Match only entry header lines: blocker references and Focus Events history are excluded.
            val entryPrefix = "$clientId - "
            holders += lines.subList(holderStart + 1, loserStart).count { it.startsWith(entryPrefix) }
            losers += lines.subList(loserStart + 1, queued).count { it.startsWith(entryPrefix) }
            if (lines[queued].substringAfter("Queued Delayed Focus:").trim() == clientId) delayed++
        }
        FocusMembership(true, holders, losers, delayed)
    }

    private suspend fun discoverPlatformClient(managerPrefix: String): String? = withContext(Dispatchers.IO) {
        val blocks = readShell("dumpsys car_service --services CarAudioService").split("*CarAudioFocus*").drop(1)
        if (blocks.isEmpty()) return@withContext null
        val candidates = mutableSetOf<String>()
        for (block in blocks) {
            val lines = block.lines().map { it.trim() }
            val holders = lines.indexOf("Current Focus Holders:")
            val losers = lines.indexOf("Transient Focus Losers:")
            val queued = lines.indexOfFirst { it.startsWith("Queued Delayed Focus:") }
            val history = lines.indexOf("Focus Events:")
            if (holders < 0 || losers <= holders || queued <= losers || history <= queued) return@withContext null
            lines.subList(holders + 1, losers).filter {
                it.startsWith(managerPrefix) && it.contains(" - ") && it.substringAfter(" - ").contains("USAGE_ASSISTANT")
            }.forEach { candidates.add(it.substringBefore(" - ")) }
        }
        // No framework-hidden getter/reflection: ambiguity is explicitly unverified.
        candidates.singleOrNull()
    }

    private fun readShell(command: String): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }

    // Debug instrumentation only: observing private ownership adds no API or hook to production.
    private fun inspectSpeech(): SpeechOwner {
        check(Looper.myLooper() == Looper.getMainLooper())
        fun field(name: String): Any? = PleosSpeech::class.java.getDeclaredField(name).apply { isAccessible = true }.get(speech)
        val focus = field("focus") as AudioFocusRequest?
        val manager = field("audio") as AudioManager
        return SpeechOwner(
            field("focusOwner") as Long?,
            focus,
            field("ttsListener") as EventListener?,
            field("tts") != null,
            field("stt") != null,
            field("cancelPending") != null,
            manager.toString()
        )
    }

    private data class SpeechRun(val job: Job, val outcome: CompletableDeferred<SpeechOutcome>)
    private data class SpeechOutcome(val kind: String, val errorKind: String? = null)
    private data class SpeechEvent(val request: Long, val event: String, val elapsedMs: Long) {
        fun toJson(): JSONObject = JSONObject().put("request", request).put("event", event).put("elapsed_ms", elapsedMs)
    }
    private data class FocusMembership(val supported: Boolean, val holders: Int = 0, val losers: Int = 0, val delayed: Int = 0) {
        val total: Int get() = holders + losers + delayed
        fun toJson(): JSONObject = JSONObject().put("supported_format", supported)
            .put("holder_matches", holders).put("loser_matches", losers).put("delayed_matches", delayed)
    }
    private data class SpeechOwner(
        val id: Long?,
        val focus: AudioFocusRequest?,
        val listener: EventListener?,
        val ttsBound: Boolean,
        val sttBound: Boolean,
        val cancelPending: Boolean,
        val managerPrefix: String,
        val clientId: String? = null
    )
}
