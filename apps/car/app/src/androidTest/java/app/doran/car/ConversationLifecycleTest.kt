package app.doran.car

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.doran.car.domain.Phase
import app.doran.car.qa.QaConversationActivity
import app.doran.car.qa.QaConversationRun
import app.doran.car.qa.SyntheticConversationApi
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Actual Activity onStop/onStart, production ViewModel, Room and Keystore with synthetic speech
 * and an in-memory API. This is not microphone, audio focus, sound quality, real network,
 * server idempotency or process-death evidence. Explicit probes observe CarUx transitions and
 * prepared split-window bounds; pixel layout and control readability still require visual review.
 */
@RunWith(AndroidJUnit4::class)
class ConversationLifecycleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext

    @Test
    fun backgroundCancelsActiveSpeechAndRejectsRetiredCallbacks(): Unit = runBlocking {
        withRun("background_listening") { run, activity ->
            withContext(Dispatchers.Main) { activity.model.start() }
            awaitPhase(activity, Phase.LISTENING)
            withContext(Dispatchers.Main) { run.speech.updatePartial("합성 시험: 듣던 중의 부분 결과") }
            assertTrue(activity.model.state.value.transcript.isNotEmpty())
            val cover = cover(activity)
            try {
                awaitPhase(activity, Phase.PAUSED)
                assertTrue(run.events.contains("activity_stopped"))
                assertTrue(run.speech.events.contains("listen_cancelled"))
                val paused = activity.model.state.value
                assertEquals("", paused.transcript)
                assertEquals("", paused.reply)
                withContext(Dispatchers.Main) { run.speech.emitRetiredCallbacks() }
                assertEquals("Retired callbacks must not change paused state", paused, activity.model.state.value)
            } finally {
                uncover(activity, cover)
            }
            awaitPhase(activity, Phase.PAUSED)
            withContext(Dispatchers.Main) { activity.model.start() }
            awaitPhase(activity, Phase.LISTENING)
            val resumed = activity.model.state.value
            withContext(Dispatchers.Main) { run.speech.emitRetiredCallbacks() }
            assertEquals("Old generation must not replace resumed transcript", resumed, activity.model.state.value)
            assertEquals(2, run.speech.listenCount)
        }
    }

    @Test
    fun pendingPauseBlocksReplayAndBackgroundRevokesOldSoloContext(): Unit = runBlocking {
        withRun("pause_replay_privacy") { run, activity ->
            val pauseGate = CompletableDeferred<Unit>()
            withContext(Dispatchers.Main) {
                run.api.pauseResponse = pauseGate
                activity.model.setSoloAsserted(true)
                activity.model.start()
            }
            awaitPhase(activity, Phase.LISTENING)
            withContext(Dispatchers.Main) { run.speech.submit("합성 시험: 내일 발표 자료를 준비했어요") }
            awaitPhase(activity, Phase.SPEAKING)
            val original = run.api.calls.single { it.operation == "turn" }
            assertEquals("alone", original.context?.getString("passenger_state"))
            val cover = cover(activity)
            try {
                awaitPhase(activity, Phase.PAUSED)
                awaitCondition { run.api.calls.any { it.operation == "pause_started" } }
                assertFalse(activity.model.state.value.soloAsserted)
                assertEquals("", activity.model.state.value.transcript)
                assertEquals("", activity.model.state.value.reply)
                assertEquals(1, run.database.outbox().all().size)
                assertTrue(run.speech.events.contains("synthetic_speak_cancelled"))
            } finally {
                uncover(activity, cover)
            }
            awaitPhase(activity, Phase.PAUSED)
            withContext(Dispatchers.Main) {
                run.api.mode = SyntheticConversationApi.TurnMode.COMPLETE
                run.speech.autoCompleteSpeech = true
                activity.model.start()
            }
            awaitPhase(activity, Phase.STARTING)
            // Yield through the main queue twice; do not use a fixed delay as a correctness oracle.
            withContext(Dispatchers.Main) { }
            withContext(Dispatchers.Main) { }
            assertEquals("Replay must wait for server pause", 1, run.api.calls.count { it.operation == "turn" })
            assertEquals("No new listening while pause is pending", 1, run.speech.listenCount)
            withContext(Dispatchers.Main) { pauseGate.complete(Unit) }
            awaitPhase(activity, Phase.LISTENING)
            val turns = run.api.calls.filter { it.operation == "turn" }
            assertEquals(2, turns.size)
            assertEquals("Replay must retain its idempotency key", original.requestId, turns.last().requestId)
            assertEquals("unknown", turns.last().context?.getString("passenger_state"))
            assertEquals("unknown", turns.last().context?.getString("passenger_source"))
            assertTrue(
                run.api.calls.indexOfFirst { it.operation == "pause_completed" } < run.api.calls.indexOfLast { it.operation == "turn" }
            )
            assertTrue(run.database.outbox().all().isEmpty())
        }
    }

    @Test
    fun speechFailurePausesServerAndPreservesUnacknowledgedTurn(): Unit = runBlocking {
        withRun("speech_failure") { run, activity ->
            withContext(Dispatchers.Main) {
                run.speech.failNextSpeech = true
                activity.model.start()
            }
            awaitPhase(activity, Phase.LISTENING)
            withContext(Dispatchers.Main) { run.speech.submit("합성 시험: 재생 오류 경계를 확인해요") }
            awaitPhase(activity, Phase.PAUSED)
            awaitCondition { run.api.calls.any { it.operation == "pause_completed" } }
            assertEquals("synthetic_playback_failure", activity.model.state.value.errorCode)
            assertEquals(1, run.api.calls.count { it.operation == "pause_started" })
            assertEquals(1, run.database.outbox().all().size)
            assertEquals(1, activity.model.state.value.pendingCount)
            val paused = activity.model.state.value
            withContext(Dispatchers.Main) { run.speech.emitRetiredCallbacks() }
            assertEquals(paused, activity.model.state.value)
        }
    }

    @Test
    fun streamFailurePausesServerAndPreservesUnacknowledgedTurn(): Unit = runBlocking {
        withRun("stream_failure") { run, activity ->
            withContext(Dispatchers.Main) {
                run.api.mode = SyntheticConversationApi.TurnMode.FAIL_STREAM
                activity.model.start()
            }
            awaitPhase(activity, Phase.LISTENING)
            withContext(Dispatchers.Main) { run.speech.submit("합성 시험: 연결 오류 경계를 확인해요") }
            awaitPhase(activity, Phase.PAUSED)
            awaitCondition { run.api.calls.any { it.operation == "pause_completed" } }
            assertEquals("synthetic_stream_failure", activity.model.state.value.errorCode)
            assertEquals(1, run.api.calls.count { it.operation == "pause_started" })
            assertEquals(1, run.database.outbox().all().size)
            assertEquals(0, run.speech.speakCount)
        }
    }

    @Test
    fun orphanedFinishMetadataCannotBlockStartingAnotherConversation(): Unit = runBlocking {
        withRun("orphan_finish_recovery", prepare = { run ->
            // Match the same owner/device so account replacement cannot accidentally repair the fixture.
            run.secure.put("owner_id", run.api.ownerId)
            run.secure.put("device_id", run.api.deviceId)
            run.secure.put("finish_wanted", "true")
            run.secure.put("finish_request", "11111111-1111-4111-8111-111111111111")
        }) { run, activity ->
            assertNull(run.secure.get("active_session"))
            assertNull(run.secure.get("finish_wanted"))
            assertNull(run.secure.get("finish_request"))
            withContext(Dispatchers.Main) { activity.model.start() }
            awaitPhase(activity, Phase.LISTENING)
            assertEquals(1, run.api.calls.count { it.operation == "session_start" })
            assertEquals(0, run.api.calls.count { it.operation == "finish" })
        }
    }

    @Test
    fun cachedReplyFromFormerSoloContextIsAcknowledgedWithoutBeingExposed(): Unit = runBlocking {
        checkCachedReplyPrivacy(
            "cached_reply_from_alone",
            true,
            "합성 시험: 이전 개인 기록을 포함한 응답을 만들었어요"
        )
    }

    @Test
    fun cachedHistoryReplyFromUnknownContextIsAcknowledgedWithoutBeingExposed(): Unit = runBlocking {
        checkCachedReplyPrivacy(
            "cached_history_reply_from_unknown",
            false,
            "합성 시험: 지난 발표 때 내가 걱정했던 내용을 알려 줘"
        )
    }

    private suspend fun checkCachedReplyPrivacy(name: String, originallySolo: Boolean, originalText: String) {
        data class ObservedEvent(val name: String, val reply: String, val speakCount: Int, val ciphertext: String?)
        withRun(name) { run, activity ->
            withContext(Dispatchers.Main) {
                activity.model.setSoloAsserted(originallySolo)
                activity.model.start()
            }
            awaitPhase(activity, Phase.LISTENING)
            withContext(Dispatchers.Main) { run.speech.submit(originalText) }
            awaitPhase(activity, Phase.SPEAKING)
            awaitCondition { run.speech.speakCount == 1 }
            assertEquals("A current turn must still show its response", run.api.replyText, activity.model.state.value.reply)
            val pending = run.database.outbox().all().single()
            val originalPayload = JSONObject(run.secure.decrypt(pending.ciphertext))
            assertEquals(originalText, originalPayload.getString("text"))
            assertEquals(if (originallySolo) "alone" else "unknown", originalPayload.getJSONObject("context").getString("passenger_state"))
            val cover = cover(activity)
            try {
                awaitPhase(activity, Phase.PAUSED)
                assertFalse(activity.model.state.value.soloAsserted)
                assertEquals("", activity.model.state.value.transcript)
                assertEquals("", activity.model.state.value.reply)
            } finally {
                uncover(activity, cover)
            }
            awaitPhase(activity, Phase.PAUSED)
            val observations = CopyOnWriteArrayList<ObservedEvent>()
            val previousSpeakCount = run.speech.speakCount
            withContext(Dispatchers.Main) {
                run.speech.autoCompleteSpeech = true
                run.api.replyText = "합성 개인 기록: 예전 발표 이야기가 남아 있어요."
                run.api.mode = SyntheticConversationApi.TurnMode.COMPLETE
                run.api.afterEvent = { event ->
                    // Observe each applied delta/sentence/done, rather than only the final blank UI.
                    yield()
                    observations.add(
                        ObservedEvent(
                            event.name,
                            activity.model.state.value.reply,
                            run.speech.speakCount,
                            run.database.outbox().all().singleOrNull()?.ciphertext
                        )
                    )
                }
                activity.model.start()
            }
            awaitPhase(activity, Phase.LISTENING)
            assertEquals(listOf("delta", "sentence", "done"), observations.map { it.name })
            assertTrue("Cached private reply must never enter state.reply", observations.all { it.reply.isEmpty() })
            assertTrue("Cached private reply must not reach speech", observations.all { it.speakCount == previousSpeakCount })
            assertEquals(pending.ciphertext, observations.first().ciphertext)
            assertEquals(pending.ciphertext, observations[1].ciphertext)
            assertNull("Acknowledged done removes the original outbox row", observations.last().ciphertext)
            assertTrue(run.database.outbox().all().isEmpty())
            assertEquals(0, activity.model.state.value.pendingCount)
            val replay = run.api.calls.last { it.operation == "turn" }
            assertEquals(pending.requestId, replay.requestId)
            assertEquals("unknown", replay.context?.getString("passenger_state"))
            assertEquals("unknown", replay.context?.getString("passenger_source"))

            // Suppressing a recovered reply must not suppress an explicit new turn in unknown context.
            val listeningBefore = run.speech.listenCount
            val currentReply = "합성 현재 응답: 새로 말씀하신 이야기를 들었어요."
            withContext(Dispatchers.Main) {
                run.api.afterEvent = null
                run.api.replyText = currentReply
                run.speech.submit("합성 시험: 지금 새로 하는 이야기에는 답해 줘")
            }
            awaitCondition { run.speech.listenCount == listeningBefore + 1 }
            awaitPhase(activity, Phase.LISTENING)
            assertEquals(currentReply, activity.model.state.value.reply)
            assertEquals(previousSpeakCount + 1, run.speech.speakCount)
            assertTrue(run.database.outbox().all().isEmpty())
        }
    }

    /** External Console operator supplies Drive/Park. This test never changes vehicle controls. */
    @Test
    fun activeSyntheticListeningSurvivesActualDriveAndParkRestrictions(): Unit = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit driving probe opt-in required", arguments.getString("run_active_driving_probe") == "true")
        assertQaTarget()
        val probeId = checkNotNull(arguments.getString("driving_probe_run")) { "A fresh probe UUID is required" }
        require(UUID.fromString(probeId).toString() == probeId) { "Use a canonical lowercase UUID" }
        val directory = File(target.filesDir, "active-driving-probe/$probeId")
        check(!directory.exists()) { "Refuse to overwrite a prior probe" }
        check(directory.mkdirs())
        fun writePhase(phase: String, state: JSONObject = JSONObject()) {
            val value = state.put("phase", phase).put("probe_id", probeId)
                .put("at", Instant.now().toString()).put("guest_elapsed_ms", SystemClock.elapsedRealtime())
                .put("synthetic_speech", true).put("microphone_or_audio_opened", false)
            val temporary = File(directory, "phase.tmp")
            temporary.writeText(value.toString(2))
            check(temporary.renameTo(File(directory, "phase.json")))
        }
        try {
            withRun("active_drive_park", optInArgument = "run_active_driving_probe", drivingProbe = true) { run, activity ->
                withContext(Dispatchers.Main) { activity.model.start() }
                awaitPhase(activity, Phase.LISTENING)
                val priorStops = run.events.count { it == "activity_stopped" }
                suspend fun snapshot(expectedDriving: Boolean): JSONObject = withContext(Dispatchers.Main) {
                    val state = activity.model.state.value
                    assertTrue(state.uxKnown)
                    assertEquals(expectedDriving, state.driving)
                    assertEquals(!expectedDriving, state.canSetup)
                    assertEquals(!expectedDriving, state.canShowTranscript)
                    assertEquals(Phase.LISTENING, state.phase)
                    assertEquals(1, run.speech.listenCount)
                    assertEquals(0, run.speech.speakCount)
                    assertEquals(priorStops, run.events.count { it == "activity_stopped" })
                    assertFalse(activity.isFinishing || activity.isDestroyed)
                    assertTrue("QA conversation window must remain visible", activity.window.decorView.isShown)
                    JSONObject().put("qa_run_id", run.id).put("driving", state.driving).put("ux_known", state.uxKnown)
                        .put("can_setup", state.canSetup).put("can_show_transcript", state.canShowTranscript)
                        .put("conversation_phase", state.phase.name)
                        .put("app_width_px", activity.window.decorView.width).put("app_height_px", activity.window.decorView.height)
                }
                withTimeout(90_000) {
                    writePhase("awaiting_drive", snapshot(false))
                    val driving = activity.model.state.filter { it.uxKnown && it.driving }.first()
                    assertFalse(driving.canSetup)
                    assertFalse(driving.canShowTranscript)
                    writePhase("awaiting_park", snapshot(true))
                    val parked = activity.model.state.filter { it.uxKnown && !it.driving && it.canSetup }.first()
                    assertTrue(parked.canShowTranscript)
                    writePhase("completed_observations", snapshot(false))
                }
            }
        } catch (error: Throwable) {
            runCatching { writePhase("failed", JSONObject().put("error_kind", error.javaClass.simpleName)) }
                .onFailure(error::addSuppressed)
            throw error
        }
    }

    /** The operator prepares the OEM split before speech starts; this test never injects input. */
    @Test
    fun preparedSplitStartsAndFinishesSyntheticConversation(): Unit = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit split probe opt-in required", arguments.getString("run_prepared_split_probe") == "true")
        assertQaTarget()
        val probeId = checkNotNull(arguments.getString("split_probe_run")) { "A fresh probe UUID is required" }
        require(UUID.fromString(probeId).toString() == probeId) { "Use a canonical lowercase UUID" }
        val directory = File(target.filesDir, "prepared-split-probe/$probeId")
        check(!directory.exists()) { "Refuse to reuse a prior phase/control directory" }
        check(directory.mkdirs())
        var latestDetails = JSONObject()
        fun writePhase(phase: String, details: JSONObject = latestDetails) {
            val value = JSONObject(details.toString()).put("phase", phase).put("probe_id", probeId)
                .put("at", Instant.now().toString()).put("guest_elapsed_ms", SystemClock.elapsedRealtime())
                .put("synthetic_speech", true).put("microphone_or_audio_opened", false)
                .put("operator_visual_review_required", true)
            latestDetails = value
            File(directory, "$phase.json").writeText(value.toString(2))
            val temporary = File(directory, "phase.tmp")
            temporary.writeText(value.toString(2))
            check(temporary.renameTo(File(directory, "phase.json")))
        }
        suspend fun awaitControl(command: String, timeoutMillis: Long, whileWaiting: suspend () -> Unit = {}) {
            val control = File(directory, "$command.control")
            val consumed = File(directory, "$command.consumed")
            check(!consumed.exists()) { "Control was already consumed" }
            withTimeout(timeoutMillis) {
                while (!control.exists()) {
                    whileWaiting()
                    delay(100)
                }
                whileWaiting()
                check(control.isFile && control.length() in 1L..128L)
                check(control.canonicalFile.parentFile == directory.canonicalFile)
                check(control.readText().trim() == "$command $probeId") { "Unexpected control content" }
                check(control.renameTo(consumed)) { "Could not consume the one-time control" }
            }
        }
        try {
            withRun("prepared_split", optInArgument = "run_prepared_split_probe", splitProbe = true) { run, activity ->
                withTimeout(180_000) {
                    suspend fun awaitReadySize(width: Int, height: Int) = withTimeout(10_000) {
                        while (true) {
                            val ready = withContext(Dispatchers.Main) {
                                val state = activity.model.state.value
                                state.phase == Phase.READY &&
                                    state.uxKnown &&
                                    state.canSetup &&
                                    activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                                    !activity.isFinishing &&
                                    !activity.isDestroyed &&
                                    activity.window.decorView.isShown &&
                                    activity.window.decorView.width == width &&
                                    activity.window.decorView.height == height
                            }
                            if (ready) break
                            delay(25)
                        }
                    }
                    suspend fun snapshot(expectedPhase: Phase, expectedWidth: Int, stoppedAtStart: Int? = null): JSONObject =
                        withContext(Dispatchers.Main) {
                            val state = activity.model.state.value
                            val view = activity.window.decorView
                            assertTrue(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
                            assertFalse(activity.isFinishing || activity.isDestroyed)
                            assertTrue(view.isShown)
                            assertEquals(expectedPhase, state.phase)
                            assertTrue(state.paired && state.uxKnown && state.canSetup && !state.driving)
                            assertEquals(expectedWidth, view.width)
                            assertEquals(850, view.height)
                            if (stoppedAtStart != null) {
                                assertEquals(
                                    "The active split conversation must stay visible",
                                    stoppedAtStart,
                                    run.events.count {
                                        it ==
                                            "activity_stopped"
                                    }
                                )
                                assertEquals(1, run.speech.listenCount)
                            }
                            assertEquals(0, run.speech.speakCount)
                            val location = IntArray(2)
                            view.getLocationOnScreen(location)
                            JSONObject().put("qa_run_id", run.id).put("conversation_phase", state.phase.name)
                                .put("lifecycle", activity.lifecycle.currentState.name)
                                .put("app_x_px", location[0]).put("app_y_px", location[1])
                                .put("app_width_px", view.width).put("app_height_px", view.height)
                                .put("activity_starts", run.events.count { it == "activity_started" })
                                .put("activity_stops", run.events.count { it == "activity_stopped" })
                                .put("can_setup", state.canSetup).put("can_show_transcript", state.canShowTranscript)
                        }
                    awaitReadySize(1256, 850)
                    check(!File(directory, "start.control").exists() && !File(directory, "finish.control").exists())
                    writePhase("awaiting_split_ready", snapshot(Phase.READY, 1256))
                    awaitControl("start", 100_000)
                    check(!File(directory, "finish.control").exists()) { "Finish cannot precede active listening" }
                    awaitReadySize(628, 850)
                    snapshot(Phase.READY, 628)
                    assertEquals(0, run.speech.listenCount)
                    val stopsAtStart = run.events.count { it == "activity_stopped" }
                    withContext(Dispatchers.Main) { activity.model.start() }
                    awaitPhase(activity, Phase.LISTENING)
                    check(!File(directory, "finish.control").exists()) { "Finish must follow the listening observation" }
                    writePhase(
                        "awaiting_split_finish",
                        snapshot(Phase.LISTENING, 628, stopsAtStart)
                            .put("controls_for_visual_review", JSONArray(listOf("잠시 멈춤", "여기까지 기록")))
                    )
                    awaitControl("finish", 60_000) { snapshot(Phase.LISTENING, 628, stopsAtStart) }
                    withContext(Dispatchers.Main) { activity.model.finish() }
                    awaitPhase(activity, Phase.SAVED)
                    assertEquals(1, run.api.calls.count { it.operation == "finish" })
                    assertTrue(run.database.outbox().all().isEmpty())
                    assertNull(activity.model.state.value.sessionId)
                    assertEquals(0, activity.model.state.value.pendingCount)
                    writePhase("saved_observed", snapshot(Phase.SAVED, 628, stopsAtStart))
                }
            }
            // withRun returns only after SAVED assertions, Activity teardown, namespace cleanup, and its report.
            writePhase("completed")
        } catch (error: Throwable) {
            runCatching { writePhase("failed", JSONObject().put("error_kind", error.javaClass.simpleName)) }
                .onFailure(error::addSuppressed)
            throw error
        }
    }

    private suspend fun withRun(
        name: String,
        prepare: (QaConversationRun) -> Unit = {},
        optInArgument: String = "run_synthetic_lifecycle",
        drivingProbe: Boolean = false,
        splitProbe: Boolean = false,
        body: suspend (QaConversationRun, QaConversationActivity) -> Unit
    ) {
        assumeTrue("Explicit opt-in required", InstrumentationRegistry.getArguments().getString(optInArgument) == "true")
        assertQaTarget()
        val run = QaConversationRun.create(target)
        var activity: QaConversationActivity? = null
        var failure: Throwable? = null
        try {
            prepare(run)
            activity = instrumentation.startActivitySync(
                Intent(target, QaConversationActivity::class.java)
                    .putExtra(QaConversationActivity.EXTRA_RUN, run.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as QaConversationActivity
            withTimeout(10_000) { activity.model.state.filter { it.uxKnown && it.paired && it.phase == Phase.READY }.first() }
            assertTrue("Actual vehicle Park/setup permission is required", activity.model.state.value.canSetup)
            body(run, activity)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            var cleanupFailure: Throwable? = null
            fun recordCleanupError(error: Throwable) {
                val previous = cleanupFailure
                if (previous == null) cleanupFailure = error else previous.addSuppressed(error)
            }
            try {
                activity?.let { host ->
                    instrumentation.runOnMainSync { host.finish() }
                    awaitCondition { run.events.contains("activity_destroyed") }
                }
            } catch (error: Throwable) {
                recordCleanupError(error)
            }
            try {
                run.dispose()
            } catch (error: Throwable) {
                recordCleanupError(error)
            }
            try {
                val directory = File(target.filesDir, "synthetic-lifecycle/${run.id}").apply { mkdirs() }
                File(directory, "report.json").writeText(
                    JSONObject().put("case", name).put("passed", failure == null && cleanupFailure == null)
                        .put("cleanup_succeeded", cleanupFailure == null)
                        .put("actual_activity_lifecycle", true).put("synthetic_speech", true).put("fake_api", true)
                        .put("microphone_or_audio_opened", false).put("real_server_validated", false)
                        .put("process_death_validated", false).put("split_screen_validated", false)
                        .put("split_window_bounds_validated", splitProbe && failure == null && cleanupFailure == null)
                        .put("driving_transition_validated", drivingProbe && failure == null && cleanupFailure == null)
                        .put("activity_events", JSONArray(run.events))
                        .put("api_events", JSONArray(run.api.calls.map { it.operation })).toString(2)
                )
            } catch (error: Throwable) {
                recordCleanupError(error)
            }
            cleanupFailure?.let { error ->
                failure?.addSuppressed(error) ?: throw error
            }
        }
    }

    private suspend fun cover(activity: QaConversationActivity): Activity {
        val before = activity.run.events.count { it == "activity_stopped" }
        val cover = instrumentation.startActivitySync(
            Intent(target, SdkVerificationActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        try {
            awaitCondition { activity.run.events.count { it == "activity_stopped" } > before }
            return cover
        } catch (error: Throwable) {
            instrumentation.runOnMainSync { cover.finish() }
            throw error
        }
    }

    private suspend fun uncover(activity: QaConversationActivity, cover: Activity) {
        val before = activity.run.events.count { it == "activity_started" }
        instrumentation.runOnMainSync { cover.finish() }
        awaitCondition { activity.run.events.count { it == "activity_started" } > before }
    }

    private suspend fun awaitPhase(activity: QaConversationActivity, phase: Phase) {
        withTimeout(10_000) { activity.model.state.filter { it.phase == phase }.first() }
    }

    private suspend fun awaitCondition(predicate: () -> Boolean) {
        withTimeout(10_000) { while (!predicate()) delay(10) }
    }

    private fun assertQaTarget() {
        assertEquals("app.doran.car.debug", target.packageName)
        assertTrue(target.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        assertTrue(target.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE))
        assertTrue(Build.FINGERPRINT.contains("avd", true) || Build.HARDWARE in setOf("ranchu", "goldfish"))
    }
}
