package app.doran.car

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Process
import android.os.SystemClock
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.doran.car.data.ApiFailure
import app.doran.car.data.DoranApi
import app.doran.car.data.DoranDatabase
import app.doran.car.data.PendingTurn
import app.doran.car.data.SecureStorage
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.security.KeyStore
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Three explicitly orchestrated invocations, with force-stop and server stop/restart between them.
 * Uses the real API parser, sockets, Room and Keystore, but a loopback fixture, synthetic text and
 * fake authentication. This is not a live Supabase, ViewModel, microphone or voice-flow test.
 * The interceptor has no route to the configured cloud and never forwards its authentication.
 */
@RunWith(AndroidJUnit4::class)
class SocketReplayTest {
    @Test
    fun runSelectedPhase(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit opt-in required", arguments.getString("run_socket_replay") == "true")
        val target = instrumentation.targetContext
        assertEquals("Refuse any other package", "app.doran.car.debug", target.packageName)
        assertTrue(target.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        val run = arguments.getString("qa_run") ?: error("qa_run is required")
        assertEquals("Use a canonical UUID namespace", UUID.fromString(run).toString(), run)
        val phase = arguments.getString("qa_phase") ?: error("qa_phase is required")
        assertTrue(phase in setOf("persist_disconnect", "offline_after_restart", "replay_after_restart", "cleanup"))
        val port = arguments.getString("qa_port")?.toIntOrNull() ?: error("qa_port is required")
        assertTrue(port in 1024..65535)
        val owned = OwnedRun(target, run)
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (phase == "persist_disconnect") owned.assertAbsent(keyStore) else owned.assertOwned(keyStore)
        val report = JSONObject().put("phase", phase).put("started_at", Instant.now().toString())
            .put("process_id", Process.myPid()).put("process_start_elapsed_ms", Process.getStartElapsedRealtime())
            .put("guest_elapsed_ms", SystemClock.elapsedRealtime())
            .put("scope", "real client sockets and local persistence against a synthetic loopback fixture")
            .put("cloud_api_called", false).put("microphone_or_audio_called", false)
            .put("release_account_accessed", false).put("production_server_idempotency_verified", false)
        owned.directory.mkdirs()
        var database: DoranDatabase? = null
        var api: DoranApi? = null
        try {
            if (phase == "cleanup") {
                owned.deleteOwned(keyStore)
                report.put("status", "OWNED_STORAGE_REMOVED")
            } else {
                val destinations = AtomicInteger()
                val secure = owned.secure()
                val client = loopbackClient(port, run, destinations)
                val activeApi = DoranApi(secure, client)
                api = activeApi
                assertTrue("Configured client required; all requests still go only to loopback", activeApi.configured)
                val activeDatabase = owned.openDatabase()
                database = activeDatabase
                when (phase) {
                    "persist_disconnect" -> {
                        val session = UUID.randomUUID().toString()
                        val request = UUID.randomUUID().toString()
                        val payload = JSONObject().put("request_id", request).put("text", PHRASE)
                            .put("context", JSONObject().put("source", "synthetic_qa").put("passenger_state", "unknown"))
                        secure.put("qa_owner", run)
                        secure.put("active_session", session)
                        val pending = PendingTurn(request, session, secure.encrypt(payload.toString()), System.currentTimeMillis())
                        activeDatabase.outbox().insert(pending)
                        val metadata = owned.newMetadata(pending)
                        owned.writeMetadata(metadata)
                        var doneReceived = false
                        var serverInstance: String? = null
                        val failure = try {
                            activeApi.turn(session, payload) { event ->
                                if (event.name == "start") serverInstance = event.body.getString("server_instance")
                                if (event.name == "done") doneReceived = true
                            }
                            null
                        } catch (error: IOException) {
                            error
                        }
                        assertTrue("A committed stream ending before done must fail", failure is ApiFailure)
                        assertEquals("stream_interrupted", (failure as ApiFailure).code)
                        assertFalse("No acknowledgement means no local deletion", doneReceived)
                        assertNotNull("A real start frame must arrive before the disconnect", serverInstance)
                        assertEquals(listOf(pending), activeDatabase.outbox().all())
                        val status = activeApi.request("/qa/replay-status/$request")
                        assertServerState(status, pending, attempts = 1)
                        assertEquals(serverInstance, status.getString("server_instance"))
                        metadata.put("stage", "persisted_after_disconnect").put("first_server_instance", serverInstance)
                        owned.writeMetadata(metadata)
                        report.put("status", "STREAM_INTERRUPTED_PENDING_PRESERVED").put("fixture_row_count", 1)
                            .put("fixture_attempts", 1).put("stream_error_code", failure.code)
                            .put("pending_cipher_sha256", sha256(pending.ciphertext))
                    }
                    "offline_after_restart" -> {
                        val metadata = owned.readMetadata()
                        assertEquals("persisted_after_disconnect", metadata.getString("stage"))
                        assertNewProcess(metadata)
                        val pending = readOriginalPending(owned, secure, activeDatabase, metadata)
                        var receivedEvent = false
                        val failure = try {
                            activeApi.turn(pending.sessionId, JSONObject(secure.decrypt(pending.ciphertext))) { receivedEvent = true }
                            null
                        } catch (error: IOException) {
                            error
                        }
                        assertNotNull("The peer must be unavailable in this phase", failure)
                        assertFalse("HTTP/API rejection is not a disconnected transport", failure is ApiFailure)
                        assertFalse("No SSE event may arrive while the listener is offline", receivedEvent)
                        assertEquals(listOf(pending), activeDatabase.outbox().all())
                        metadata.put("stage", "offline_reopened").put("offline_process_id", Process.myPid())
                        owned.writeMetadata(metadata)
                        report.put("status", "NEW_PROCESS_OFFLINE_PENDING_PRESERVED")
                            .put("prior_process_id", metadata.getInt("first_process_id"))
                            .put("transport_error_class", failure!!.javaClass.simpleName)
                            .put("pending_cipher_sha256", sha256(pending.ciphertext))
                    }
                    "replay_after_restart" -> {
                        val metadata = owned.readMetadata()
                        assertEquals("offline_reopened", metadata.getString("stage"))
                        assertNewProcess(metadata)
                        assertNotEquals("Restart after the offline attempt as well", metadata.getInt("offline_process_id"), Process.myPid())
                        val pending = readOriginalPending(owned, secure, activeDatabase, metadata)
                        var doneCount = 0
                        var turnId: String? = null
                        activeApi.turn(pending.sessionId, JSONObject(secure.decrypt(pending.ciphertext))) { event ->
                            if (event.name == "done") {
                                doneCount++
                                turnId = event.body.getString("turn_id")
                                activeDatabase.outbox().remove(pending.requestId)
                            }
                        }
                        assertEquals("Exactly one final acknowledgement", 1, doneCount)
                        assertTrue("Only done allows the pending entry to be removed", activeDatabase.outbox().all().isEmpty())
                        val status = activeApi.request("/qa/replay-status/${pending.requestId}")
                        assertServerState(status, pending, attempts = 2)
                        assertEquals(turnId, status.getString("turn_id"))
                        assertNotEquals(
                            "The fixture server must have actually restarted",
                            metadata.getString("first_server_instance"),
                            status.getString("server_instance")
                        )
                        metadata.put("stage", "replay_acknowledged")
                        owned.writeMetadata(metadata)
                        report.put("status", "REPLAY_ACKNOWLEDGED_FIXTURE_ROW_STILL_ONE")
                            .put("fixture_row_count", 1).put("fixture_attempts", 2).put("done_count", doneCount)
                            .put("prior_process_id", metadata.getInt("first_process_id"))
                    }
                }
                report.put("loopback_request_count", destinations.get())
                assertTrue("The real HTTP client must issue a loopback request", destinations.get() > 0)
            }
        } catch (error: Throwable) {
            report.put("status", "FAILED").put("error_class", error.javaClass.simpleName)
            throw error
        } finally {
            api?.close()
            database?.close()
            report.put("finished_at", Instant.now().toString())
            File(owned.directory, "$phase-${Process.myPid()}.json").writeText(report.toString(2))
        }
        Unit
    }

    private fun assertNewProcess(metadata: JSONObject) {
        assertNotEquals("Force-stop/relaunch the debug target between phases", metadata.getInt("first_process_id"), Process.myPid())
        assertTrue(
            "A new process must start after the initial process",
            Process.getStartElapsedRealtime() > metadata.getLong("first_process_start_ms")
        )
    }

    private suspend fun readOriginalPending(
        owned: OwnedRun,
        secure: SecureStorage,
        database: DoranDatabase,
        metadata: JSONObject
    ): PendingTurn {
        assertEquals(owned.run, secure.get("qa_owner"))
        val pending = database.outbox().all().single()
        assertEquals(metadata.getString("request_id"), pending.requestId)
        assertEquals(metadata.getString("session_id"), pending.sessionId)
        assertEquals(pending.sessionId, secure.get("active_session"))
        assertEquals(
            "Do not recreate or re-encrypt the original row after process death",
            metadata.getString("cipher_sha256"),
            sha256(pending.ciphertext)
        )
        val payload = JSONObject(secure.decrypt(pending.ciphertext))
        assertEquals(PHRASE, payload.getString("text"))
        assertEquals(pending.requestId, payload.getString("request_id"))
        return pending
    }

    private fun assertServerState(status: JSONObject, pending: PendingTurn, attempts: Int) {
        assertTrue(status.getBoolean("fixture"))
        assertEquals(1, status.getInt("row_count"))
        assertEquals(attempts, status.getInt("attempts"))
        assertEquals(pending.requestId, status.getString("request_id"))
        assertEquals(pending.sessionId, status.getString("session_id"))
        assertEquals(sha256(PHRASE), status.getString("text_sha256"))
    }

    private fun loopbackClient(port: Int, run: String, destinations: AtomicInteger): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).writeTimeout(5, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .proxy(Proxy.NO_PROXY)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                if (hostname != "127.0.0.1") throw UnknownHostException("This test permits loopback only")
                return listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
            }
        })
        .addInterceptor { chain ->
            val original = chain.request()
            val path = original.url.encodedPath
            if (path !in setOf("/auth/v1/signup", "/auth/v1/token") &&
                !path.matches(Regex("/functions/v1/doran-api/v1/sessions/[0-9a-f-]{36}/turns")) &&
                !path.matches(Regex("/functions/v1/doran-api/v1/qa/replay-status/[0-9a-f-]{36}"))
            ) {
                throw IOException("Path is outside the isolated socket fixture")
            }
            val local = original.url.newBuilder().scheme("http").host("127.0.0.1").port(port).build()
            destinations.incrementAndGet()
            chain.proceed(
                original.newBuilder().url(local).header("Authorization", "Bearer $FIXTURE_AUTH")
                    .header("apikey", FIXTURE_AUTH).header("X-Doran-QA-Run", run).build()
            )
        }.build()

    private class OwnedRun(private val context: Context, val run: String) {
        private val namespace = "doran_socket_qa_$run"
        private val databaseName = "$namespace.db"
        private val preferencesName = "${namespace}_preferences"
        private val keyAlias = "$namespace.key"
        val directory = File(context.filesDir, "qa-socket-replay/$run")
        private val metadataFile = File(directory, "ownership.json")

        fun secure() = SecureStorage(context, preferencesName, keyAlias)
        fun openDatabase(): DoranDatabase = Room.databaseBuilder(context, DoranDatabase::class.java, databaseName).build()
        fun readMetadata() = JSONObject(metadataFile.readText())
        fun writeMetadata(value: JSONObject) = metadataFile.writeText(value.toString(2))
        fun newMetadata(pending: PendingTurn) = JSONObject().put("schema", "doran-socket-replay-v1")
            .put("run", run).put("namespace", namespace).put("stage", "preparing")
            .put("first_process_id", Process.myPid()).put("first_process_start_ms", Process.getStartElapsedRealtime())
            .put("request_id", pending.requestId).put("session_id", pending.sessionId)
            .put("cipher_sha256", sha256(pending.ciphertext))

        private fun preferenceFile() = File(context.applicationInfo.dataDir, "shared_prefs/$preferencesName.xml")
        private fun ownedFiles(): List<File> {
            val database = context.getDatabasePath(databaseName)
            val preferences = preferenceFile()
            return listOf(
                database,
                File("${database.path}-wal"),
                File("${database.path}-shm"),
                File("${database.path}-journal"),
                preferences,
                File("${preferences.path}.bak")
            )
        }

        fun assertAbsent(keyStore: KeyStore) {
            assertFalse("Use a new run namespace", directory.exists())
            assertFalse("No database, sidecar or preference file may predate this run", ownedFiles().any(File::exists))
            assertFalse(keyStore.containsAlias(keyAlias))
        }

        fun assertOwned(keyStore: KeyStore) {
            assertTrue("The initial phase must create an ownership marker", metadataFile.isFile)
            val metadata = readMetadata()
            assertEquals("doran-socket-replay-v1", metadata.getString("schema"))
            assertEquals(run, metadata.getString("run"))
            assertEquals(namespace, metadata.getString("namespace"))
            assertTrue("Do not create a replacement key during recovery", keyStore.containsAlias(keyAlias))
            assertTrue(preferenceFile().isFile)
            assertEquals(run, secure().get("qa_owner"))
        }

        fun deleteOwned(keyStore: KeyStore) {
            context.deleteDatabase(databaseName)
            assertFalse(context.getDatabasePath(databaseName).exists())
            assertTrue(context.deleteSharedPreferences(preferencesName))
            keyStore.deleteEntry(keyAlias)
            assertFalse(keyStore.containsAlias(keyAlias))
            assertFalse("Remove only this run's database, sidecars and preferences", ownedFiles().any(File::exists))
            // Keep private reports and ownership evidence; never enumerate or remove other keys/files.
        }
    }

    companion object {
        private const val PHRASE = "합성 시험: 네트워크가 끊겨도 이 문장은 한 번만 저장해 주세요."
        private const val FIXTURE_AUTH = "doran-socket-fixture-only"
        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
