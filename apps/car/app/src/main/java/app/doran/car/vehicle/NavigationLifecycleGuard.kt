package app.doran.car.vehicle

import ai.pleos.playground.navi.constants.NaviMode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.data.RouteStateInfo

internal enum class NavigationTransition { ACTIVATE, RETAIN, CLEAR, CLEAR_ACTIVE, IGNORE }

/**
 * A stopped route cannot be restored by a late state/arrival callback in the same SDK generation.
 * The SDK supplies no route identifier: a route-start callback opens a new epoch, but we cannot
 * correlate a subsequently delivered event to a distinct SDK route ID.
 */
internal class NavigationLifecycleGuard {
    private enum class Phase { RESTORABLE, AWAITING_GUIDANCE, GUIDING, ARRIVED, CLOSED }
    private var phase = Phase.RESTORABLE

    val isGuiding: Boolean get() = phase == Phase.GUIDING
    val acceptsGuidanceState: Boolean
        get() = phase == Phase.RESTORABLE || phase == Phase.AWAITING_GUIDANCE || phase == Phase.GUIDING

    fun resetForSdkGeneration() {
        phase = Phase.RESTORABLE
    }

    fun onRouteStarted(): NavigationTransition {
        phase = Phase.AWAITING_GUIDANCE
        return NavigationTransition.CLEAR
    }

    fun onRouteState(info: RouteStateInfo): NavigationTransition = when (info.naviMode) {
        // Observed on Pleos 2.1.0: ROUTE_GUIDING while isRouting=false throughout active guidance.
        // Use the explicit guidance mode, not the conflicting Boolean, for travel context.
        NaviMode.ROUTE_GUIDING -> activate()
        NaviMode.SAFE_GUIDING, NaviMode.NOT_WORKING -> close(preserveArrival = true)
        NaviMode.SIMULATION_GUIDING -> close(preserveArrival = false)
    }

    fun onNavigationStatus(status: NavigationStatus): NavigationTransition = when (status) {
        // Wait for the explicit non-simulation NaviMode before accepting travel context.
        NavigationStatus.ROUTE_DRIVE -> if (isGuiding) NavigationTransition.RETAIN else NavigationTransition.IGNORE
        // Initial service/map preparation does not establish that an existing route has ended.
        // Only a fresh SDK generation is eligible for this exception; CLOSED never reopens here.
        NavigationStatus.INITIALIZING -> if (phase == Phase.RESTORABLE) NavigationTransition.IGNORE else close(preserveArrival = true)
        NavigationStatus.FREE_DRIVE, NavigationStatus.INACTIVE -> close(preserveArrival = true)
    }

    fun onDestinationChanged(): NavigationTransition {
        if (phase == Phase.ARRIVED) return NavigationTransition.CLEAR_ACTIVE
        if (phase == Phase.CLOSED) return NavigationTransition.IGNORE
        phase = Phase.AWAITING_GUIDANCE
        return NavigationTransition.CLEAR
    }

    fun onRouteCancelled(): NavigationTransition = close(preserveArrival = false)

    fun onRouteSearchCompleted(): NavigationTransition =
        if (phase == Phase.ARRIVED) NavigationTransition.CLEAR_ACTIVE else NavigationTransition.IGNORE

    fun onDestinationArrived(): Boolean {
        if (phase != Phase.GUIDING) return false
        phase = Phase.ARRIVED
        return true
    }

    private fun activate(): NavigationTransition {
        if (!acceptsGuidanceState) return NavigationTransition.IGNORE
        if (phase == Phase.GUIDING) return NavigationTransition.RETAIN
        phase = Phase.GUIDING
        return NavigationTransition.ACTIVATE
    }

    private fun close(preserveArrival: Boolean): NavigationTransition {
        if (preserveArrival && phase == Phase.ARRIVED) return NavigationTransition.CLEAR_ACTIVE
        phase = Phase.CLOSED
        return NavigationTransition.CLEAR
    }
}
