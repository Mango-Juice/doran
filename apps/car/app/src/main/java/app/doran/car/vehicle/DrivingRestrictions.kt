package app.doran.car.vehicle

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Unknown platform state hides sensitive content and setup. DO never overrides platform flags. */
data class DrivingState(val known: Boolean = false, val driving: Boolean = true, val flags: Int = 0, val maxStringLength: Int = 120) {
    val canShowTranscript: Boolean
        get() = known &&
            !driving &&
            flags and (
                CarUxRestrictions.UX_RESTRICTIONS_LIMIT_CONTENT or CarUxRestrictions.UX_RESTRICTIONS_LIMIT_STRING_LENGTH or
                    CarUxRestrictions.UX_RESTRICTIONS_NO_VOICE_TRANSCRIPTION or CarUxRestrictions.UX_RESTRICTIONS_NO_TEXT_MESSAGE
                ) == 0
    val canSetup: Boolean
        get() = canShowTranscript && flags and CarUxRestrictions.UX_RESTRICTIONS_NO_SETUP == 0
    val textLimit: Int?
        get() = maxStringLength.coerceAtLeast(0).takeIf { flags and CarUxRestrictions.UX_RESTRICTIONS_LIMIT_STRING_LENGTH != 0 }
}

class DrivingRestrictions(private val context: Context) {
    private val mutable = MutableStateFlow(DrivingState())
    val state: StateFlow<DrivingState> = mutable
    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null
    private var closed = false
    fun start() {
        if (car != null || closed) return
        runCatching {
            car = Car.createCar(context, Handler(Looper.getMainLooper()), Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected, ready ->
                if (closed) return@createCar
                if (!ready) {
                    mutable.value = DrivingState()
                    return@createCar
                }
                runCatching {
                    manager = connected.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
                    fun apply(value: CarUxRestrictions) {
                        mutable.value =
                            DrivingState(
                                true,
                                value.isRequiresDistractionOptimization,
                                value.activeRestrictions,
                                value.maxRestrictedStringLength
                            )
                    }
                    manager?.registerListener(::apply)
                    manager?.currentCarUxRestrictions?.let(::apply)
                }.onFailure { mutable.value = DrivingState() }
            }
        }.onFailure { mutable.value = DrivingState() }
    }
    fun close() {
        closed = true
        runCatching { manager?.unregisterListener() }
        runCatching { car?.disconnect() }
        manager = null
        car = null
    }
}
