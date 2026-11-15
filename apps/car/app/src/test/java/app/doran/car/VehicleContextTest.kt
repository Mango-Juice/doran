package app.doran.car

import app.doran.car.vehicle.SignalStatus
import app.doran.car.vehicle.VehicleArrival
import app.doran.car.vehicle.VehicleContext
import app.doran.car.vehicle.VehicleCoordinate
import app.doran.car.vehicle.VehicleSignal
import org.junit.Assert.*
import org.junit.Test

class VehicleContextTest {
    private val now = 1_800_000_000_000L
    private fun <T> sample(value: T, at: Long = now) = VehicleSignal(value, "test", at, SignalStatus.AVAILABLE)

    @Test fun `stale or future samples are not uploaded`() {
        val context = VehicleContext(
            consented = true,
            remainingSeconds = sample(90, now - 60_001),
            currentDrivingMeters = sample(1200.0, now + 1),
            currentDrivingSeconds = sample(600L, now - 59_000)
        )
        val json = context.toJson(now)
        assertFalse(json.has("destination_eta_seconds"))
        assertFalse(json.has("trip_distance_m"))
        assertEquals(600L, json.getLong("trip_duration_seconds"))
        assertEquals("2027-01-15T07:59:01Z", json.getString("observed_at"))
    }

    @Test fun `revoked consent prevents every optional value from upload`() {
        val context = VehicleContext(
            consented = false,
            remainingSeconds = sample(90),
            currentDrivingMeters = sample(2000.0),
            currentDrivingSeconds = sample(600L),
            occupiedPassengerSeat = sample(true),
            arrival = sample(VehicleArrival(600, 2000, "private place"))
        )
        val json = context.toJson(now)
        assertEquals(setOf("source", "passenger_state", "observed_at"), json.keys().asSequence().toSet())
        assertEquals("unknown", json.getString("passenger_state"))
    }

    @Test fun `unverified SDK booleans cannot establish either occupied or private cabin`() {
        assertEquals("unknown", VehicleContext(consented = true, occupiedPassengerSeat = sample(false)).passengerState(now))
        assertEquals("unknown", VehicleContext(consented = true, occupiedPassengerSeat = sample(true)).passengerState(now))
        assertEquals("unknown", VehicleContext(consented = true, occupiedPassengerSeat = sample(true, now - 60_001)).passengerState(now))
    }

    @Test fun `readable but unverified occupied sample is excluded from cloud context and observation time`() {
        val context = VehicleContext(
            consented = true,
            remainingSeconds = sample(90, now - 2_000),
            occupiedPassengerSeat = VehicleSignal(true, "pleos_vehicle_seat", now, SignalStatus.AVAILABLE)
        )
        val json = context.toJson(now)
        assertEquals("unknown", json.getString("passenger_state"))
        assertEquals("2027-01-15T07:59:58Z", json.getString("observed_at"))
        assertEquals(90, json.getInt("destination_eta_seconds"))
    }

    @Test fun `unavailable seat interpretation does not suppress valid independent vehicle signals`() {
        val context = VehicleContext(
            consented = true,
            occupiedPassengerSeat = VehicleSignal(source = "pleos_vehicle_seat", status = SignalStatus.UNAVAILABLE),
            arrival = sample(VehicleArrival(600, 1200, "public test destination"))
        )
        val json = context.toJson(now)
        assertEquals("unknown", json.getString("passenger_state"))
        assertTrue(json.getBoolean("arrived"))
        assertEquals(600, json.getInt("trip_duration_seconds"))
    }

    @Test fun `precise coordinates and destination details remain off the wire`() {
        val context = VehicleContext(
            consented = true,
            location = sample(VehicleCoordinate(37.285123, 127.045123)),
            arrival = sample(VehicleArrival(1200, 3500, "private place"))
        )
        val json = context.toJson(now)
        assertTrue(json.getBoolean("arrived"))
        assertEquals("pleos", json.getString("source"))
        assertFalse(json.toString().contains("37.285123"))
        assertFalse(json.toString().contains("private place"))
        assertFalse(json.has("evidence"))
    }

    @Test fun `destination name requires separate consent and fresh route signal`() {
        val context = VehicleContext(consented = true, destinationName = sample("meeting place"))
        assertFalse(context.toJson(now).has("destination_name"))
        assertEquals("meeting place", context.toJson(now, true).getString("destination_name"))
        assertFalse(context.copy(consented = false).toJson(now, true).has("destination_name"))
        assertFalse(context.copy(destinationName = sample("meeting place", now - 60_001)).toJson(now, true).has("destination_name"))
        assertFalse(context.withoutActiveRoute().toJson(now, true).has("destination_name"))
    }
}
