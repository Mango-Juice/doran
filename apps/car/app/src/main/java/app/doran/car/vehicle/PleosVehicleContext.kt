package app.doran.car.vehicle

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.Odometer
import ai.pleos.playground.vehicle.api.Seat
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.DrivingTimeListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import app.doran.car.data.SecureStorage
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

enum class SignalStatus { DISABLED, WAITING, AVAILABLE, UNSUPPORTED, UNAVAILABLE, PERMISSION_REQUIRED }

data class VehicleSignal<T>(
    val value: T? = null,
    val source: String,
    val measuredAtMillis: Long? = null,
    val status: SignalStatus = SignalStatus.DISABLED
) {
    fun freshValue(nowMillis: Long, maxAgeMillis: Long = 60_000): T? = value.takeIf {
        status == SignalStatus.AVAILABLE &&
            measuredAtMillis != null &&
            nowMillis - measuredAtMillis in 0..maxAgeMillis
    }
}

data class VehicleCoordinate(val latitude: Double, val longitude: Double)

data class VehicleArrival(val durationSeconds: Int, val distanceMeters: Int, val destinationName: String)

data class VehicleContext(
    val consented: Boolean = false,
    val tripKey: String? = null,
    val location: VehicleSignal<VehicleCoordinate> = VehicleSignal(source = "pleos_fused_location"),
    val remainingSeconds: VehicleSignal<Int> = VehicleSignal(source = "pleos_navi_destination"),
    val currentDrivingSeconds: VehicleSignal<Long> = VehicleSignal(source = "pleos_vehicle_odometer"),
    val currentDrivingMeters: VehicleSignal<Double> = VehicleSignal(source = "pleos_vehicle_odometer"),
    val arrival: VehicleSignal<VehicleArrival> = VehicleSignal(source = "pleos_navi_arrived"),
    val occupiedPassengerSeat: VehicleSignal<Boolean> = VehicleSignal(source = "pleos_vehicle_seat"),
    val placeLabel: VehicleSignal<String> = VehicleSignal(source = "pleos_user_labeled_place"),
    val tripBaseline: VehicleSignal<TripBaseline> = VehicleSignal(source = "local_confirmed_arrivals"),
    val navigationStatus: SignalStatus = SignalStatus.DISABLED
) {
    // Absence of an occupied sensor is not proof that every passenger seat is empty.
    fun passengerState(nowMillis: Long = System.currentTimeMillis()): String =
        if (consented && occupiedPassengerSeat.freshValue(nowMillis) == true) "occupied" else "unknown"

    fun withoutActiveRoute(): VehicleContext = copy(
        tripKey = null,
        remainingSeconds = VehicleSignal(source = "pleos_navi_destination", status = SignalStatus.UNAVAILABLE),
        placeLabel = VehicleSignal(source = "pleos_user_labeled_place", status = SignalStatus.UNAVAILABLE),
        tripBaseline = VehicleSignal(source = "local_confirmed_arrivals", status = SignalStatus.UNAVAILABLE)
    )

    /** API v1 context. Locations/addresses are never sent to the server. */
    fun toJson(nowMillis: Long = System.currentTimeMillis()): JSONObject {
        val out = JSONObject().put("source", "pleos")
            .put("passenger_state", passengerState(nowMillis))
        val observationTimes = mutableListOf<Long>()
        fun <T> add(name: String, signal: VehicleSignal<T>, maxAgeMillis: Long = 60_000) {
            if (!consented) return
            signal.freshValue(nowMillis, maxAgeMillis)?.let { value ->
                out.put(name, value)
                signal.measuredAtMillis?.let(observationTimes::add)
            }
        }
        add("place_label", placeLabel)
        add("destination_eta_seconds", remainingSeconds)
        add("trip_duration_seconds", currentDrivingSeconds)
        add("trip_distance_m", currentDrivingMeters)
        if (out.has("trip_duration_seconds")) out.put("trip_duration_source", "pleos_vehicle_odometer")
        if (consented) {
            tripBaseline.freshValue(nowMillis)?.let { baseline ->
                out.put("trip_baseline", baseline.toJson())
                tripBaseline.measuredAtMillis?.let(observationTimes::add)
            }
            arrival.freshValue(nowMillis, 60_000)?.let { observed ->
                out.put("arrived", true)
                    .put("trip_duration_seconds", observed.durationSeconds)
                    .put("trip_distance_m", observed.distanceMeters)
                    .put("trip_duration_source", "pleos_navi_arrived")
                arrival.measuredAtMillis?.let(observationTimes::add)
            }
        }
        if (consented && occupiedPassengerSeat.freshValue(nowMillis) == true) {
            occupiedPassengerSeat.measuredAtMillis?.let(observationTimes::add)
        }
        return out.put("observed_at", Instant.ofEpochMilli(observationTimes.maxOrNull() ?: nowMillis).toString())
    }
}

/** Foreground, opt-in subscriptions. Every callback is fenced to its SDK generation. */
class PleosVehicleContext(context: Context) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(VehicleContext())
    val state: StateFlow<VehicleContext> = mutableState.asStateFlow()
    private var generation = 0L
    private var active = false
    private var routeActive = false
    private val secure = SecureStorage(this.context)
    private var placeMemory = LocalPlaceMemory()
    private var placeOwner: String? = null
    private var memoryRevision: Long? = null
    private var destination: VehicleCoordinate? = null
    private var destinationObservedAt: Long? = null
    private var tripId = UUID.randomUUID().toString()
    private var pendingLabel: String? = null
    private var pendingArrival: Long? = null
    private var pendingArrivalDuration: Int? = null
    private var currentTripBaseline: TripBaseline? = null
    private var confirmedArrival: VehicleCoordinate? = null
    private var confirmedArrivalAt: Long? = null
    private var fused: FusedLocation? = null
    private var locationListener: FusedLocationListener? = null
    private var navi: NaviHelper? = null
    private var naviListener: NaviHelperEventListener? = null
    private var vehicle: Vehicle? = null
    private var odometer: Odometer? = null
    private var seats: Seat? = null
    private var timeListener: DrivingTimeListener? = null
    private var distanceListener: DistanceDrivenListener? = null
    private val seatListeners = mutableMapOf<SeatArea, SeatOccupancyListener>()
    private val occupiedSeats = mutableMapOf<SeatArea, Boolean?>()

    fun start(consented: Boolean) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Call start on the main thread" }
        if (!consented) {
            stop()
            clearPrivateData()
            return
        }
        val owner = runCatching { secure.get("owner_id") }.getOrNull()
        if (active && owner == placeOwner) return
        stop()
        loadPlaces(owner)
        active = true
        val token = generation
        mutableState.value = VehicleContext(
            consented = true,
            location = VehicleSignal(source = "pleos_fused_location", status = SignalStatus.WAITING),
            remainingSeconds = VehicleSignal(source = "pleos_navi_destination", status = SignalStatus.WAITING),
            currentDrivingSeconds = VehicleSignal(source = "pleos_vehicle_odometer", status = SignalStatus.WAITING),
            currentDrivingMeters = VehicleSignal(source = "pleos_vehicle_odometer", status = SignalStatus.WAITING),
            arrival = VehicleSignal(source = "pleos_navi_arrived", status = SignalStatus.WAITING),
            occupiedPassengerSeat = VehicleSignal(source = "pleos_vehicle_seat", status = SignalStatus.WAITING),
            navigationStatus = SignalStatus.WAITING
        )
        initializeLocation(token)
        initializeNavigation(token)
        initializeVehicle(token)
    }

    private fun dispatch(token: Long, block: () -> Unit) {
        main.post { if (active && token == generation) block() }
    }

    private fun initializeLocation(token: Long) {
        try {
            if (context.checkSelfPermission(LOCATION_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
                locationUnavailable(SignalStatus.PERMISSION_REQUIRED)
                return
            }
            val sdk = FusedLocation(context)
            if (!sdk.isSupported()) {
                locationUnavailable(SignalStatus.UNSUPPORTED)
                sdk.release()
                return
            }
            val listener = object : FusedLocationListener {
                override fun onChanged(location: LocationData?) = dispatch(token) {
                    if (location == null ||
                        !location.latitude.isFinite() ||
                        !location.longitude.isFinite() ||
                        location.latitude !in -90.0..90.0 ||
                        location.longitude !in -180.0..180.0
                    ) {
                        locationUnavailable(SignalStatus.UNAVAILABLE)
                    } else {
                        mutableState.value = mutableState.value.copy(
                            location = VehicleSignal(
                                VehicleCoordinate(location.latitude, location.longitude),
                                "pleos_fused_location",
                                location.timestampMillis,
                                SignalStatus.AVAILABLE
                            )
                        )
                        confirmPendingArrival()
                        refreshPlaceLabel()
                    }
                }
                override fun onError(exception: Exception) = dispatch(token) {
                    locationUnavailable(SignalStatus.UNAVAILABLE)
                }
            }
            fused = sdk
            locationListener = listener
            sdk.initialize()
            sdk.registerFusedLocationCallback(listener)
            sdk.getLastKnownLocation(listener)
        } catch (_: Exception) {
            locationUnavailable(SignalStatus.UNAVAILABLE)
        }
    }

    private fun locationUnavailable(status: SignalStatus) {
        mutableState.value = mutableState.value.copy(location = VehicleSignal(source = "pleos_fused_location", status = status))
    }

    private fun initializeNavigation(token: Long) {
        try {
            val sdk = NaviHelper(context)
            navi = sdk
            val listener = object : NaviHelperEventListener {
                override fun onDestinationInfo(destinationInfo: DestinationInfo) = dispatch(token) {
                    if (routeActive) {
                        val point = VehicleCoordinate(destinationInfo.latitude, destinationInfo.longitude)
                        if (point.latitude.isFinite() &&
                            point.longitude.isFinite() &&
                            point.latitude in -90.0..90.0 &&
                            point.longitude in -180.0..180.0
                        ) {
                            val previous = destination
                            if (previous != null && LocalPlaceMemory.distanceMeters(previous, point) > 200) pendingLabel = null
                            destination = point
                            destinationObservedAt = System.currentTimeMillis()
                            refreshPlaceLabel()
                        }
                        setRemaining(destinationInfo.remainTime)
                    }
                }
                override fun onDrivingInfo(drivingInfo: DrivingInfo) = dispatch(token) {
                    if (routeActive) setRemaining(drivingInfo.destination.duration)
                }
                override fun onRouteStarted(routeStartInfo: RouteStartInfo) = dispatch(token) {
                    if (!routeActive) {
                        clearRoute()
                        tripId = UUID.randomUUID().toString()
                    }
                    routeActive = true
                    mutableState.value = mutableState.value.copy(tripKey = tripId)
                    runCatching { sdk.getDestinationInfo() }.onFailure { navigationUnavailable() }
                }
                override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) = dispatch(token) {
                    val info = destinationArrivedInfo
                    routeActive = false
                    pendingArrival = System.currentTimeMillis().takeIf { info.duration >= 0 && info.distance >= 0 }
                    pendingArrivalDuration = info.duration.takeIf { it >= 0 }
                    confirmPendingArrival()
                    refreshPlaceLabel()
                    mutableState.value = mutableState.value.copy(
                        tripKey = null,
                        remainingSeconds = VehicleSignal(source = "pleos_navi_destination", status = SignalStatus.UNAVAILABLE),
                        arrival = if (info.duration >= 0 && info.distance >= 0) {
                            VehicleSignal(
                                VehicleArrival(info.duration, info.distance, info.name),
                                "pleos_navi_arrived",
                                System.currentTimeMillis(),
                                SignalStatus.AVAILABLE
                            )
                        } else {
                            VehicleSignal(source = "pleos_navi_arrived", status = SignalStatus.UNAVAILABLE)
                        }
                    )
                }
                override fun onDestinationChanged() = dispatch(token) {
                    clearRoute()
                    runCatching { sdk.getRouteStateInfo() }.onFailure { navigationUnavailable() }
                }
                override fun onRouteCancelled() = dispatch(token) { clearRoute() }
                override fun onRouteEnded() = dispatch(token) {
                    routeActive = false
                    mutableState.value = mutableState.value.withoutActiveRoute()
                    refreshPlaceLabel()
                }
                override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) = dispatch(token) {
                    if (routeStateInfo.isRouting && !routeActive) {
                        clearRoute()
                        tripId = UUID.randomUUID().toString()
                    }
                    routeActive = routeStateInfo.isRouting
                    mutableState.value = mutableState.value.copy(tripKey = tripId.takeIf { routeActive })
                    if (!routeActive) {
                        // Preserve a just-observed arrival when the route closes normally.
                        mutableState.value = mutableState.value.withoutActiveRoute()
                        refreshPlaceLabel()
                    } else {
                        runCatching { sdk.getDestinationInfo() }.onFailure { navigationUnavailable() }
                    }
                }
                override fun onError(errorCode: NaviErrorCode) = dispatch(token) { navigationUnavailable() }
            }
            naviListener = listener
            sdk.initialize(onConnected = {
                dispatch(token) {
                    try {
                        sdk.addListener(listener)
                        mutableState.value = mutableState.value.copy(navigationStatus = SignalStatus.AVAILABLE)
                        sdk.getRouteStateInfo()
                    } catch (_: Exception) {
                        navigationUnavailable()
                    }
                }
            }, onFailed = { dispatch(token) { navigationUnavailable() } })
        } catch (_: Exception) {
            navigationUnavailable()
        }
    }

    private fun setRemaining(seconds: Int) {
        mutableState.value = mutableState.value.copy(
            navigationStatus = SignalStatus.AVAILABLE,
            remainingSeconds = VehicleSignal(
                seconds.takeIf { it >= 0 },
                "pleos_navi_destination",
                System.currentTimeMillis(),
                if (seconds >= 0) SignalStatus.AVAILABLE else SignalStatus.UNAVAILABLE
            )
        )
    }

    private fun clearRoute() {
        routeActive = false
        destination = null
        destinationObservedAt = null
        pendingLabel = null
        pendingArrival = null
        pendingArrivalDuration = null
        currentTripBaseline = null
        confirmedArrival = null
        confirmedArrivalAt = null
        mutableState.value = mutableState.value.copy(
            tripKey = null,
            remainingSeconds = VehicleSignal(source = "pleos_navi_destination", status = SignalStatus.UNAVAILABLE),
            arrival = VehicleSignal(source = "pleos_navi_arrived", status = SignalStatus.UNAVAILABLE),
            placeLabel = VehicleSignal(source = "pleos_user_labeled_place", status = SignalStatus.UNAVAILABLE),
            tripBaseline = VehicleSignal(source = "local_confirmed_arrivals", status = SignalStatus.UNAVAILABLE)
        )
    }

    private fun navigationUnavailable() {
        clearRoute()
        mutableState.value = mutableState.value.copy(navigationStatus = SignalStatus.UNAVAILABLE)
    }

    private fun initializeVehicle(token: Long) {
        try {
            val sdk = Vehicle(context)
            vehicle = sdk
            sdk.initialize()
            val odo = sdk.getOdometer()
            val seat = sdk.getSeat()
            odometer = odo
            seats = seat
            initializeOdometer(token, odo)
            seat.checkSeatOccupancyCapabilities(
                onComplete = { config: CapabilityConfig, areas: List<SeatArea> ->
                    dispatch(token) {
                        if (!readable(config)) {
                            seatUnavailable(SignalStatus.UNSUPPORTED)
                        } else {
                            // Korean emulator configuration is left-hand drive. Combined driver areas
                            // are excluded because their occupancy cannot identify a passenger.
                            val passengers = areas.filter { SeatValue.ROW_1_LEFT !in it.values }
                            if (passengers.isEmpty()) seatUnavailable(SignalStatus.UNSUPPORTED)
                            for (area in passengers) subscribeSeat(token, seat, area)
                        }
                    }
                },
                onFailed = { dispatch(token) { seatUnavailable(SignalStatus.UNAVAILABLE) } }
            )
        } catch (_: Exception) {
            timeUnavailable(SignalStatus.UNAVAILABLE)
            distanceUnavailable(SignalStatus.UNAVAILABLE)
            seatUnavailable(SignalStatus.UNAVAILABLE)
        }
    }

    private fun initializeOdometer(token: Long, odo: Odometer) {
        odo.checkTripCurrentDrivingTimeCapabilities(onComplete = { config ->
            dispatch(token) {
                if (!readable(config)) {
                    timeUnavailable(SignalStatus.UNSUPPORTED)
                } else {
                    try {
                        val listener = object : DrivingTimeListener {
                            override fun onDrivingTimeUpdated(drivingTime: Int?) = dispatch(token) { updateTime(drivingTime) }
                            override fun onFailed(exception: Exception) = dispatch(token) { timeUnavailable(SignalStatus.UNAVAILABLE) }
                        }
                        timeListener = listener
                        odo.registerTripCurrentDrivingTime(listener)
                        odo.getTripCurrentDrivingTime(
                            { value -> dispatch(token) { updateTime(value) } },
                            { dispatch(token) { timeUnavailable(SignalStatus.UNAVAILABLE) } }
                        )
                    } catch (_: Exception) {
                        timeUnavailable(SignalStatus.UNAVAILABLE)
                    }
                }
            }
        }, onFailed = { dispatch(token) { timeUnavailable(SignalStatus.UNAVAILABLE) } })
        odo.checkTripCurrentDrivingDistanceCapabilities(onComplete = { config ->
            dispatch(token) {
                if (!readable(config)) {
                    distanceUnavailable(SignalStatus.UNSUPPORTED)
                } else {
                    try {
                        val listener = object : DistanceDrivenListener {
                            override fun onDistanceDrivenUpdated(distanceDriven: Float?) =
                                dispatch(token) { updateDistance(distanceDriven) }
                            override fun onFailed(exception: Exception) = dispatch(token) { distanceUnavailable(SignalStatus.UNAVAILABLE) }
                        }
                        distanceListener = listener
                        odo.registerTripCurrentDrivingDistance(listener)
                        odo.getTripCurrentDrivingDistance(
                            { value -> dispatch(token) { updateDistance(value) } },
                            { dispatch(token) { distanceUnavailable(SignalStatus.UNAVAILABLE) } }
                        )
                    } catch (_: Exception) {
                        distanceUnavailable(SignalStatus.UNAVAILABLE)
                    }
                }
            }
        }, onFailed = { dispatch(token) { distanceUnavailable(SignalStatus.UNAVAILABLE) } })
    }

    private fun updateTime(minutes: Int?) {
        if (minutes == null || minutes < 0) {
            timeUnavailable(SignalStatus.UNAVAILABLE)
        } else {
            mutableState.value = mutableState.value.copy(
                currentDrivingSeconds = VehicleSignal(
                    minutes.toLong() * 60,
                    "pleos_vehicle_odometer",
                    System.currentTimeMillis(),
                    SignalStatus.AVAILABLE
                )
            )
        }
    }

    private fun updateDistance(meters: Float?) {
        if (meters == null || !meters.isFinite() || meters < 0) {
            distanceUnavailable(SignalStatus.UNAVAILABLE)
        } else {
            mutableState.value = mutableState.value.copy(
                currentDrivingMeters = VehicleSignal(
                    meters.toDouble(),
                    "pleos_vehicle_odometer",
                    System.currentTimeMillis(),
                    SignalStatus.AVAILABLE
                )
            )
        }
    }

    private fun timeUnavailable(status: SignalStatus) {
        mutableState.value =
            mutableState.value.copy(currentDrivingSeconds = VehicleSignal(source = "pleos_vehicle_odometer", status = status))
    }

    private fun distanceUnavailable(status: SignalStatus) {
        mutableState.value =
            mutableState.value.copy(currentDrivingMeters = VehicleSignal(source = "pleos_vehicle_odometer", status = status))
    }

    private fun subscribeSeat(token: Long, seat: Seat, area: SeatArea) {
        try {
            occupiedSeats[area] = null
            val listener = object : SeatOccupancyListener {
                override fun onSeatOccupancyUpdated(occupancy: Boolean?) = dispatch(token) { updateSeat(area, occupancy) }
                override fun onFailed(exception: Exception) = dispatch(token) { updateSeat(area, null) }
            }
            seatListeners[area] = listener
            seat.registerSeatOccupancy(area, listener)
            seat.getCurrentSeatOccupancy(
                area,
                { value -> dispatch(token) { updateSeat(area, value) } },
                { dispatch(token) { updateSeat(area, null) } }
            )
        } catch (_: Exception) {
            updateSeat(area, null)
        }
    }

    private fun updateSeat(area: SeatArea, occupied: Boolean?) {
        occupiedSeats[area] = occupied
        mutableState.value = mutableState.value.copy(
            occupiedPassengerSeat = VehicleSignal(
                occupiedSeats.values.any { it == true }.takeIf { occupiedSeats.values.any { it == true } },
                "pleos_vehicle_seat",
                System.currentTimeMillis(),
                if (occupiedSeats.values.any { it == true }) SignalStatus.AVAILABLE else SignalStatus.UNAVAILABLE
            )
        )
    }

    private fun seatUnavailable(status: SignalStatus) {
        occupiedSeats.clear()
        mutableState.value = mutableState.value.copy(occupiedPassengerSeat = VehicleSignal(source = "pleos_vehicle_seat", status = status))
    }

    /** Parent calls this on each settings refresh before collecting optional context. */
    fun syncMemoryRevision(revision: Long) {
        check(Looper.myLooper() == Looper.getMainLooper())
        require(revision >= 0)
        if (memoryRevision != null && memoryRevision != revision) clearPrivateData()
        memoryRevision = revision
    }

    /** Only explicit, supported user claims create labels; never an LLM-generated sentence. */
    fun observeUserUtterance(text: String) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!active || !state.value.consented || placeOwner == null) return
        if (placeMemory.observeScheduleCorrection(text, System.currentTimeMillis())) {
            currentTripBaseline = null
            persistPlaces()
            refreshPlaceLabel()
        }
        LocalPlaceMemory.departureLabel(text)?.let { label ->
            if (routeActive && destination != null) pendingLabel = label
        }
        val correction = LocalPlaceMemory.correctionLabel(text) ?: return
        val now = System.currentTimeMillis()
        val arrived = confirmedArrival ?: return
        val arrivedAt = confirmedArrivalAt ?: return
        val current = state.value.location.freshValue(now, 30_000) ?: return
        if (now - arrivedAt !in 0..600_000 || LocalPlaceMemory.distanceMeters(arrived, current) > 200) return
        if (placeMemory.correctArrival(current, correction, now)) {
            currentTripBaseline = null
            persistPlaces()
            refreshPlaceLabel()
        }
    }

    /** Clear on account switch/revoke, consent revoke, and server source-invalidation revision. */
    fun clearPrivateData() {
        check(Looper.myLooper() == Looper.getMainLooper())
        placeMemory.clear()
        placeOwner = null
        pendingLabel = null
        pendingArrival = null
        pendingArrivalDuration = null
        currentTripBaseline = null
        confirmedArrival = null
        confirmedArrivalAt = null
        runCatching { secure.remove(PLACE_MEMORY_KEY) }
        mutableState.value = mutableState.value.copy(
            placeLabel =
            VehicleSignal(source = "pleos_user_labeled_place", status = SignalStatus.DISABLED),
            tripBaseline = VehicleSignal(source = "local_confirmed_arrivals", status = SignalStatus.DISABLED)
        )
    }

    private fun loadPlaces(owner: String?) {
        placeMemory.clear()
        placeOwner = owner
        if (owner == null || memoryRevision == null) {
            runCatching { secure.remove(PLACE_MEMORY_KEY) }
            return
        }
        val loaded = runCatching { secure.get(PLACE_MEMORY_KEY)?.let(::JSONObject) }.getOrNull()
        if (loaded?.optString("owner") == owner && loaded.optLong("revision", -1) == memoryRevision) {
            placeMemory = LocalPlaceMemory(loaded.optString("clusters").takeIf { it.isNotEmpty() })
        } else {
            runCatching { secure.remove(PLACE_MEMORY_KEY) }
        }
    }

    private fun persistPlaces() {
        val owner = placeOwner ?: return
        val revision = memoryRevision ?: return
        runCatching {
            secure.put(
                PLACE_MEMORY_KEY,
                JSONObject().put("owner", owner)
                    .put("revision", revision).put("clusters", placeMemory.serialize()).toString()
            )
        }
    }

    private fun confirmPendingArrival() {
        val at = pendingArrival ?: return
        val target = destination ?: return
        val now = System.currentTimeMillis()
        val location = state.value.location
        val point = location.freshValue(now, 30_000) ?: return
        val measuredAt = location.measuredAtMillis ?: return
        if (placeOwner == null || !state.value.consented) return
        val previousLabel = placeMemory.labelAt(target, now)
        val priorBaseline = placeMemory.baselineAt(target, now, tripId)
            .takeIf { pendingLabel == null || pendingLabel == previousLabel }
        val result = placeMemory.confirmArrival(target, point, measuredAt, at, now, tripId, pendingLabel, pendingArrivalDuration)
        if (result != null) {
            currentTripBaseline = priorBaseline
            confirmedArrival = target
            confirmedArrivalAt = at
            pendingArrival = null
            pendingArrivalDuration = null
            pendingLabel = null
            persistPlaces()
        } else if (now - at > 120_000) {
            pendingArrival = null
            pendingArrivalDuration = null
            pendingLabel = null
        }
    }

    private fun refreshPlaceLabel() {
        val now = System.currentTimeMillis()
        val point = if (routeActive) destination else state.value.location.freshValue(now, 30_000)
        val label = point?.takeIf { placeOwner != null }?.let { placeMemory.labelAt(it, now) }
        val timestamp = if (routeActive) destinationObservedAt else state.value.location.measuredAtMillis
        val baseline = if (point == null || placeOwner == null) {
            null
        } else if (routeActive) {
            placeMemory.baselineAt(point, now, tripId)
        } else {
            currentTripBaseline.takeIf {
                confirmedArrival?.let { LocalPlaceMemory.distanceMeters(it, point) <= 200 } == true &&
                    confirmedArrivalAt?.let { now - it in 0..60_000 } == true
            }
        }
        mutableState.value = mutableState.value.copy(
            placeLabel = VehicleSignal(
                label,
                "pleos_user_labeled_place",
                timestamp,
                if (label != null) SignalStatus.AVAILABLE else SignalStatus.UNAVAILABLE
            ),
            tripBaseline = VehicleSignal(
                baseline,
                "local_confirmed_arrivals",
                timestamp,
                if (baseline != null) SignalStatus.AVAILABLE else SignalStatus.UNAVAILABLE
            )
        )
    }

    fun stop() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Call stop on the main thread" }
        active = false
        routeActive = false
        destination = null
        destinationObservedAt = null
        pendingLabel = null
        pendingArrival = null
        pendingArrivalDuration = null
        currentTripBaseline = null
        confirmedArrival = null
        confirmedArrivalAt = null
        generation++
        locationListener?.let { listener -> runCatching { fused?.unregisterFusedLocationCallback(listener) } }
        naviListener?.let { listener -> runCatching { navi?.removeListener(listener) } }
        timeListener?.let { listener -> runCatching { odometer?.unregisterTripCurrentDrivingTime(listener) } }
        distanceListener?.let { listener -> runCatching { odometer?.unregisterTripCurrentDrivingDistance(listener) } }
        seatListeners.forEach { (area, listener) -> runCatching { seats?.unregisterSeatOccupancy(area, listener) } }
        runCatching { fused?.release() }
        runCatching { navi?.release() }
        runCatching { vehicle?.release() }
        fused = null
        navi = null
        vehicle = null
        seats = null
        odometer = null
        locationListener = null
        naviListener = null
        timeListener = null
        distanceListener = null
        seatListeners.clear()
        occupiedSeats.clear()
        mutableState.value = VehicleContext()
    }

    private fun readable(config: CapabilityConfig) = config.accessType == AccessType.READ || config.accessType == AccessType.READ_WRITE

    companion object {
        private const val PLACE_MEMORY_KEY = "vehicle_places.v1"
        const val LOCATION_PERMISSION = "pleos.car.permission.FUSED_LOCATION"
    }
}
