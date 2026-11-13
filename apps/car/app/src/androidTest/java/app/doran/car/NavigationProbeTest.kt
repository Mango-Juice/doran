package app.doran.car

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NaviMode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.doran.car.data.SecureStorage
import app.doran.car.vehicle.DrivingRestrictions
import java.io.File
import java.time.Instant
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** One real navigation request. No fake navigation events, microphone, account, or cloud bootstrap. */
@RunWith(AndroidJUnit4::class)
class NavigationProbeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext

    @Test
    fun captureRealNavigationRequest(): Unit = runBlocking {
        assertTrue("Use the isolated debug package", target.packageName.endsWith(".debug"))
        assertTrue("Refuse a release target", target.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        assertTrue(
            "This probe only runs on an Automotive emulator",
            target.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE) &&
                (Build.FINGERPRINT.contains("avd", true) || Build.HARDWARE in setOf("ranchu", "goldfish"))
        )
        val secure = SecureStorage(target)
        assertTrue("Use an unpaired debug installation", secure.get("owner_id") == null && secure.get("active_session") == null)

        val report = JSONObject().put("started_at", Instant.now().toString())
            .put("target_package", target.packageName).put("fingerprint", Build.FINGERPRINT)
            .put("scope", "One official SDK request to a public test destination; no synthetic arrival events")
            .put("test_destination", JSONObject().put("name", TEST_NAME).put("latitude", TEST_LATITUDE).put("longitude", TEST_LONGITUDE))
            .put("destination_source", "Pleos-Playground/pleos-sample-navi-app NaviTestData.routeInfo")
        var activity: Activity? = null
        try {
            val suppliedPackage = InstrumentationRegistry.getArguments().getString("navigation_package").orEmpty()
            if (!suppliedPackage.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))) {
                report.put("status", "SKIPPED_NAVIGATION_PACKAGE_ARGUMENT_REQUIRED")
                return@runBlocking
            }
            val navigationInstalled = installedPackage(suppliedPackage)
            val serviceInstalled = installedPackage(NAVI_SERVICE_PACKAGE)
            report.put("navigation_application", navigationInstalled).put("navi_service", serviceInstalled)
                .put("installation_note", "Package installation does not prove default navigation selection or route support")
            if (!navigationInstalled.optBoolean("installed") || !serviceInstalled.optBoolean("installed")) {
                report.put("status", "SKIPPED_REQUIRED_PACKAGE_NOT_CONFIRMED")
                return@runBlocking
            }
            val permissions = JSONObject()
            for (permission in listOf(ROUTE_PERMISSION, SEARCH_PERMISSION)) {
                permissions.put(permission, target.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED)
            }
            report.put("permissions", permissions)
            if (!permissions.getBoolean(ROUTE_PERMISSION) || !permissions.getBoolean(SEARCH_PERMISSION)) {
                report.put("status", "SKIPPED_PERMISSION_REQUIRED")
                return@runBlocking
            }
            activity = instrumentation.startActivitySync(
                Intent(target, SdkVerificationActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            instrumentation.waitForIdleSync()
            report.put("foreground_host", "debug-only SdkVerificationActivity; navigation app may subsequently foreground itself")
            report.put("navigation", observeNavigation())
            report.put("status", "EVIDENCE_CAPTURED")
        } catch (error: Exception) {
            report.put("status", if (error is SecurityException) "PERMISSION_DENIED" else "HARNESS_ERROR")
                .put("error_class", error.javaClass.simpleName)
        } finally {
            activity?.let { host -> instrumentation.runOnMainSync { host.finish() } }
            report.put("finished_at", Instant.now().toString())
            File(target.filesDir, "navigation-probe.json").writeText(report.toString(2))
        }
        Unit
    }

    private fun installedPackage(packageName: String): JSONObject {
        // Runtime supplies a package observed in the actual emulator; never guess a navigation provider.
        val output = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("pm path --user current $packageName")
        ).bufferedReader().use { it.readText() }
        return JSONObject().put("package", packageName)
            .put("installed", output.lineSequence().any { it.startsWith("package:") && it.endsWith(".apk") })
            .put("check", "pm path --user current")
    }

    private suspend fun observeNavigation(): JSONObject = withContext(Dispatchers.Main) {
        val sdk = NaviHelper(target)
        val observations = Observations()
        val restrictions = DrivingRestrictions(target)
        val result = JSONObject()
        var listenerAdded = false
        var requestSent = false
        try {
            restrictions.start()
            val parked = withTimeoutOrNull(10_000) { restrictions.state.filter { it.known }.first() }
            result.put(
                "native_car_ux",
                JSONObject().put("known", parked?.known == true).put("can_setup", parked?.canSetup == true)
                    .put("active_restrictions", parked?.flags ?: JSONObject.NULL)
            )
            if (parked?.canSetup != true) {
                result.put("status", "SKIPPED_NOT_CONFIRMED_PARKED")
                return@withContext result
            }
            val connection = CompletableDeferred<Throwable?>()
            observations.event("initialize_called")
            sdk.initialize(
                onConnected = {
                    observations.event("service_connected")
                    connection.complete(null)
                },
                onFailed = { error ->
                    observations.event("service_connection_failed", JSONObject().put("error_class", error.javaClass.simpleName))
                    connection.complete(error)
                }
            )
            val connected = withTimeoutOrNull(20_000) {
                connection.await() == null
            }
            if (connected != true) {
                result.put("status", if (connected == null) "CONNECTION_TIMEOUT" else "CONNECTION_FAILED")
                return@withContext result
            }
            sdk.addListener(observations)
            listenerAdded = true
            observations.phase("preflight")
            sdk.getRouteStateInfo()
            sdk.getDestinationInfo()
            val preflightObserved = withTimeoutOrNull(15_000) {
                while (!observations.hasPreflightResponses()) delay(100)
                true
            } == true
            // Keep a short quiet period after both responses before deciding to mutate navigation.
            delay(2_000)
            result.put("preflight", observations.preflightSummary())
            if (!preflightObserved) {
                result.put("status", "SKIPPED_PREFLIGHT_STATE_UNOBSERVED")
                return@withContext result
            }
            if (!observations.canRequest()) {
                result.put("status", "SKIPPED_EXISTING_ROUTE_OR_NAVIGATION_NOT_READY")
                return@withContext result
            }
            if (!restrictions.state.value.canSetup) {
                result.put("status", "SKIPPED_DRIVING_STATE_CHANGED")
                return@withContext result
            }
            observations.phase("request")
            observations.event("request_route_called")
            requestSent = true
            sdk.requestRoute(
                RouteInfo(
                    latitude = TEST_LATITUDE,
                    longitude = TEST_LONGITUDE,
                    poiId = TEST_POI_ID,
                    poiName = TEST_NAME,
                    poiSubId = "0",
                    address = "서울시 강남구 남부순환로 2621"
                )
            )
            observations.event("request_route_returned")
            // Bounded low-frequency queries verify service responses; a return from requestRoute is not success.
            val deadline = SystemClock.elapsedRealtime() + 30_000
            while (SystemClock.elapsedRealtime() < deadline) {
                if (!restrictions.state.value.canSetup) {
                    observations.event("driving_state_changed_during_observation")
                    break
                }
                sdk.getRouteStateInfo()
                sdk.getDestinationInfo()
                delay(3_000)
                if (observations.hasRequestError() || observations.hasArrival()) break
            }
            result.put("status", observations.requestOutcome())
        } catch (error: Exception) {
            result.put("status", if (error is SecurityException) "PERMISSION_DENIED" else "SDK_CALL_FAILED")
                .put("error_class", error.javaClass.simpleName)
        } finally {
            observations.phase("cleanup")
            var cleanupDestinationRefreshed = false
            if (requestSent && !observations.hasArrival()) {
                try {
                    val previousResponses = observations.prepareCleanupDestinationQuery()
                    sdk.getDestinationInfo()
                    cleanupDestinationRefreshed = withTimeoutOrNull(5_000) {
                        while (!observations.hasNewDestinationResponse(previousResponses)) delay(100)
                        true
                    } == true
                } catch (error: Exception) {
                    result.put("cleanup_query_error_class", error.javaClass.simpleName)
                }
            }
            if (requestSent && cleanupDestinationRefreshed && observations.ownsCurrentRoute()) {
                try {
                    observations.event("cancel_own_route_called")
                    sdk.cancelRoute()
                    val cancelled = withTimeoutOrNull(8_000) {
                        while (!observations.hasCancellation()) delay(100)
                        true
                    } == true
                    result.put("route_cleanup", if (cancelled) "CANCEL_CALLBACK_RECEIVED" else "CANCEL_REQUESTED_CALLBACK_UNOBSERVED")
                } catch (error: Exception) {
                    result.put("route_cleanup", "CANCEL_CALL_FAILED").put("cleanup_error_class", error.javaClass.simpleName)
                }
            } else {
                result.put(
                    "route_cleanup",
                    when {
                        !requestSent -> "NO_REQUEST_SENT"
                        observations.hasArrival() -> "ARRIVAL_RECEIVED_NO_CANCELLATION_SENT"
                        else -> "NOT_CANCELLED_ROUTE_OWNERSHIP_UNCONFIRMED"
                    }
                )
            }
            if (listenerAdded) runCatching { sdk.removeListener(observations) }
            runCatching { sdk.release() }
            restrictions.close()
            observations.stop()
            result.put("request_count", if (requestSent) 1 else 0)
                .put("observations", observations.summary())
                .put("sdk_release_called", true)
                .put("arrival_note", "Only a real onDestinationArrived callback counts; no GPS injection or forced arrival")
        }
        result
    }

    private class Observations : NaviHelperEventListener {
        private val lock = Any()
        private val started = SystemClock.elapsedRealtime()
        private val events = JSONArray()
        private var accepting = true
        private var phase = "connection"
        private var routeState: RouteStateInfo? = null
        private var navigationStatus: NavigationStatus? = null
        private var destinationPresent = false
        private var currentDestinationMatches = false
        private var requestRouteStarted = false
        private var requestRoutingCompleted = false
        private var requestDestinationMatched = false
        private var requestArrival = false
        private var requestArrivalMatched = false
        private var requestError = false
        private var cleanupCancelled = false
        private var destinationCallbackCount = 0
        private var etaSeconds: Int? = null

        fun phase(value: String) = synchronized(lock) { phase = value }
        fun stop() = synchronized(lock) { accepting = false }
        fun event(name: String, values: JSONObject = JSONObject()) = synchronized(lock) {
            if (accepting && events.length() < 150) {
                events.put(
                    values.put("event", name).put("phase", phase).put("at", Instant.now().toString())
                        .put("elapsed_ms", SystemClock.elapsedRealtime() - started)
                )
            }
        }
        fun hasPreflightResponses(): Boolean = synchronized(lock) { routeState != null && destinationCallbackCount > 0 }
        fun hasRequestError(): Boolean = synchronized(lock) { requestError }
        fun hasArrival(): Boolean = synchronized(lock) { requestArrival }
        fun hasCancellation(): Boolean = synchronized(lock) { cleanupCancelled }
        fun ownsCurrentRoute(): Boolean = synchronized(lock) {
            val state = routeState
            val active = state?.isRouting == true ||
                state?.naviMode == NaviMode.ROUTE_GUIDING ||
                state?.naviMode == NaviMode.SIMULATION_GUIDING
            currentDestinationMatches &&
                !requestArrival &&
                active &&
                navigationStatus != NavigationStatus.INACTIVE &&
                navigationStatus != NavigationStatus.FREE_DRIVE
        }
        fun prepareCleanupDestinationQuery(): Int = synchronized(lock) {
            currentDestinationMatches = false
            destinationCallbackCount
        }
        fun hasNewDestinationResponse(previous: Int): Boolean = synchronized(lock) { destinationCallbackCount > previous }
        fun canRequest(): Boolean = synchronized(lock) {
            val state = routeState ?: return@synchronized false
            state.termsAgreement &&
                destinationCallbackCount > 0 &&
                !state.isRouting &&
                state.naviMode == NaviMode.SAFE_GUIDING &&
                navigationStatus != NavigationStatus.ROUTE_DRIVE &&
                !destinationPresent
        }
        fun preflightSummary(): JSONObject = synchronized(lock) {
            JSONObject().put("route_state_observed", routeState != null)
                .put("terms_agreed", routeState?.termsAgreement ?: JSONObject.NULL)
                .put("navi_mode", routeState?.naviMode?.name ?: JSONObject.NULL)
                .put("is_routing", routeState?.isRouting ?: JSONObject.NULL)
                .put("navigation_status", navigationStatus?.name ?: JSONObject.NULL)
                .put("destination_present", destinationPresent)
                .put("destination_callback_count", destinationCallbackCount)
                .put("private_destination_values_recorded", false)
        }
        fun requestOutcome(): String = synchronized(lock) {
            when {
                requestError -> "NAVIGATION_ERROR_CALLBACK"
                requestArrivalMatched -> "TEST_DESTINATION_ARRIVAL_CALLBACK_RECEIVED"
                requestArrival -> "ARRIVAL_CALLBACK_RECEIVED_TEST_ASSOCIATION_UNCONFIRMED"
                requestDestinationMatched -> "TEST_DESTINATION_CALLBACK_RECEIVED"
                requestRouteStarted || requestRoutingCompleted -> "ROUTE_CALLBACK_RECEIVED_DESTINATION_UNCONFIRMED"
                else -> "REQUEST_RETURNED_NO_ROUTE_CALLBACK_OBSERVED"
            }
        }
        fun summary(): JSONObject = synchronized(lock) {
            JSONObject().put("route_started_observed", requestRouteStarted)
                .put("routing_completed_observed", requestRoutingCompleted)
                .put("test_destination_matched", requestDestinationMatched)
                .put("destination_eta_seconds", etaSeconds ?: JSONObject.NULL)
                .put("real_arrival_observed", requestArrival).put("request_error_observed", requestError)
                .put("test_destination_arrival_confirmed", requestArrivalMatched)
                .put("events", JSONArray(events.toString()))
        }

        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) = synchronized(lock) {
            if (!accepting) return@synchronized
            routeState = routeStateInfo
            if (routeStateInfo.naviMode != NaviMode.ROUTE_GUIDING &&
                routeStateInfo.naviMode != NaviMode.SIMULATION_GUIDING &&
                !routeStateInfo.isRouting
            ) {
                currentDestinationMatches = false
            }
            event(
                "onRouteStateInfo",
                JSONObject().put("navi_mode", routeStateInfo.naviMode.name).put("is_routing", routeStateInfo.isRouting)
                    .put("terms_agreed", routeStateInfo.termsAgreement)
            )
        }
        override fun onNavigationStatus(status: NavigationStatus) = synchronized(lock) {
            if (!accepting) return@synchronized
            navigationStatus = status
            if (status != NavigationStatus.ROUTE_DRIVE) currentDestinationMatches = false
            event("onNavigationStatus", JSONObject().put("status", status.name))
        }
        override fun onDestinationInfo(destinationInfo: DestinationInfo) = synchronized(lock) {
            if (!accepting) return@synchronized
            destinationCallbackCount++
            destinationPresent = destinationInfo.name.isNotBlank() ||
                destinationInfo.poiId.isNotBlank() ||
                destinationInfo.latitude != 0.0 ||
                destinationInfo.longitude != 0.0
            val matches = destinationInfo.poiId == TEST_POI_ID ||
                (
                    abs(destinationInfo.latitude - TEST_LATITUDE) < 0.0005 &&
                        abs(destinationInfo.longitude - TEST_LONGITUDE) < 0.0005
                    )
            currentDestinationMatches = phase != "preflight" && matches
            if (phase == "request" && matches) {
                requestDestinationMatched = true
                etaSeconds = destinationInfo.remainTime
            }
            event(
                "onDestinationInfo",
                JSONObject().put("destination_present", destinationPresent).put("matches_public_test_destination", matches)
                    .put("remaining_seconds", if (matches) destinationInfo.remainTime else JSONObject.NULL)
                    .put("remaining_meters", if (matches) destinationInfo.remainDistance else JSONObject.NULL)
            )
        }
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) = synchronized(lock) {
            if (!accepting) return@synchronized
            currentDestinationMatches = false
            if (phase == "request") requestRouteStarted = true
            event("onRouteStarted", JSONObject().put("matches_test_name", routeStartInfo.name == TEST_NAME))
        }
        override fun onRoutingCompleted() = synchronized(lock) {
            if (!accepting) return@synchronized
            if (phase == "request") requestRoutingCompleted = true
            event("onRoutingCompleted")
        }
        override fun onDestinationChanged() = synchronized(lock) {
            if (!accepting) return@synchronized
            currentDestinationMatches = false
            event("onDestinationChanged")
        }
        override fun onRouteCancelled() = synchronized(lock) {
            if (!accepting) return@synchronized
            currentDestinationMatches = false
            if (phase == "cleanup") cleanupCancelled = true
            event("onRouteCancelled")
        }
        override fun onDrivingInfo(drivingInfo: DrivingInfo) = synchronized(lock) {
            if (!accepting) return@synchronized
            event(
                "onDrivingInfo",
                JSONObject().put("matches_test_name", drivingInfo.destination.name == TEST_NAME)
                    .put("remaining_seconds", if (currentDestinationMatches) drivingInfo.destination.duration else JSONObject.NULL)
            )
        }
        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) = synchronized(lock) {
            if (!accepting) return@synchronized
            if (phase == "request") {
                requestArrival = true
                requestArrivalMatched = currentDestinationMatches && destinationArrivedInfo.name == TEST_NAME
            }
            event(
                "onDestinationArrived",
                JSONObject().put("matches_test_name", destinationArrivedInfo.name == TEST_NAME)
                    .put("test_destination_previously_confirmed", requestDestinationMatched)
            )
        }
        override fun onError(errorCode: NaviErrorCode) = synchronized(lock) {
            if (!accepting) return@synchronized
            if (phase == "request") requestError = true
            event("onError", JSONObject().put("error_code", errorCode.name))
        }
    }

    companion object {
        private const val NAVI_SERVICE_PACKAGE = "ai.pleos.playground.service.navi"
        private const val ROUTE_PERMISSION = "pleos.car.permission.NAVI_ROUTE"
        private const val SEARCH_PERMISSION = "pleos.car.permission.NAVI_ROUTE_SEARCH"
        private const val TEST_NAME = "42dot"
        private const val TEST_LATITUDE = 37.48544722
        private const val TEST_LONGITUDE = 127.03636666
        private const val TEST_POI_ID = "36khp37hh8gqcm5nqhe5"
    }
}
