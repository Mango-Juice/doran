package app.doran.car.data

import app.doran.car.BuildConfig
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class ApiFailure(val code: String, override val message: String, val retryable: Boolean = false) : IOException(message)

data class ServerEvent(val name: String, val body: JSONObject)

class DoranApi internal constructor(
    private val storage: SecureStorage,
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).build()
) : ConversationApi {
    private val authMutex = Mutex()

    @Volatile private var turnCall: Call? = null
    private val base = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val key = BuildConfig.SUPABASE_PUBLISHABLE_KEY
    val configured: Boolean get() = base.startsWith("https://") && key.isNotBlank()

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) else response.close()
            }
        })
    }

    private suspend fun token(force: Boolean = false): String = authMutex.withLock {
        if (!configured) throw ApiFailure("configuration_missing", "앱 연결 설정이 필요해요")
        val saved = storage.get("auth")?.let(::JSONObject)
        if (!force && saved != null && saved.optLong("expires_at") > System.currentTimeMillis() / 1000 + 90) {
            return@withLock saved.getString("access_token")
        }
        val refreshing = saved?.optString("refresh_token")?.takeIf { it.isNotBlank() }
        val path = if (refreshing == null) "/auth/v1/signup" else "/auth/v1/token?grant_type=refresh_token"
        val body = if (refreshing == null) JSONObject() else JSONObject().put("refresh_token", refreshing)
        val response = client.newCall(
            Request.Builder().url(base + path).header("apikey", key)
                .post(body.toString().toRequestBody(JSON)).build()
        ).await()
        response.use {
            val data = JSONObject(it.body?.string().orEmpty().ifBlank { "{}" })
            if (!it.isSuccessful || !data.has("access_token")) {
                throw ApiFailure("authentication_failed", "차량 인증을 확인하지 못했어요. 연결 상태를 확인하고 다시 시도해 주세요", true)
            }
            if (!data.has("expires_at")) data.put("expires_at", System.currentTimeMillis() / 1000 + data.optLong("expires_in", 3600))
            storage.put("auth", data.toString())
            data.getString("access_token")
        }
    }

    override suspend fun request(path: String, body: JSONObject?, method: String): JSONObject = withContext(Dispatchers.IO) {
        repeat(2) { attempt ->
            val jwt = token(attempt > 0)
            val request = Request.Builder().url("$base/functions/v1/doran-api/v1$path")
                .header("apikey", key).header("Authorization", "Bearer $jwt")
                .method(method, body?.toString()?.toRequestBody(JSON)).build()
            client.newCall(request).await().use { response ->
                if (response.code == 401 && attempt == 0) return@repeat
                val value = JSONObject(response.body?.string().orEmpty().ifBlank { "{}" })
                if (!response.isSuccessful) throw failure(response.code, value)
                return@withContext value
            }
        }
        throw ApiFailure("authentication_failed", "차량 인증을 다시 확인해 주세요", true)
    }

    override suspend fun turn(sessionId: String, body: JSONObject, onEvent: suspend (ServerEvent) -> Unit) =
        streamResponse(sessionId, "turns", body, onEvent)

    override suspend fun opening(sessionId: String, body: JSONObject, onEvent: suspend (ServerEvent) -> Unit) =
        streamResponse(sessionId, "opening", body, onEvent)

    private suspend fun streamResponse(sessionId: String, action: String, body: JSONObject, onEvent: suspend (ServerEvent) -> Unit) =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url("$base/functions/v1/doran-api/v1/sessions/$sessionId/$action")
                .header("apikey", key).header("Authorization", "Bearer ${token()}").header("Accept", "text/event-stream")
                .post(body.toString().toRequestBody(JSON)).build()
            val call = client.newCall(request)
            turnCall = call
            val cancellation = CoroutineScope(currentCoroutineContext()).launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }
            try {
                call.await().use { response ->
                    if (!response.isSuccessful) throw failure(response.code, JSONObject(response.body?.string().orEmpty().ifBlank { "{}" }))
                    val source = response.body?.source() ?: throw IOException("empty response")
                    var event = "message"
                    val data = StringBuilder()
                    var completed = false
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        when {
                            line.isEmpty() -> {
                                if (data.isNotEmpty()) {
                                    val value = JSONObject(data.toString())
                                    if (event ==
                                        "error"
                                    ) {
                                        throw ApiFailure(
                                            value.optString("code"),
                                            value.optString("message", "응답을 완료하지 못했어요"),
                                            value.optBoolean("retryable")
                                        )
                                    }
                                    if (event == "done") completed = true
                                    onEvent(ServerEvent(event, value))
                                }
                                event = "message"
                                data.clear()
                            }
                            line.startsWith("event:") -> event = line.substringAfter(':').trim()
                            line.startsWith("data:") -> {
                                if (data.isNotEmpty()) data.append('\n')
                                data.append(line.substringAfter(':').trimStart())
                            }
                        }
                    }
                    if (!completed) throw ApiFailure("stream_interrupted", "연결이 잠시 끊겼어요. 말씀하신 내용은 기기에 보관했어요", true)
                }
            } finally {
                cancellation.cancel()
                if (turnCall === call) turnCall = null
            }
        }

    override fun cancelTurn() {
        turnCall?.cancel()
        turnCall = null
    }
    override fun close() {
        cancelTurn()
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
    }
    private fun failure(status: Int, body: JSONObject): ApiFailure {
        val error = body.optJSONObject("error")
        return ApiFailure(
            error?.optString("code") ?: "http_$status",
            error?.optString("message") ?: "연결을 확인하고 다시 시도해 주세요",
            error?.optBoolean("retryable") ?: (status >= 500)
        )
    }
    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        fun mutation() = JSONObject().put("request_id", UUID.randomUUID().toString())
    }
}
