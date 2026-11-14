package app.doran.car.domain

import org.json.JSONObject

/** Old responses may contain explicitly requested history even without an original solo assertion. */
fun mayReplayResponse(replaying: Boolean, current: JSONObject): Boolean = !replaying ||
    (current.optString("passenger_state") == "alone" && current.optString("passenger_source") == "user_assertion")

/** A retry sends current privacy only; the encrypted original keeps its historical observations. */
fun replayContext(current: JSONObject, observedAt: String): JSONObject {
    val state = current.optString("passenger_state", "unknown")
    val source = current.optString("passenger_source", "unknown")
    val verified = when {
        state == "alone" && source == "user_assertion" -> "alone" to "user_assertion"
        state == "occupied" && source == "pleos_seat" -> "occupied" to "pleos_seat"
        else -> "unknown" to "unknown"
    }
    return JSONObject().put("source", "pleos").put("observed_at", observedAt)
        .put("passenger_state", verified.first).put("passenger_source", verified.second)
}
