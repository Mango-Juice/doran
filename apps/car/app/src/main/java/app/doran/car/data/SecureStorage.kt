package app.doran.car.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.room.*
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keystore key is non-exportable; only ciphertext is written to disk or Room. */
class SecureStorage(context: Context, preferencesName: String = "doran_secure", private val keyAlias: String = "doran.v1") {
    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val key: SecretKey by lazy {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()
            )
            generateKey()
        }
    }
    fun encrypt(text: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
    fun decrypt(text: String): String {
        val bytes = Base64.decode(text, Base64.NO_WRAP)
        require(bytes.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        }
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }
    fun get(name: String): String? = preferences.getString(name, null)?.let(::decrypt)
    fun put(name: String, value: String) {
        check(preferences.edit().putString(name, encrypt(value)).commit())
    }
    fun remove(name: String) {
        check(preferences.edit().remove(name).commit())
    }

    /** Session completion must not leave a finish marker after removing its session identity. */
    fun removeAll(vararg names: String) {
        if (names.isEmpty()) return
        val editor = preferences.edit()
        names.forEach { editor.remove(it) }
        check(editor.commit())
    }
}

@Entity(tableName = "pending_turns")
data class PendingTurn(@PrimaryKey val requestId: String, val sessionId: String, val ciphertext: String, val createdAt: Long)

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(turn: PendingTurn)

    @Query("SELECT * FROM pending_turns ORDER BY createdAt ASC")
    suspend fun all(): List<PendingTurn>

    @Query("DELETE FROM pending_turns WHERE requestId = :id")
    suspend fun remove(id: String)

    @Query("DELETE FROM pending_turns WHERE sessionId = :sessionId")
    suspend fun removeSession(sessionId: String)

    @Query("DELETE FROM pending_turns")
    suspend fun clear()
}

@Database(entities = [PendingTurn::class], version = 1, exportSchema = false)
abstract class DoranDatabase : RoomDatabase() {
    abstract fun outbox(): OutboxDao
    companion object {
        fun open(context: Context): DoranDatabase = Room.databaseBuilder(context, DoranDatabase::class.java, "doran.db").build()
    }
}
