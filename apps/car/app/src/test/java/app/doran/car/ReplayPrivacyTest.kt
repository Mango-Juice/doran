package app.doran.car

import app.doran.car.domain.mayReplayResponse
import app.doran.car.domain.replayContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayPrivacyTest {
    private fun oldContext() = JSONObject()
        .put("passenger_state", "alone").put("passenger_source", "user_assertion")
        .put("observed_at", "2026-11-14T00:00:00Z").put("place_label", "사용자가 알려 준 장소")

    @Test fun backgroundThenRetryDoesNotRestorePriorSoloAssertion() {
        val current = JSONObject().put("passenger_state", "unknown").put("passenger_source", "unknown")
        val replay = replayContext(current, "2026-11-14T01:00:00Z")
        assertEquals("unknown", replay.getString("passenger_state"))
        assertEquals("unknown", replay.getString("passenger_source"))
        assertEquals("2026-11-14T01:00:00Z", replay.getString("observed_at"))
    }

    @Test fun retryNeverTurnsOldSensorFactsIntoCurrentObservations() {
        val current = oldContext().put("destination_eta_seconds", 10).put("arrived", true)
            .put("trip_baseline", JSONObject().put("sample_count", 3))
        val replay = replayContext(current, "2026-11-14T01:00:00Z")
        assertEquals(4, replay.length())
        assertFalse(replay.has("destination_eta_seconds"))
        assertFalse(replay.has("arrived"))
        assertFalse(replay.has("trip_baseline"))
        assertFalse(replay.has("place_label"))
        assertEquals("2026-11-14T00:00:00Z", current.getString("observed_at"))
    }

    @Test fun currentOccupiedContextReplacesOldSoloAssertion() {
        val replay = replayContext(JSONObject().put("passenger_state", "occupied").put("passenger_source", "pleos_seat"), "now")
        assertEquals("occupied", replay.getString("passenger_state"))
        assertEquals("pleos_seat", replay.getString("passenger_source"))
    }

    @Test fun soloRequiresCurrentExplicitAssertion() {
        val current = JSONObject().put("passenger_state", "alone").put("passenger_source", "user_assertion")
        assertEquals("alone", replayContext(current, "now").getString("passenger_state"))
        assertEquals("unknown", replayContext(current.put("passenger_source", "unknown"), "now").getString("passenger_state"))
    }

    @Test fun cachedPersonalReplyRequiresCurrentSoloAssertion() {
        assertFalse(mayReplayResponse(true, JSONObject().put("passenger_state", "unknown")))
        assertFalse(mayReplayResponse(true, JSONObject().put("passenger_state", "occupied").put("passenger_source", "pleos_seat")))
        assertFalse(mayReplayResponse(true, JSONObject().put("passenger_state", "alone").put("passenger_source", "unknown")))
        assertTrue(mayReplayResponse(true, JSONObject().put("passenger_state", "alone").put("passenger_source", "user_assertion")))
        assertTrue(mayReplayResponse(false, JSONObject().put("passenger_state", "unknown")))
        assertTrue(mayReplayResponse(false, JSONObject().put("passenger_state", "occupied").put("passenger_source", "pleos_seat")))
    }
}
