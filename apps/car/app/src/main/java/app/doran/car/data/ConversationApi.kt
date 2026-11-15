package app.doran.car.data

import org.json.JSONObject

/** Network operations used by the conversation, independent of its presentation and speech SDK. */
interface ConversationApi {
    suspend fun request(path: String, body: JSONObject? = null, method: String = if (body == null) "GET" else "POST"): JSONObject
    suspend fun turn(sessionId: String, body: JSONObject, onEvent: suspend (ServerEvent) -> Unit)
    suspend fun opening(sessionId: String, body: JSONObject, onEvent: suspend (ServerEvent) -> Unit): Unit =
        throw UnsupportedOperationException("Opening is not supported")
    fun cancelTurn()
    fun close()
}
