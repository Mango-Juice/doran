package app.doran.car

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.doran.car.data.DoranDatabase
import app.doran.car.data.PendingTurn
import app.doran.car.data.SecureStorage
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Room and Android Keystore persistence with synthetic text only. Reopens database/key handles;
 * it does not simulate process death, network replay, or acknowledgement by the server.
 * Never opens the app's default database, preferences, or key, even in the debug package.
 */
@RunWith(AndroidJUnit4::class)
class OutboxPersistenceTest {
    @Test
    fun encryptedTurnsSurviveReopenAndSessionDeletionStaysScoped(): Unit = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("Refuse any other target package", "app.doran.car.debug", target.packageName)
        assertTrue("Refuse a release target", target.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)

        val primary = IsolatedStorage(target)
        val neighbor = IsolatedStorage(target)
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        // Preflight precedes cleanup ownership: a collision must never delete existing resources.
        listOf(primary, neighbor).forEach { it.assertAbsent(keyStore) }
        var database: DoranDatabase? = null
        var failure: Throwable? = null
        try {
            val secure = primary.secure()
            val neighborText = "합성 시험: 별도 키 보존 ${UUID.randomUUID()}"
            val neighborCiphertext = neighbor.secure().encrypt(neighborText)
            neighbor.secure().put("sentinel", neighborText)

            val sessionA = UUID.randomUUID().toString()
            val sessionB = UUID.randomUUID().toString()
            val texts = listOf(
                "합성 시험: 첫 번째 보류 발화 ${UUID.randomUUID()}",
                "합성 시험: 두 번째 보류 발화 ${UUID.randomUUID()}",
                "합성 시험: 다른 세션 발화 ${UUID.randomUUID()}"
            )
            val payloads = texts.map { text ->
                JSONObject().put("request_id", UUID.randomUUID().toString()).put("text", text).toString()
            }
            val turns = payloads.mapIndexed { index, payload ->
                PendingTurn(
                    requestId = JSONObject(payload).getString("request_id"),
                    sessionId = if (index < 2) sessionA else sessionB,
                    ciphertext = secure.encrypt(payload),
                    createdAt = 1000L + index
                )
            }
            secure.put("active_session", sessionA)
            secure.put("pending_payload", payloads.first())
            database = primary.openDatabase()
            val initialOutbox = database.outbox()
            turns.forEach { initialOutbox.insert(it) }
            val replay = turns.first().copy(ciphertext = secure.encrypt(payloads.first()))
            assertNotEquals("AES-GCM must use a fresh IV", turns.first().ciphertext, replay.ciphertext)
            initialOutbox.insert(replay)
            assertEquals("Duplicate request must retain the original ordered rows", turns, initialOutbox.all())
            primary.assertNoPlaintext(texts)

            database.close()
            database = null
            assertTrue("Use an on-disk database", target.getDatabasePath(primary.databaseName).isFile)
            primary.assertNoPlaintext(texts)

            // A new SecureStorage instance loads the persisted key; a new Room handle reads disk.
            val reopenedSecure = primary.secure()
            assertEquals(sessionA, reopenedSecure.get("active_session"))
            assertEquals(payloads.first(), reopenedSecure.get("pending_payload"))
            database = primary.openDatabase()
            val reopenedOutbox = database.outbox()
            val recovered = reopenedOutbox.all()
            assertEquals(turns, recovered)
            assertEquals(payloads, recovered.map { reopenedSecure.decrypt(it.ciphertext) })
            reopenedOutbox.insert(replay)
            assertEquals("Idempotency must also survive reopening", turns, reopenedOutbox.all())

            reopenedOutbox.removeSession(sessionA)
            assertEquals("Only the requested session is removed", listOf(turns.last()), reopenedOutbox.all())
            database.close()
            database = null
            database = primary.openDatabase()
            val remaining = database.outbox().all()
            assertEquals("Scoped deletion must persist", listOf(turns.last()), remaining)
            assertEquals(payloads.last(), primary.secure().decrypt(remaining.single().ciphertext))
            assertEquals("Outbox deletion must not mutate preferences", sessionA, primary.secure().get("active_session"))
            database.close()
            database = null
            primary.assertNoPlaintext(texts)

            // Deleting one owned namespace must preserve the independently owned key/preferences.
            primary.deleteOwned(keyStore)
            assertTrue("The other test key must remain", keyStore.containsAlias(neighbor.keyAlias))
            assertEquals(neighborText, neighbor.secure().get("sentinel"))
            assertEquals(neighborText, neighbor.secure().decrypt(neighborCiphertext))
            neighbor.assertNoPlaintext(listOf(neighborText))
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            val cleanupErrors = mutableListOf<Throwable>()
            try {
                database?.close()
            } catch (error: Throwable) {
                cleanupErrors.add(error)
            }
            // Each cleanup is attempted even if another fails; never clear app data or enumerate keys.
            listOf(primary, neighbor).forEach { owned ->
                try {
                    owned.deleteOwned(keyStore)
                } catch (error: Throwable) {
                    cleanupErrors.add(error)
                }
            }
            if (cleanupErrors.isNotEmpty()) {
                val cleanupFailure = AssertionError("Could not remove all isolated test resources")
                cleanupErrors.forEach(cleanupFailure::addSuppressed)
                failure?.addSuppressed(cleanupFailure) ?: throw cleanupFailure
            }
        }
    }

    private class IsolatedStorage(private val context: Context) {
        private val namespace = "doran_outbox_instrumentation_${UUID.randomUUID()}"
        val databaseName = "$namespace.db"
        private val preferencesName = "${namespace}_preferences"
        val keyAlias = "$namespace.key"

        fun secure() = SecureStorage(context, preferencesName = preferencesName, keyAlias = keyAlias)

        fun openDatabase(): DoranDatabase = Room.databaseBuilder(context, DoranDatabase::class.java, databaseName).build()

        private fun ownedFiles(): List<File> {
            val database = context.getDatabasePath(databaseName)
            val preferences = File(context.applicationInfo.dataDir, "shared_prefs/$preferencesName.xml")
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
            assertFalse("Isolated storage files must not already exist", ownedFiles().any(File::exists))
            assertFalse("Isolated key must not already exist", keyStore.containsAlias(keyAlias))
        }

        fun assertNoPlaintext(texts: List<String>) {
            val encodings = listOf(Charsets.UTF_8, Charsets.UTF_16LE, Charsets.UTF_16BE)
            ownedFiles().filter(File::isFile).forEach { file ->
                val bytes = file.readBytes()
                texts.forEach { text ->
                    encodings.forEach { encoding ->
                        assertFalse("Synthetic utterance found unencrypted on disk", contains(bytes, text.toByteArray(encoding)))
                    }
                }
            }
        }

        fun deleteOwned(keyStore: KeyStore) {
            val errors = mutableListOf<Throwable>()
            val operations: List<() -> Unit> = listOf(
                {
                    context.deleteDatabase(databaseName)
                    Unit
                },
                {
                    context.deleteSharedPreferences(preferencesName)
                    Unit
                },
                { if (keyStore.containsAlias(keyAlias)) keyStore.deleteEntry(keyAlias) }
            )
            operations.forEach { operation ->
                try {
                    operation()
                } catch (error: Throwable) {
                    errors.add(error)
                }
            }
            try {
                assertAbsent(keyStore)
            } catch (error: Throwable) {
                errors.add(error)
            }
            if (errors.isNotEmpty()) {
                val cleanupFailure = AssertionError("Could not delete an isolated test namespace")
                errors.forEach(cleanupFailure::addSuppressed)
                throw cleanupFailure
            }
        }

        private fun contains(haystack: ByteArray, needle: ByteArray): Boolean = needle.size <= haystack.size &&
            (0..haystack.size - needle.size).any { start ->
                needle.indices.all { offset -> haystack[start + offset] == needle[offset] }
            }
    }
}
