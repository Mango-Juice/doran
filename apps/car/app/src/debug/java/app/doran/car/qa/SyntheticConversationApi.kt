package app.doran.car.qa

import app.doran.car.data.ApiFailure
import app.doran.car.data.ConversationApi
import app.doran.car.data.ServerEvent
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject

/** Deterministic in-memory contract double. Its results are never evidence of server behavior. */
class SyntheticConversationApi : ConversationApi {
    enum class TurnMode { HOLD_AFTER_SENTENCE, COMPLETE, FAIL_STREAM }
    data class Call(val operation: String, val requestId: String? = null, val context: JSONObject? = null)
    val calls = CopyOnWriteArrayList<Call>()
    val ownerId: String = UUID.randomUUID().toString()
    val deviceId: String = UUID.randomUUID().toString()
    var mode = TurnMode.HOLD_AFTER_SENTENCE
    var replyText = "합성 응답입니다."
    var afterEvent: (suspend (ServerEvent) -> Unit)? = null
    var pauseResponse: CompletableDeferred<Unit>? = null
    private var activeTurn: CompletableDeferred<Unit>? = null
    private val sessions = mutableMapOf<String, String>()

    override suspend fun request(path: String, body: JSONObject?, method: String): JSONObject = when {
        path == "/devices/bootstrap" -> JSONObject().put("paired", true).put(
            "device",
            JSONObject().put("id", deviceId).put("owner_id", ownerId)
        )
        path == "/devices/current" -> JSONObject().put("paired", true)
        path == "/settings" -> JSONObject().put("consent_accepted", true).put("vehicle_context_enabled", false)
            .put("conversation_suggestions", "off").put("vehicle_memory_revision", 0)
        path == "/sessions" -> {
            val requestId = checkNotNull(body).getString("request_id")
            calls.add(Call("session_start", requestId))
            JSONObject().put("id", sessions.getOrPut(requestId) { UUID.randomUUID().toString() })
        }
        path.endsWith("/pause") -> {
            calls.add(Call("pause_started"))
            pauseResponse?.await()
            calls.add(Call("pause_completed"))
            JSONObject().put("status", "paused")
        }
        path.endsWith("/finish") -> {
            calls.add(Call("finish", body?.optString("request_id")))
            JSONObject().put("record_id", UUID.randomUUID().toString()).put("status", "pending")
        }
        else -> throw AssertionError("Unexpected synthetic API route: $method $path")
    }

    override suspend fun turn(sessionId: String, body: JSONObject, onEvent: suspend (ServerEvent) -> Unit) {
        val requestId = body.getString("request_id")
        calls.add(Call("turn", requestId, body.optJSONObject("context")?.let { JSONObject(it.toString()) }))
        val held = CompletableDeferred<Unit>()
        activeTurn = held
        suspend fun deliver(name: String, value: JSONObject) {
            val event = ServerEvent(name, value)
            onEvent(event)
            afterEvent?.invoke(event)
        }
        try {
            deliver("delta", JSONObject().put("text", replyText))
            when (mode) {
                TurnMode.FAIL_STREAM -> throw ApiFailure("synthetic_stream_failure", "합성 연결 실패", true)
                TurnMode.HOLD_AFTER_SENTENCE -> {
                    deliver("sentence", JSONObject().put("text", replyText).put("index", 0))
                    held.await()
                }
                TurnMode.COMPLETE -> {
                    deliver("sentence", JSONObject().put("text", replyText).put("index", 0))
                    deliver("done", JSONObject().put("assistant_text", replyText))
                    calls.add(Call("turn_completed", requestId))
                }
            }
        } finally {
            if (activeTurn === held) activeTurn = null
        }
    }

    override fun cancelTurn() {
        calls.add(Call("turn_cancel_requested"))
        activeTurn?.completeExceptionally(CancellationException("Synthetic API call cancelled"))
        activeTurn = null
    }

    override fun close() {
        cancelTurn()
        pauseResponse?.cancel()
    }
}
