package app.doran.car

import app.doran.car.vehicle.LocalPlaceMemory
import app.doran.car.vehicle.VehicleCoordinate
import org.junit.Assert.*
import org.junit.Test

class LocalPlaceMemoryTest {
    private val start = 1_800_000_000_000L
    private val company = VehicleCoordinate(37.285, 127.045)
    private val home = VehicleCoordinate(37.300, 127.070)

    @Test fun `explicit departure cannot assign company to current home position`() {
        val memory = LocalPlaceMemory()
        val label = LocalPlaceMemory.departureLabel("회사 가는 중인데 오늘 회의가 많아요")
        assertEquals("회사", label)
        assertNull(memory.confirmArrival(company, home, start, start, start, "trip1", label))
        assertNull(memory.labelAt(home, start))
        assertNull(memory.labelAt(company, start))
    }

    @Test fun `only three distinct confirmed arrivals establish repeat place label`() {
        val memory = LocalPlaceMemory()
        repeat(3) { index ->
            val at = start + index * 3_600_000L
            memory.confirmArrival(company, company, at, at, at, "trip$index", if (index == 0) "회사" else null)
            assertEquals(if (index == 2) "회사" else null, memory.labelAt(company, at))
        }
    }

    @Test fun `duplicate arrival and quick SDK restart do not count as repeat visits`() {
        val memory = LocalPlaceMemory()
        memory.confirmArrival(company, company, start, start, start, "trip1", "회사")
        repeat(5) { index ->
            val at = start + index * 10_000L
            memory.confirmArrival(company, company, at, at, at, "restart$index", "회사")
        }
        assertNull(memory.labelAt(company, start + 50_000))
    }

    @Test fun `stale GPS and stale arrival cannot teach a place`() {
        val memory = LocalPlaceMemory()
        assertNull(memory.confirmArrival(company, company, start - 30_001, start, start, "trip1", "회사"))
        assertNull(memory.confirmArrival(company, company, start, start - 120_001, start, "trip1", "회사"))
        assertNull(memory.confirmArrival(company, company, start + 1, start, start, "trip1", "회사"))
    }

    @Test fun `explicit correction replaces old label only at recent confirmed place`() {
        val memory = LocalPlaceMemory()
        repeat(3) { index ->
            val at = start + index * 3_600_000L
            memory.confirmArrival(company, company, at, at, at, "trip$index", "회사")
        }
        val at = start + 7_200_000
        assertFalse(memory.correctArrival(home, "집", at))
        assertTrue(memory.correctArrival(company, LocalPlaceMemory.correctionLabel("여기는 회사가 아니라 집이야")!!, at))
        assertEquals("집", memory.labelAt(company, at))
        assertFalse(memory.correctArrival(company, "회사", at + 600_001))
    }

    @Test fun `quoted hypothetical negated and unrelated statements never assign a label`() {
        listOf(
            "친구가 회사 가는 중이라고 했어요",
            "회사 가는 중이라면 좋겠어요",
            "회사 가는 중이 아니에요",
            "회사 가는 중일까요",
            "회사 가는 중국인이에요",
            "\"회사 가는 중\"이라고 말했어요",
            "퇴근 중이에요"
        )
            .forEach { assertNull(it, LocalPlaceMemory.departureLabel(it)) }
        assertEquals("집", LocalPlaceMemory.departureLabel("지금 집으로 가고 있어요"))
        assertEquals("회사", LocalPlaceMemory.departureLabel("직장에 가는 중이에요"))
    }

    @Test fun `persisted clusters survive roundtrip and explicit clear removes labels`() {
        val memory = LocalPlaceMemory()
        repeat(3) { index ->
            val at = start + index * 3_600_000L
            memory.confirmArrival(company, company, at, at, at, "trip$index", "회사")
        }
        val restored = LocalPlaceMemory(memory.serialize())
        assertEquals("회사", restored.labelAt(company, start + 7_200_000))
        restored.clear()
        assertNull(restored.labelAt(company, start + 7_200_000))
        assertNull(LocalPlaceMemory("invalid json").labelAt(company, start))
    }

    @Test fun `old patterns expire instead of following user indefinitely`() {
        val memory = LocalPlaceMemory()
        repeat(3) { index ->
            val at = start + index * 3_600_000L
            memory.confirmArrival(company, company, at, at, at, "trip$index", "회사")
        }
        assertNull(memory.labelAt(company, start + 7_200_000 + 90L * 86_400_000 + 1))
    }

    @Test fun `recent return supports correction even when too soon to increment visit count`() {
        val memory = LocalPlaceMemory()
        memory.confirmArrival(company, company, start, start, start, "trip1", "회사")
        val returnedAt = start + 1_200_000
        val returned = memory.confirmArrival(company, company, returnedAt, returnedAt, returnedAt, "trip2", null)!!
        assertEquals(1, returned.visits)
        assertTrue(memory.correctArrival(company, "집", returnedAt + 1))
        val restored = LocalPlaceMemory(memory.serialize())
        assertTrue(restored.correctArrival(company, "회사", returnedAt + 2))
    }
}
