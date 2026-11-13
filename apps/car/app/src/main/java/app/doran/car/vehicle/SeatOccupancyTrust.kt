package app.doran.car.vehicle

/**
 * No SDK/platform combination currently has a verified seat-occupancy interpretation.
 * Vehicle 2.3.2 maps raw 1 to true, while the tested Pleos 2.1.0 Console emits
 * raw 1 for Empty and raw 2 for Occupied. Do not invert Boolean values or enable
 * an untested platform by default. Raw SDK diagnostics remain separate from this policy.
 */
internal object SeatOccupancyTrust {
    val automaticInferenceEnabled: Boolean = false

    fun isConfirmedOccupied(signal: VehicleSignal<Boolean>, nowMillis: Long): Boolean =
        automaticInferenceEnabled && signal.freshValue(nowMillis) == true
}
