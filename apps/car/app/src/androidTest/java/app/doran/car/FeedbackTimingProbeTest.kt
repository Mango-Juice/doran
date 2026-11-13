package app.doran.car

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.Display
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.doran.car.vehicle.DrivingRestrictions
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One synthetic tap on the existing RELEASE UI; no Activity launch, pairing confirmation, or reset.
 * All screenshots (which may contain pairing codes) remain in the debug target's private files.
 * Arguments: unpaired_test=true, parked_gear_p_speed_zero=true, expected_release_sha256=<64 hex>.
 * The parked argument records the operator's prior Virtual Console observation, not a sensor read.
 * Run only this test class, with an external instrumentation timeout: framework Binder calls cannot
 * be forcibly cancelled here. The capture deadline is checked between calls, never a hard watchdog.
 * JUnit completion means evidence collection completed; it never means the 250 ms target passed.
 */
@RunWith(AndroidJUnit4::class)
class FeedbackTimingProbeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation

    @Test
    fun captureOneReleasePairingFeedback(): Unit = runBlocking {
        assertTrue("Instrumentation must target the isolated debug package", target.packageName == "$RELEASE_PACKAGE.debug")
        assertTrue("Refuse a non-debug instrumentation target", target.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)

        val directory = File(target.filesDir, "feedback-timing/${SystemClock.uptimeMillis()}")
        val frames = mutableListOf<CapturedFrame>()
        var storageBounds: Rect? = null
        var originalServiceFlags: Int? = null
        val events = JSONArray()
        val report = JSONObject()
            .put("schema_version", 1)
            .put("status", "PREFLIGHT")
            .put("measurement_verdict", "NOT_EVALUATED")
            .put("instrumentation_package", target.packageName)
            .put("observed_package", RELEASE_PACKAGE)
            .put("device_fingerprint", Build.FINGERPRINT)
            .put("android_api", Build.VERSION.SDK_INT)
            .put("clock", "Android SystemClock.uptimeMillis; all event/capture times are milliseconds")
            .put("started_uptime_ms", SystemClock.uptimeMillis())
            .put("events", events)
            .put("max_capture_calls_including_baseline", MAX_CAPTURE_CALLS)
            .put("post_down_capture_budget_ms", CAPTURE_BUDGET_MS)
            .put("feedback_target_ms", 250)
            .put("automatic_performance_pass", false)
            .put("tap_attempts", 0)
            .put("image_scope", "Display bitmaps are acquired in memory; only the preflight release-window crop is saved after measurement")
            .put(
                "manual_review",
                "Find a frame containing both the connecting title and disabled connection button; pixel difference alone is not evidence"
            )
            .put("expected_feedback_title", "연결을 확인하고 있어요")
            .put(
                "limits",
                JSONArray(
                    listOf(
                        "Synthetic Android input and guest display screenshots; not human input or physical host display presentation timing",
                        "Capture-return minus eventTime is an observation upper bound, not the frame's presentation timestamp",
                        "Input injection may wait for window/surface work; success does not prove the intended button handled the tap",
                        "Capture calls, polling gaps, and observer load can miss a transient state; no matching frame is inconclusive",
                        "The deadline is checked between framework calls; an external instrumentation timeout is still required",
                        "One sample only; no population percentile or general 250 ms guarantee"
                    )
                )
            )
        val restrictions = DrivingRestrictions(target)
        var stop: PreflightStop? = null
        var failure: Throwable? = null
        try {
            val arguments = InstrumentationRegistry.getArguments()
            preflight(arguments.getString("unpaired_test") == "true", "EXPLICIT_UNPAIRED_TEST_ARGUMENT_REQUIRED")
            preflight(arguments.getString("parked_gear_p_speed_zero") == "true", "OPERATOR_PARKED_OBSERVATION_ARGUMENT_REQUIRED")
            report.put(
                "operator_preconditions",
                JSONObject()
                    .put("unpaired_test_confirmed", true)
                    .put("virtual_console_gear_P_speed_zero_confirmed", true)
                    .put("source", "Explicit operator arguments; not independently measured gear or speed")
            )
            preflight(
                Build.VERSION.SDK_INT == 34 &&
                    target.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE) &&
                    (Build.HARDWARE in setOf("ranchu", "goldfish") || Build.FINGERPRINT.contains("avd", true)),
                "API34_AUTOMOTIVE_EMULATOR_REQUIRED"
            )
            val expectedHash = arguments.getString("expected_release_sha256").orEmpty().lowercase()
            preflight(expectedHash.matches(Regex("[a-f0-9]{64}")), "EXPECTED_RELEASE_SHA256_REQUIRED")
            verifyReleaseApk(expectedHash, report)
            val serviceInfo = automation.serviceInfo
            originalServiceFlags = serviceInfo.flags
            serviceInfo.flags = serviceInfo.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            automation.serviceInfo = serviceInfo

            withContext(Dispatchers.Main) { restrictions.start() }
            val nativeUx = withTimeoutOrNull(5_000) { restrictions.state.filter { it.known }.first() }
            report.put(
                "native_ux_preflight",
                JSONObject()
                    .put("observed_uptime_ms", SystemClock.uptimeMillis())
                    .put("known", nativeUx?.known == true)
                    .put("can_setup", nativeUx?.canSetup == true)
                    .put("flags", nativeUx?.flags ?: JSONObject.NULL)
                    .put("scope", "Fresh OEM UX setup permission; not an independent gear=P observation")
            )
            preflight(nativeUx?.canSetup == true, "NATIVE_UX_SETUP_NOT_ALLOWED")
            val initialButton = requireReleaseButton()
            storageBounds = Rect(initialButton.windowBounds)
            report.put("initial_button", initialButton.toJson())
            val baseline = capture("baseline")
            frames.add(baseline)
            preflight(baseline.bitmap != null, "BASELINE_SCREENSHOT_UNAVAILABLE")
            val baselineBitmap = baseline.bitmap!!
            val screen = Rect(0, 0, baselineBitmap.width, baselineBitmap.height)

            // Coordinates have no package binding: re-check the active release window immediately
            // before input. Never find an approximate label, retry a tap, or bring an Activity forward.
            val button = requireReleaseButton()
            preflight(button == initialButton && screen.contains(button.windowBounds), "BUTTON_OR_WINDOW_CHANGED_DURING_PREFLIGHT")
            val justBeforeInput = restrictions.state.value
            preflight(justBeforeInput.known && justBeforeInput.canSetup, "NATIVE_UX_CHANGED_BEFORE_INPUT")
            report.put("button_at_input", button.toJson())
                .put("native_ux_rechecked_uptime_ms", SystemClock.uptimeMillis())
                .put("measurement_verdict", "PENDING_MANUAL_PIXEL_REVIEW")
                .put("tap_attempts", 1)
            val x = button.bounds.exactCenterX()
            val y = button.bounds.exactCenterY()
            val downTime = SystemClock.uptimeMillis()
            val tapDeadline = downTime + minOf(MAX_TAP_MS, (ViewConfiguration.getLongPressTimeout() - 1L).coerceAtLeast(1L))
            report.put("down_event_time_ms", downTime).put("tap_deadline_uptime_ms", tapDeadline)
            var gestureClosed = false
            try {
                preflight(inject(MotionEvent.ACTION_DOWN, downTime, downTime, x, y, events), "DOWN_INJECTION_FAILED", true)
                preflight(
                    SystemClock.uptimeMillis() < tapDeadline && restrictions.state.value.canSetup,
                    "DOWN_RETURNED_TOO_LATE_OR_UX_CHANGED"
                )
                SystemClock.sleep(TAP_HOLD_MS)
                preflight(requireReleaseButton() == button, "RELEASE_WINDOW_CHANGED_DURING_TAP")
                val eventTime = SystemClock.uptimeMillis()
                preflight(
                    eventTime < tapDeadline && restrictions.state.value.known && restrictions.state.value.canSetup,
                    "TAP_DEADLINE_OR_UX_CHANGED"
                )
                report.put("up_event_time_ms", eventTime)
                gestureClosed = inject(MotionEvent.ACTION_UP, downTime, eventTime, x, y, events)
                preflight(gestureClosed, "UP_INJECTION_FAILED", true)
            } finally {
                if (!gestureClosed) {
                    runCatching { inject(MotionEvent.ACTION_CANCEL, downTime, SystemClock.uptimeMillis(), x, y, events) }
                }
            }
            val deadline = downTime + CAPTURE_BUDGET_MS
            while (frames.size < MAX_CAPTURE_CALLS && SystemClock.uptimeMillis() < deadline) {
                val frame = capture("after_tap_${frames.size}")
                frames.add(frame)
                if (frame.bitmap == null) break
            }
            report.put(
                "capture_stop_reason",
                when {
                    frames.last().bitmap == null -> "SCREENSHOT_NULL"
                    frames.size == MAX_CAPTURE_CALLS -> "CAPTURE_COUNT_LIMIT"
                    else -> "CAPTURE_DEADLINE_REACHED"
                }
            )
            report.put(
                "status",
                if (frames.drop(1).any {
                        it.bitmap != null
                    }
                ) {
                    "CAPTURED_PENDING_MANUAL_REVIEW"
                } else {
                    "INCONCLUSIVE_NO_POST_INPUT_FRAME"
                }
            )
        } catch (error: PreflightStop) {
            stop = error
            report.put(
                "status",
                when {
                    error.isFailure -> "FAILED_PREFLIGHT_OR_INJECTION"
                    report.optInt("tap_attempts") > 0 -> "ABORTED_TAP"
                    else -> "SKIPPED_PREFLIGHT"
                }
            )
                .put("reason", error.reason)
        } catch (error: Exception) {
            failure = error
            report.put("status", "HARNESS_ERROR").put("error_class", error.javaClass.simpleName)
        } finally {
            // No PNG compression, file I/O, accessibility traversal, or pixel classification occurs
            // between the tap and the bounded screenshot loop.
            report.put("measurement_finished_uptime_ms", SystemClock.uptimeMillis())
            withContext(Dispatchers.Main) { restrictions.close() }
            originalServiceFlags?.let { flags ->
                runCatching {
                    val serviceInfo = automation.serviceInfo
                    serviceInfo.flags = flags
                    automation.serviceInfo = serviceInfo
                }
            }
            directory.mkdirs()
            val frameReports = JSONArray()
            try {
                for ((index, frame) in frames.withIndex()) {
                    val entry = JSONObject().put("sequence", index).put("role", frame.role)
                        .put("capture_call_started_uptime_ms", frame.startedAt)
                        .put("capture_returned_uptime_ms", frame.returnedAt)
                        .put("capture_call_duration_ms", frame.returnedAt - frame.startedAt)
                        .put("bitmap_available", frame.bitmap != null)
                        .put("capture_error_class", frame.errorClass ?: JSONObject.NULL)
                        .put("target_pixels_manually_verified", JSONObject.NULL)
                    if (report.has("down_event_time_ms") && index > 0) {
                        entry.put("capture_return_minus_down_ms", frame.returnedAt - report.getLong("down_event_time_ms"))
                        entry.put("capture_return_minus_up_ms", frame.returnedAt - report.getLong("up_event_time_ms"))
                    }
                    frame.bitmap?.let { bitmap ->
                        val bounds = storageBounds
                        if (bounds != null && Rect(0, 0, bitmap.width, bitmap.height).contains(bounds)) {
                            val cropped = Bitmap.createBitmap(bitmap, bounds.left, bounds.top, bounds.width(), bounds.height())
                            try {
                                val name = "frame-${index.toString().padStart(2, '0')}.png"
                                val saved = File(directory, name).outputStream().use {
                                    cropped.compress(Bitmap.CompressFormat.PNG, 100, it)
                                }
                                entry.put("private_png", name).put("png_saved", saved)
                                    .put("width", cropped.width).put("height", cropped.height)
                                    .put("source_crop_bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
                            } finally {
                                if (cropped !== bitmap) cropped.recycle()
                            }
                        } else {
                            entry.put("png_saved", false).put("storage_error", "RELEASE_WINDOW_OUTSIDE_CAPTURE")
                        }
                    }
                    frameReports.put(entry)
                }
                report.put("frames", frameReports)
                File(directory, "report.json").writeText(report.toString(2))
            } finally {
                frames.forEach { it.bitmap?.recycle() }
            }
        }
        assertTrue("Feedback probe failed; inspect its private report", failure == null && stop?.isFailure != true)
        assumeTrue("Feedback probe skipped: ${stop?.reason}", stop == null)
        Unit
    }

    private fun verifyReleaseApk(expectedHash: String, report: JSONObject) {
        val paths = shell("pm path --user current $RELEASE_PACKAGE").lineSequence()
            .filter { it.startsWith("package:") }.map { it.removePrefix("package:").trim() }.toList()
        preflight(paths.size == 1, "EXACTLY_ONE_RELEASE_APK_REQUIRED")
        val path = paths.single()
        preflight(
            path.startsWith("/data/app/") && path.endsWith("/base.apk") && path.matches(Regex("[A-Za-z0-9/_=+.~-]+")),
            "UNEXPECTED_RELEASE_APK_PATH"
        )
        // UiAutomation's command API is not a POSIX quoting contract. The path above
        // is already restricted to a no-whitespace, no-shell-metacharacter alphabet.
        val output = shell("sha256sum $path").trim()
        val actualHash = output.substringBefore(' ').lowercase()
        preflight(actualHash.matches(Regex("[a-f0-9]{64}")), "RELEASE_APK_HASH_UNAVAILABLE")
        report.put("expected_release_sha256", expectedHash).put("installed_release_sha256", actualHash)
        preflight(actualHash == expectedHash, "RELEASE_APK_SHA256_MISMATCH", true)
    }

    private fun requireReleaseButton(): ButtonObservation {
        automation.clearCache()
        val root = automation.rootInActiveWindow ?: throw PreflightStop("ACTIVE_ACCESSIBILITY_WINDOW_UNAVAILABLE")
        preflight(root.packageName?.toString() == RELEASE_PACKAGE, "ACTIVE_WINDOW_IS_NOT_RELEASE_APP")
        val window = root.window ?: throw PreflightStop("ACTIVE_WINDOW_INFO_UNAVAILABLE")
        preflight(
            window.displayId == Display.DEFAULT_DISPLAY &&
                window.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                window.isActive &&
                window.isFocused,
            "DEFAULT_DISPLAY_FOCUSED_APPLICATION_WINDOW_REQUIRED"
        )
        val rootBounds = Rect().also(root::getBoundsInScreen)
        val candidates = mutableListOf<ButtonObservation>()
        var visited = 0
        fun visit(node: AccessibilityNodeInfo) {
            visited++
            preflight(visited <= 500, "ACCESSIBILITY_TREE_LIMIT")
            val label = node.text?.toString().orEmpty()
            if (label in CONNECTION_LABELS && node.isVisibleToUser && node.isEnabled) {
                var clickable: AccessibilityNodeInfo? = node
                var depth = 0
                while (clickable != null && !clickable.isClickable && depth++ < 6) clickable = clickable.parent
                clickable?.let { button ->
                    val bounds = Rect().also(button::getBoundsInScreen)
                    if (button.packageName?.toString() == RELEASE_PACKAGE &&
                        button.isClickable &&
                        button.isEnabled &&
                        button.isVisibleToUser &&
                        !bounds.isEmpty &&
                        rootBounds.contains(bounds)
                    ) {
                        candidates.add(ButtonObservation(label, bounds, rootBounds, window.id, window.displayId))
                    }
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
        }
        visit(root)
        val unique = candidates.distinct()
        preflight(unique.size == 1, "UNIQUE_ENABLED_RELEASE_CONNECTION_BUTTON_REQUIRED")
        return unique.single()
    }

    private fun inject(action: Int, downTime: Long, eventTime: Long, x: Float, y: Float, events: JSONArray): Boolean {
        val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        val entry = JSONObject().put(
            "action",
            when (action) {
                MotionEvent.ACTION_DOWN -> "DOWN"
                MotionEvent.ACTION_UP -> "UP"
                else -> "CANCEL"
            }
        ).put("event_time_ms", event.eventTime).put("down_time_ms", event.downTime)
            .put("x_px", x).put("y_px", y).put("inject_call_started_uptime_ms", SystemClock.uptimeMillis())
        events.put(entry)
        return try {
            automation.injectInputEvent(event, true).also { entry.put("injection_succeeded", it) }
        } finally {
            entry.put("inject_call_returned_uptime_ms", SystemClock.uptimeMillis())
            event.recycle()
        }
    }

    private fun capture(role: String): CapturedFrame {
        val startedAt = SystemClock.uptimeMillis()
        return try {
            val bitmap = automation.takeScreenshot()
            CapturedFrame(role, startedAt, SystemClock.uptimeMillis(), bitmap, null)
        } catch (error: Exception) {
            CapturedFrame(role, startedAt, SystemClock.uptimeMillis(), null, error.javaClass.simpleName)
        }
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
        .bufferedReader().use { it.readText() }

    private fun preflight(condition: Boolean, reason: String, failure: Boolean = false) {
        if (!condition) throw PreflightStop(reason, failure)
    }

    private data class CapturedFrame(
        val role: String,
        val startedAt: Long,
        val returnedAt: Long,
        val bitmap: Bitmap?,
        val errorClass: String?
    )

    private data class ButtonObservation(
        val label: String,
        val bounds: Rect,
        val windowBounds: Rect,
        val windowId: Int,
        val displayId: Int
    ) {
        fun toJson(): JSONObject = JSONObject().put("label", label).put("enabled", true).put("clickable", true)
            .put("package", RELEASE_PACKAGE).put("bounds", rectJson(bounds)).put("window_bounds", rectJson(windowBounds))
            .put("window_id", windowId).put("display_id", displayId).put("window_active", true).put("window_focused", true)

        private fun rectJson(rect: Rect): JSONArray = JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom))
    }

    private class PreflightStop(val reason: String, val isFailure: Boolean = false) : RuntimeException(reason)

    companion object {
        private const val RELEASE_PACKAGE = "app.doran.car"
        private const val MAX_CAPTURE_CALLS = 6
        private const val CAPTURE_BUDGET_MS = 1_500L
        private const val TAP_HOLD_MS = 32L
        private const val MAX_TAP_MS = 180L
        private val CONNECTION_LABELS = setOf("연결 코드 받기", "새 연결 코드 받기")
    }
}
