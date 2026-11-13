package app.doran.car

import app.doran.car.vehicle.LocalPlaceMemory
import app.doran.car.vehicle.SignalStatus
import app.doran.car.vehicle.TripBaseline
import app.doran.car.vehicle.VehicleArrival
import app.doran.car.vehicle.VehicleContext
import app.doran.car.vehicle.VehicleCoordinate
import app.doran.car.vehicle.VehicleSignal
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class TripBaselineTest {
    private val point = VehicleCoordinate(37.285, 127.045)
    private val home = VehicleCoordinate(37.300, 127.070)
    private val dayMillis = 86_400_000L
    private fun at(day: Int, hour: Int = 9, minute: Int = 0): Long =
        ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli()

    private fun add(
        memory: LocalPlaceMemory,
        day: Int,
        seconds: Int,
        minute: Int = 0,
        target: VehicleCoordinate = point,
        label: String = "회사"
    ) {
        val time = at(day, 9, minute)
        memory.confirmArrival(target, target, time, time, time, "$label-$day", label, seconds)
    }

    @Test fun `same destination robust median retains count time and source`() {
        val memory = LocalPlaceMemory()
        add(memory, 1, 600, 0)
        add(memory, 2, 660, 5)
        add(memory, 3, 720, 10)
        add(memory, 4, 7200, 3)
        val baseline = memory.baselineAt(point, at(5))!!
        assertEquals(4, baseline.sampleCount)
        assertEquals(690.0, baseline.medianDurationSeconds, 0.0)
        assertEquals(544, baseline.medianArrivalMinute)
        assertEquals(at(4, 9, 3), baseline.latestObservedAt)
        assertEquals("local_confirmed_arrivals", baseline.toJson().getString("source"))
        assertNull(memory.baselineAt(home, at(5)))
    }

    @Test fun `current arrival never contaminates its previous baseline`() {
        val memory = LocalPlaceMemory()
        add(memory, 1, 600)
        add(memory, 2, 660)
        add(memory, 3, 720)
        val before = memory.baselineAt(point, at(4), "회사-4")!!
        add(memory, 4, 20000)
        val after = memory.baselineAt(point, at(4) + 1, "회사-4")!!
        assertEquals(before, after)
        assertEquals(3, after.sampleCount)
        assertEquals(660.0, after.medianDurationSeconds, 0.0)
    }

    @Test fun `midnight arrival times use circular median instead of noon`() {
        val memory = LocalPlaceMemory()
        listOf(at(1, 23, 50), at(3, 0, 0), at(4, 0, 10)).forEachIndexed { index, time ->
            memory.confirmArrival(point, point, time, time, time, "trip$index", "회사", 600)
        }
        assertEquals(0, memory.baselineAt(point, at(5))!!.medianArrivalMinute)
    }

    @Test fun `schedule correction segments company history but retains home history`() {
        val memory = LocalPlaceMemory()
        for (day in 1..3) {
            add(memory, day, 600)
            add(memory, day, 900, target = home, label = "집")
        }
        assertTrue(memory.observeScheduleCorrection("요즘 출근 시간이 바뀌어서 괜찮아요", at(4)))
        assertNull(memory.baselineAt(point, at(4)))
        assertNotNull(memory.baselineAt(home, at(4)))
        val restored = LocalPlaceMemory(memory.serialize())
        assertNull(restored.baselineAt(point, at(4)))
        for (day in 5..7) add(restored, day, 1200, 30)
        assertEquals(1200.0, restored.baselineAt(point, at(8))!!.medianDurationSeconds, 0.0)
        assertEquals(570, restored.baselineAt(point, at(8))!!.medianArrivalMinute)
    }

    @Test fun `historical and unsupported observations do not form a baseline`() {
        val memory = LocalPlaceMemory()
        for (day in 1..3) add(memory, day, 600)
        assertNull(memory.baselineAt(point, at(3) + 31 * dayMillis))
        val empty = LocalPlaceMemory()
        for (day in 1..3) {
            val time = at(day)
            empty.confirmArrival(point, point, time, time, time, "t$day", "회사")
        }
        assertNull(empty.baselineAt(point, at(4)))
    }

    @Test fun `hypothetical quoted and third party schedule changes do not reset history`() {
        listOf("출근 시간이 바뀌었으면 좋겠어", "친구 출근 시간이 바뀌었어", "\"출근 시간이 바뀌었어\"라고 말했어요", "출근 시간이 안 바뀌었어")
            .forEach { assertFalse(it, LocalPlaceMemory.isWorkScheduleCorrection(it)) }
        assertTrue(LocalPlaceMemory.isWorkScheduleCorrection("출근 시간이 바뀌었어"))
    }

    @Test fun `cloud duration uses actual Navi leg when arrived and marks its provenance`() {
        val now = at(4)
        val baseline = TripBaseline(3, 600.0, 540, at(3))
        val context = VehicleContext(
            consented = true,
            currentDrivingSeconds = VehicleSignal(90000, "pleos_vehicle_odometer", now, SignalStatus.AVAILABLE),
            tripBaseline = VehicleSignal(baseline, "local_confirmed_arrivals", now, SignalStatus.AVAILABLE),
            arrival = VehicleSignal(VehicleArrival(1200, 3000, "private location"), "pleos_navi_arrived", now, SignalStatus.AVAILABLE)
        )
        val json = context.toJson(now)
        assertEquals(1200, json.getInt("trip_duration_seconds"))
        assertEquals("pleos_navi_arrived", json.getString("trip_duration_source"))
        assertEquals(Instant.ofEpochMilli(at(3)).toString(), json.getJSONObject("trip_baseline").getString("latest_observed_at"))
        val ongoing = context.copy(arrival = VehicleSignal(source = "pleos_navi_arrived")).toJson(now)
        assertEquals("pleos_vehicle_odometer", ongoing.getString("trip_duration_source"))
        assertEquals(90000, ongoing.getInt("trip_duration_seconds"))
    }

    @Test fun `inactive route removes destination references before another turn can serialize them`() {
        val now = at(4)
        val context = VehicleContext(
            consented = true,
            tripKey = "old-trip",
            remainingSeconds = VehicleSignal(300, "pleos_navi_destination", now, SignalStatus.AVAILABLE),
            placeLabel = VehicleSignal("회사", "pleos_user_labeled_place", now, SignalStatus.AVAILABLE),
            tripBaseline = VehicleSignal(TripBaseline(3, 600.0, 540, at(3)), "local_confirmed_arrivals", now, SignalStatus.AVAILABLE)
        )
        val ended = context.withoutActiveRoute()
        val json = ended.toJson(now)
        assertNull(ended.tripKey)
        assertFalse(json.has("destination_eta_seconds"))
        assertFalse(json.has("place_label"))
        assertFalse(json.has("trip_baseline"))
    }
}
