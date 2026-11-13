package app.doran.car

import ai.pleos.playground.navi.constants.NaviMode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import app.doran.car.vehicle.NavigationLifecycleGuard
import app.doran.car.vehicle.NavigationTransition
import app.doran.car.vehicle.SignalStatus
import app.doran.car.vehicle.VehicleArrival
import app.doran.car.vehicle.VehicleContext
import app.doran.car.vehicle.VehicleSignal
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NavigationLifecycleTest {
    private val now = 1_800_000_000_000L

    private fun state(mode: NaviMode, isRouting: Boolean = false) = RouteStateInfo(
        termsAgreement = true,
        naviMode = mode,
        isRouting = isRouting,
        // These required constructor fields were not captured by the diagnostic and are neutral test values.
        routeOption = RouteOption.RECOMMENDED,
        remainDestinationChargingInt = 0,
        remainWaypointChargingInt = emptyList(),
        trafficStatus = null
    )

    @Test fun `observed SDK sequence keeps trip identity and ETA during ROUTE GUIDING with routing false`() {
        val fixture = javaClass.getResourceAsStream("/navigation/route-guiding-observed.json")!!.bufferedReader().use { it.readText() }
        val events = JSONObject(fixture).getJSONArray("events")
        val guard = NavigationLifecycleGuard()
        var context = VehicleContext(consented = true)
        var activations = 0
        var destinations = 0
        for (index in 0 until events.length()) {
            val event = events.getJSONObject(index)
            val transition = when (event.getString("event")) {
                "onRouteStateInfo" -> guard.onRouteState(
                    state(NaviMode.valueOf(event.getString("navi_mode")), event.getBoolean("is_routing"))
                )
                "onRouteStarted" -> guard.onRouteStarted()
                "onNavigationStatus" -> guard.onNavigationStatus(NavigationStatus.valueOf(event.getString("status")))
                "onRouteCancelled" -> guard.onRouteCancelled()
                "onDestinationInfo" -> {
                    // Actual SDK type and the public test destination; this is a JVM replay, not an SDK execution.
                    val destination = DestinationInfo(
                        latitude = 37.48544722,
                        longitude = 127.03636666,
                        name = "42dot",
                        address = "",
                        remainDistance = event.getInt("remaining_meters"),
                        remainTime = event.getInt("remaining_seconds"),
                        tollFare = 0,
                        poiId = "36khp37hh8gqcm5nqhe5",
                        poiSubId = "0"
                    )
                    assertTrue(guard.isGuiding)
                    context =
                        context.copy(
                            remainingSeconds = VehicleSignal(destination.remainTime, "pleos_navi_destination", now, SignalStatus.AVAILABLE)
                        )
                    destinations++
                    NavigationTransition.IGNORE
                }
                else -> error("Unknown observed event")
            }
            context = when (transition) {
                NavigationTransition.ACTIVATE -> {
                    activations++
                    context.withoutNavigationRoute().copy(tripKey = "observed-trip")
                }
                NavigationTransition.CLEAR -> context.withoutNavigationRoute()
                NavigationTransition.CLEAR_ACTIVE -> context.withoutActiveRoute()
                NavigationTransition.RETAIN, NavigationTransition.IGNORE -> context
            }
            if (guard.isGuiding) {
                assertEquals("observed-trip", context.tripKey)
                if (destinations > 0) assertEquals(60, context.toJson(now).getInt("destination_eta_seconds"))
            }
        }
        assertEquals(1, activations)
        assertEquals(2, destinations)
        assertFalse(guard.isGuiding)
        assertNull(context.tripKey)
        assertFalse(context.toJson(now).has("destination_eta_seconds"))
    }

    @Test fun `route search start and completion alone never establish guidance`() {
        val guard = NavigationLifecycleGuard()
        assertEquals(NavigationTransition.CLEAR, guard.onRouteStarted())
        assertEquals(NavigationTransition.IGNORE, guard.onRouteSearchCompleted())
        assertEquals(NavigationTransition.IGNORE, guard.onNavigationStatus(NavigationStatus.ROUTE_DRIVE))
        assertFalse(guard.isGuiding)
        assertFalse(guard.onDestinationArrived())
        assertEquals(NavigationTransition.ACTIVATE, guard.onRouteState(state(NaviMode.ROUTE_GUIDING)))
        assertEquals(NavigationTransition.IGNORE, guard.onRouteSearchCompleted())
        assertTrue(guard.isGuiding)
    }

    @Test fun `initial preparation preserves restoration until an explicit real guidance snapshot`() {
        val guard = NavigationLifecycleGuard()
        assertEquals(NavigationTransition.IGNORE, guard.onNavigationStatus(NavigationStatus.INITIALIZING))
        assertEquals(NavigationTransition.IGNORE, guard.onNavigationStatus(NavigationStatus.ROUTE_DRIVE))
        assertFalse(guard.isGuiding)
        assertFalse(guard.onDestinationArrived())
        assertEquals(NavigationTransition.ACTIVATE, guard.onRouteState(state(NaviMode.ROUTE_GUIDING, isRouting = false)))
        assertTrue(guard.isGuiding)
    }

    @Test fun `initializing cannot reopen a cancelled or free drive epoch`() {
        for (close in listOf<(NavigationLifecycleGuard) -> NavigationTransition>(
            { it.onRouteCancelled() },
            { it.onNavigationStatus(NavigationStatus.FREE_DRIVE) }
        )) {
            val guard = NavigationLifecycleGuard()
            guard.onRouteState(state(NaviMode.ROUTE_GUIDING))
            close(guard)
            assertEquals(NavigationTransition.CLEAR, guard.onNavigationStatus(NavigationStatus.INITIALIZING))
            assertEquals(NavigationTransition.IGNORE, guard.onNavigationStatus(NavigationStatus.ROUTE_DRIVE))
            assertEquals(NavigationTransition.IGNORE, guard.onRouteState(state(NaviMode.ROUTE_GUIDING, isRouting = false)))
            assertFalse(guard.isGuiding)
            assertFalse(guard.onDestinationArrived())
        }
    }

    @Test fun `safe guiding with routing true and free drive both close the route`() {
        for (close in listOf<(NavigationLifecycleGuard) -> NavigationTransition>(
            { it.onRouteState(state(NaviMode.SAFE_GUIDING, isRouting = true)) },
            { it.onNavigationStatus(NavigationStatus.FREE_DRIVE) }
        )) {
            val guard = NavigationLifecycleGuard()
            guard.onRouteState(state(NaviMode.ROUTE_GUIDING))
            assertEquals(NavigationTransition.CLEAR, close(guard))
            assertFalse(guard.isGuiding)
            assertEquals(NavigationTransition.IGNORE, guard.onRouteState(state(NaviMode.ROUTE_GUIDING)))
            assertEquals(NavigationTransition.IGNORE, guard.onNavigationStatus(NavigationStatus.ROUTE_DRIVE))
            assertFalse(guard.onDestinationArrived())
        }
    }

    @Test fun `cancel rejects late guiding destination changes and arrival until a new start`() {
        val guard = NavigationLifecycleGuard()
        guard.onRouteState(state(NaviMode.ROUTE_GUIDING))
        assertEquals(NavigationTransition.CLEAR, guard.onRouteCancelled())
        assertEquals(NavigationTransition.IGNORE, guard.onDestinationChanged())
        assertEquals(NavigationTransition.IGNORE, guard.onRouteState(state(NaviMode.ROUTE_GUIDING)))
        assertFalse(guard.onDestinationArrived())
        guard.onRouteStarted()
        assertFalse(guard.isGuiding)
        assertFalse(guard.onDestinationArrived())
        assertEquals(NavigationTransition.ACTIVATE, guard.onRouteState(state(NaviMode.ROUTE_GUIDING)))
        assertTrue(guard.onDestinationArrived())
        assertFalse(guard.onDestinationArrived())
    }

    @Test fun `new SDK generation can restore real guidance but simulation never creates a real trip`() {
        val guard = NavigationLifecycleGuard()
        guard.onRouteCancelled()
        guard.resetForSdkGeneration()
        assertEquals(NavigationTransition.ACTIVATE, guard.onRouteState(state(NaviMode.ROUTE_GUIDING)))
        assertEquals(NavigationTransition.CLEAR, guard.onRouteState(state(NaviMode.SIMULATION_GUIDING, isRouting = true)))
        assertEquals(NavigationTransition.IGNORE, guard.onNavigationStatus(NavigationStatus.ROUTE_DRIVE))
        assertFalse(guard.isGuiding)
        assertFalse(guard.onDestinationArrived())
    }

    @Test fun `accepted arrival survives normal free and search completion but not explicit cancellation`() {
        val guard = NavigationLifecycleGuard()
        guard.onRouteState(state(NaviMode.ROUTE_GUIDING))
        assertTrue(guard.onDestinationArrived())
        val arrival = VehicleSignal(VehicleArrival(600, 1200, "synthetic policy case"), "pleos_navi_arrived", now, SignalStatus.AVAILABLE)
        val arrived = VehicleContext(consented = true, tripKey = "finished-trip", arrival = arrival)
        assertEquals(NavigationTransition.CLEAR_ACTIVE, guard.onNavigationStatus(NavigationStatus.FREE_DRIVE))
        assertEquals(NavigationTransition.CLEAR_ACTIVE, guard.onRouteSearchCompleted())
        val cleared = arrived.withoutActiveRoute()
        assertNull(cleared.tripKey)
        assertTrue(cleared.toJson(now + 60_000).getBoolean("arrived"))
        assertFalse(cleared.toJson(now + 60_001).has("arrived"))
        assertEquals(NavigationTransition.IGNORE, guard.onRouteState(state(NaviMode.ROUTE_GUIDING)))
        assertFalse(guard.onDestinationArrived())
        assertEquals(NavigationTransition.CLEAR, guard.onRouteCancelled())
        assertFalse(cleared.withoutNavigationRoute().toJson(now).has("arrived"))
    }
}
