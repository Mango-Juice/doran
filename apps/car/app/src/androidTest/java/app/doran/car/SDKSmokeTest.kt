package app.doran.car

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.doran.car.data.SecureStorage
import app.doran.car.speech.PleosSpeech
import app.doran.car.speech.SpeechFailure
import app.doran.car.vehicle.DrivingRestrictions
import app.doran.car.vehicle.PleosVehicleContext
import app.doran.car.vehicle.SignalStatus
import app.doran.car.vehicle.VehicleContext
import app.doran.car.vehicle.VehicleSignal
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Diagnostic instrumentation only: no microphone request, cloud request, or production hooks. */
@RunWith(AndroidJUnit4::class)
class SDKSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext
    private val arguments = InstrumentationRegistry.getArguments()

    @Test
    fun captureRealSdkEvidence() = runBlocking {
        assertTrue("Use the dedicated debug package", target.packageName.endsWith(".debug"))
        assertTrue("Refuse a non-debuggable target", target.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        assertTrue(
            "This diagnostic only runs on an Automotive emulator",
            target.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE) &&
                (Build.FINGERPRINT.contains("avd", true) || Build.HARDWARE in setOf("ranchu", "goldfish"))
        )
        val secure = SecureStorage(target)
        assertTrue(
            "Use a clean debug installation without a paired owner",
            secure.get("owner_id") == null && secure.get("active_session") == null
        )
        val rounds = arguments.getString("speech_rounds", "1").toInt().coerceIn(1, 20)
        val report = JSONObject().put("started_at", Instant.now().toString())
            .put("package", target.packageName).put("fingerprint", Build.FINGERPRINT)
            .put("sdk_int", Build.VERSION.SDK_INT).put("requested_speech_rounds", rounds)
            .put("input_scope", "Emulator SDK signals and synthetic PCM; human microphone, cloud AI, physical output not verified")
        val output = File(target.filesDir, "sdk-verification.json")
        var activity: Activity? = null
        try {
            activity = instrumentation.startActivitySync(
                Intent(target, SdkVerificationActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            instrumentation.waitForIdleSync()
            report.put("foreground_host", "debug-only SdkVerificationActivity; no application bootstrap")
            val initiallyGranted = target.checkSelfPermission(PleosVehicleContext.LOCATION_PERMISSION) == PackageManager.PERMISSION_GRANTED
            report.put("location_initially_granted", initiallyGranted)
            if (!initiallyGranted) {
                report.put("permission_denied_phase", captureContext())
                assertEquals(
                    SignalStatus.PERMISSION_REQUIRED.name,
                    report.getJSONObject("permission_denied_phase")
                        .getJSONObject("final").getJSONObject("location").getString("status")
                )
            } else {
                report.put(
                    "permission_denied_phase",
                    JSONObject().put("status", "NOT_RUN_ALREADY_GRANTED")
                        .put("note", "Install a fresh debug test profile or revoke permission before instrumenting to test denial safely")
                )
            }
            report.put("location_permission", grantLocationPermission())
            report.put("context", captureContext())
            report.put("vehicle_capabilities", captureVehicleCapabilities())
            report.put("speech", captureSpeech(rounds))
            report.put("harness_completed", true)
            // Harness completion proves evidence capture and cleanup, not that every SDK was supported.
            assertTrue(report.has("context") && report.has("vehicle_capabilities") && report.has("speech"))
            for (name in listOf("driving_time", "driving_distance", "seat_occupancy")) {
                assertTrue("Missing capability evidence: $name", report.getJSONObject("vehicle_capabilities").has(name))
            }
        } finally {
            activity?.let { host -> instrumentation.runOnMainSync { host.finish() } }
            report.put("finished_at", Instant.now().toString())
            output.writeText(report.toString(2))
        }
    }

    private fun grantLocationPermission(): JSONObject = try {
        instrumentation.uiAutomation.grantRuntimePermission(target.packageName, PleosVehicleContext.LOCATION_PERMISSION)
        val granted = target.checkSelfPermission(PleosVehicleContext.LOCATION_PERMISSION) == PackageManager.PERMISSION_GRANTED
        JSONObject().put("result", if (granted) "GRANTED_FOR_TEST" else "NOT_GRANTED")
    } catch (error: Exception) {
        JSONObject().put("result", "NOT_GRANTED").put("error_class", error.javaClass.simpleName)
    }

    private suspend fun captureContext(): JSONObject = withContext(Dispatchers.Main) {
        val vehicle = PleosVehicleContext(target)
        val restrictions = DrivingRestrictions(target)
        val trace = JSONArray()
        var collector: Job? = null
        val result = JSONObject()
        try {
            vehicle.syncMemoryRevision(0)
            vehicle.start(consented = true)
            restrictions.start()
            collector = CoroutineScope(coroutineContext).launch {
                vehicle.state.collect { state ->
                    if (trace.length() < 80) trace.put(contextSnapshot(state))
                }
            }
            val settled = withTimeoutOrNull(20_000) {
                vehicle.state.filter { state ->
                    listOf(
                        state.location.status,
                        state.currentDrivingSeconds.status,
                        state.currentDrivingMeters.status,
                        state.occupiedPassengerSeat.status,
                        state.navigationStatus
                    ).none { it == SignalStatus.WAITING }
                }.first()
            }
            delay(2_000)
            val snapshot = vehicle.state.value
            val driving = restrictions.state.value
            result.put("settled_before_timeout", settled != null)
                .put("final", contextSnapshot(snapshot)).put("state_observations", trace)
                .put(
                    "native_car_ux",
                    JSONObject().put("known", driving.known).put("requires_do", driving.driving)
                        .put("active_restrictions", driving.flags).put("can_setup", driving.canSetup)
                )
                .put("navigation_arrival_observed", snapshot.arrival.status == SignalStatus.AVAILABLE)
                .put("navigation_note", "AVAILABLE navigationStatus proves SDK connection only; no arrival claim without actual callback")
        } finally {
            collector?.cancelAndJoin()
            vehicle.stop()
            restrictions.close()
            val cleared = vehicle.state.value == VehicleContext()
            result.put("cleared_after_stop", cleared)
            assertTrue("SDK stop must clear all optional context", cleared)
        }
        result
    }

    private fun contextSnapshot(value: VehicleContext): JSONObject {
        fun signal(value: VehicleSignal<*>, includeValue: Boolean = true): JSONObject = JSONObject()
            .put("status", value.status.name).put("source", value.source)
            .put("measured_at", value.measuredAtMillis?.let { Instant.ofEpochMilli(it).toString() } ?: JSONObject.NULL)
            .put("value", if (includeValue) value.value ?: JSONObject.NULL else JSONObject.NULL)
        return JSONObject().put("captured_at", Instant.now().toString())
            .put("location", signal(value.location, includeValue = false))
            .put("trip_duration_seconds", signal(value.currentDrivingSeconds))
            .put("trip_distance_m", signal(value.currentDrivingMeters))
            .put("seat_privacy_gate", signal(value.occupiedPassengerSeat))
            .put("passenger_state", value.passengerState())
            .put("navigation_connection", value.navigationStatus.name)
            .put(
                "navigation_route",
                when {
                    value.tripKey != null -> "ACTIVE_ROUTE_OBSERVED"
                    value.navigationStatus != SignalStatus.AVAILABLE -> "CONNECTION_UNAVAILABLE_OR_PENDING"
                    value.remainingSeconds.status == SignalStatus.WAITING -> "CONNECTED_ROUTE_STATE_UNOBSERVED"
                    else -> "CONNECTED_NO_ACTIVE_ROUTE_OBSERVED"
                }
            )
            .put("destination_eta_seconds", signal(value.remainingSeconds))
            .put("arrival", signal(value.arrival, includeValue = false))
    }

    private suspend fun captureVehicleCapabilities(): JSONObject = withContext(Dispatchers.Main) {
        val sdk = Vehicle(target)
        val report = JSONObject()
        try {
            sdk.initialize()
            val odo = sdk.getOdometer()
            report.put(
                "driving_time",
                probe {
                    val capability =
                        awaitResult<CapabilityConfig> { success, failure -> odo.checkTripCurrentDrivingTimeCapabilities(success, failure) }
                    val value = if (readable(capability)) {
                        awaitResult<Int?> { success, failure ->
                            odo.getTripCurrentDrivingTime(success, failure)
                        }
                    } else {
                        null
                    }
                    JSONObject().put("status", if (readable(capability)) "READ_SUPPORTED" else "UNSUPPORTED")
                        .put("access_type", capability.accessType.name).put("raw_minutes", value ?: JSONObject.NULL)
                        .put("source", "Vehicle.getTripCurrentDrivingTime").put("get_callback_count", if (readable(capability)) 1 else 0)
                }
            )
            report.put(
                "driving_distance",
                probe {
                    val capability =
                        awaitResult<CapabilityConfig> { success, failure ->
                            odo.checkTripCurrentDrivingDistanceCapabilities(success, failure)
                        }
                    val value = if (readable(capability)) {
                        awaitResult<Float?> { success, failure ->
                            odo.getTripCurrentDrivingDistance(success, failure)
                        }
                    } else {
                        null
                    }
                    JSONObject().put("status", if (readable(capability)) "READ_SUPPORTED" else "UNSUPPORTED")
                        .put("access_type", capability.accessType.name).put("raw_meters", value ?: JSONObject.NULL)
                        .put(
                            "source",
                            "Vehicle.getTripCurrentDrivingDistance"
                        ).put("get_callback_count", if (readable(capability)) 1 else 0)
                }
            )
            report.put(
                "seat_occupancy",
                probe {
                    val seat = sdk.getSeat()
                    val capability = awaitResult<Pair<CapabilityConfig, List<SeatArea>>> { success, failure ->
                        seat.checkSeatOccupancyCapabilities({ config: CapabilityConfig, areas: List<SeatArea> ->
                            success(config to areas)
                        }, failure)
                    }
                    val areas = JSONArray()
                    if (readable(capability.first)) {
                        for (area in capability.second.take(9)) {
                            val observed = probe {
                                val value =
                                    awaitResult<Boolean?> { success, failure -> seat.getCurrentSeatOccupancy(area, success, failure) }
                                JSONObject().put(
                                    "status",
                                    if (value ==
                                        null
                                    ) {
                                        "NO_VALUE"
                                    } else {
                                        "VALUE_RECEIVED"
                                    }
                                ).put("occupied", value ?: JSONObject.NULL).put("get_callback_count", 1)
                            }
                            areas.put(observed.put("areas", JSONArray(area.values.map { it.name })))
                        }
                    }
                    JSONObject().put("status", if (readable(capability.first)) "READ_SUPPORTED" else "UNSUPPORTED")
                        .put("access_type", capability.first.accessType.name).put("areas", areas)
                        .put("source", "Vehicle.checkSeatOccupancyCapabilities/getCurrentSeatOccupancy")
                }
            )
        } catch (error: Exception) {
            report.put("initialization", failed(error))
            for (name in listOf("driving_time", "driving_distance", "seat_occupancy")) {
                if (!report.has(name)) report.put(name, failed(error).put("status", "NOT_RUN_INITIALIZATION_FAILED"))
            }
        } finally {
            runCatching { sdk.release() }
        }
        report
    }

    private suspend fun captureSpeech(rounds: Int): JSONObject {
        val fixtures = listOf("THANKS-190.pcm" to "감사합니다", "SORRY-190.pcm" to "미안합니다")
        val cases = JSONArray()
        val interruptions = AtomicInteger()
        val mainSamples = AtomicInteger()
        val mainGapsOver500 = AtomicInteger()
        val maxMainGap = AtomicLong()
        val speech = withContext(Dispatchers.Main) { PleosSpeech(target) { interruptions.incrementAndGet() } }
        val mainProbe = CoroutineScope(Dispatchers.Main).launch {
            var previous = SystemClock.elapsedRealtime()
            while (isActive) {
                delay(100)
                val now = SystemClock.elapsedRealtime()
                val gap = now - previous
                previous = now
                mainSamples.incrementAndGet()
                maxMainGap.updateAndGet { maxOf(it, gap) }
                if (gap > 500) mainGapsOver500.incrementAndGet()
            }
        }
        try {
            repeat(rounds) { index ->
                val (name, expected) = fixtures[index % fixtures.size]
                val case = JSONObject().put("index", index + 1).put("fixture", name).put("expected_text", expected)
                val bytes = runCatching { instrumentation.context.assets.open("sdk_voice/$name").use { it.readBytes() } }.getOrNull()
                if (bytes == null) {
                    case.put("stt", JSONObject().put("status", "SKIPPED_FIXTURE_MISSING"))
                } else {
                    case.put("fixture_sha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
                    val started = SystemClock.elapsedRealtime()
                    val stt = probe { recognizePcm(bytes) }
                    val recognized = stt.optString("final_text")
                    stt.put("elapsed_ms", SystemClock.elapsedRealtime() - started)
                        .put("expected_match", normalize(recognized) == normalize(expected))
                    case.put("stt", stt)
                }
                val started = SystemClock.elapsedRealtime()
                val startedAt = Instant.now().toString()
                val phrase = "도란 음성 출력 점검입니다. $expected"
                val tts = probe {
                    withTimeout(65_000) { speech.speak(phrase) }
                    JSONObject().put("status", "SDK_ON_DONE_RECEIVED")
                        .put(
                            "evidence",
                            "Production PleosSpeech.speak returned after real SDK onDone; physical speaker audibility unverified"
                        )
                }
                val finished = SystemClock.elapsedRealtime()
                case.put(
                    "tts",
                    tts.put("elapsed_ms", finished - started)
                        .put("call_started_at", startedAt)
                        .put("call_started_elapsed_realtime_ms", started)
                        .put("call_returned_elapsed_realtime_ms", finished)
                        .put("utterance_character_count", phrase.length)
                        .put("rate_setting", "SDK default; TextToSpeech 3.3.1 exposes no public rate setter")
                        .put("callback_timing_source", "DoranSpeech and Pleos SDK logcat; this object measures the suspended call")
                )
                cases.put(case)
                delay(400)
            }
        } finally {
            withContext(Dispatchers.Main) { speech.pause() }
            mainProbe.cancelAndJoin()
        }
        return JSONObject().put("mode", "ON_DEVICE").put("microphone_requested", false)
            .put("source", "Local synthetic 16kHz signed16LE mono PCM")
            .put("cases", cases).put("audio_focus_interruptions", interruptions.get())
            .put(
                "main_looper_probe",
                JSONObject().put("requested_interval_ms", 100).put("sample_count", mainSamples.get())
                    .put("maximum_observed_gap_ms", maxMainGap.get())
                    .put("gaps_over_500_ms", mainGapsOver500.get())
                    .put("note", "Coroutine scheduling gaps include emulator CPU contention; not proof of a blocking application call")
            )
    }

    private suspend fun recognizePcm(bytes: ByteArray): JSONObject = withContext(Dispatchers.Main) {
        val sdk = SpeechToText(target, ai.pleos.playground.stt.constant.Mode.ON_DEVICE)
        var listener: ResultListener? = null
        var sender: Job? = null
        val events = JSONArray()
        val sentBytes = AtomicInteger()
        val started = SystemClock.elapsedRealtime()
        fun event(name: String) {
            synchronized(events) {
                val now = SystemClock.elapsedRealtime()
                events.put(
                    JSONObject().put("name", name).put("elapsed_realtime_ms", now)
                        .put("since_call_ms", now - started)
                )
            }
        }
        event("call_started")
        val result = try {
            withTimeout(25_000) {
                suspendCancellableCoroutine<JSONObject> { continuation ->
                    val completed = AtomicBoolean(false)
                    fun complete(result: JSONObject) {
                        if (continuation.isActive && completed.compareAndSet(false, true)) {
                            continuation.resume(result)
                        }
                    }
                    val resultListener = object : ResultListener {
                        override fun onReady() {
                            event("onReady")
                        }
                        override fun onStartedRecognition() {
                            event("onStartedRecognition")
                        }
                        override fun onEndedRecognition() {
                            event("onEndedRecognition")
                        }
                        override fun onUpdated(text: String, completed: Boolean) {
                            if (completed && continuation.isActive) {
                                event("final")
                                complete(JSONObject().put("status", "FINAL_RECEIVED").put("final_text", text))
                            }
                        }
                        override fun onUpdatedEpdData(on: Long, off: Long) = Unit
                        override fun onUpdatedRms(rms: Double) = Unit
                        override fun onError(errorType: ai.pleos.playground.stt.constant.ErrorType) {
                            event("sdk_error")
                            complete(JSONObject().put("status", "SDK_ERROR").put("error_code", errorType.name))
                        }
                    }
                    listener = resultListener
                    event("initialize_called")
                    sdk.initialize(object : ai.pleos.playground.stt.listener.OnInitializeListener {
                        private val configured = AtomicBoolean(false)
                        override fun onSuccess() {
                            if (!continuation.isActive || !configured.compareAndSet(false, true)) return
                            event("initialized")
                            sender = CoroutineScope(continuation.context).launch(Dispatchers.IO) {
                                try {
                                    sdk.setLocale(Locale.KOREA)
                                    sdk.addListener(resultListener)
                                    event("locale_and_listener_configured")
                                    // sendAudio starts SDK recognition from PCM; request() is NEVER called.
                                    var offset = 0
                                    while (offset < bytes.size && continuation.isActive) {
                                        val end = minOf(offset + 3200, bytes.size)
                                        if (offset == 0) event("pcm_first_send_called")
                                        sdk.sendAudio(bytes.copyOfRange(offset, end))
                                        sentBytes.addAndGet(end - offset)
                                        offset = end
                                        if (offset == bytes.size) event("pcm_last_send_returned")
                                        delay(100)
                                    }
                                    if (continuation.isActive) {
                                        event("complete_audio_send_called")
                                        sdk.completeAudioSend()
                                        event("complete_audio_send_returned")
                                    }
                                } catch (error: Exception) {
                                    event("sender_error")
                                    if (continuation.isActive &&
                                        completed.compareAndSet(false, true)
                                    ) {
                                        continuation.resumeWithException(error)
                                    }
                                }
                            }
                        }
                        override fun onFailure(errorType: ai.pleos.playground.stt.constant.ErrorType) {
                            event("initialize_failed")
                            complete(JSONObject().put("status", "INIT_FAILED").put("error_code", errorType.name))
                        }
                    })
                    event("initialize_returned")
                    continuation.invokeOnCancellation { sender?.cancel() }
                }
            }
        } catch (error: Exception) {
            event(if (error is TimeoutCancellationException) "timeout" else "exception")
            failed(error)
        } finally {
            sender?.cancelAndJoin()
            listener?.let { runCatching { sdk.removeListener(it) } }
            runCatching { sdk.stop() }
            runCatching { sdk.release() }
        }
        event("cleanup_calls_returned")
        val snapshot = synchronized(events) { JSONArray(events.toString()) }
        val milestones = (0 until snapshot.length()).associate { index ->
            val entry = snapshot.getJSONObject(index)
            entry.getString("name") to entry.getLong("elapsed_realtime_ms")
        }
        fun span(from: String, to: String): Any {
            val first = milestones[from] ?: return JSONObject.NULL
            val last = milestones[to] ?: return JSONObject.NULL
            return last - first
        }
        result.put("events", snapshot).put("fixture_bytes", bytes.size).put("sent_bytes", sentBytes.get())
            .put("pcm_duration_ms", bytes.size / 32.0)
            .put("chunk_size_bytes", 3200).put("delay_after_each_chunk_ms", 100)
            .put("initialize_to_success_ms", span("initialize_called", "initialized"))
            .put("feed_until_complete_call_ms", span("pcm_first_send_called", "complete_audio_send_called"))
            .put("complete_call_to_final_ms", span("complete_audio_send_called", "final"))
            .put("complete_return_to_final_ms", span("complete_audio_send_returned", "final"))
            .put("recognition_started_to_final_ms", span("onStartedRecognition", "final"))
            .put("timing_note", "SDK calls return before service processing is acknowledged; compare SDK logcat for Binder milestones")
    }

    private suspend fun <T> awaitResult(block: ((T) -> Unit, (Exception) -> Unit) -> Unit): T = withTimeout(10_000) {
        suspendCancellableCoroutine { continuation ->
            block(
                { value -> if (continuation.isActive) continuation.resume(value) },
                { error -> if (continuation.isActive) continuation.resumeWithException(error) }
            )
        }
    }

    private suspend fun probe(block: suspend () -> JSONObject): JSONObject = try {
        block()
    } catch (error: Exception) {
        failed(error)
    }

    private fun failed(error: Exception): JSONObject = JSONObject()
        .put("status", if (error is TimeoutCancellationException) "TIMEOUT" else "ERROR")
        .put("error_class", error.javaClass.simpleName)
        .put("error_code", (error as? SpeechFailure)?.kind ?: JSONObject.NULL)

    private fun readable(capability: CapabilityConfig): Boolean = capability.accessType in setOf(AccessType.READ, AccessType.READ_WRITE)
    private fun normalize(text: String): String = text.replace(Regex("[^가-힣]"), "")
}
