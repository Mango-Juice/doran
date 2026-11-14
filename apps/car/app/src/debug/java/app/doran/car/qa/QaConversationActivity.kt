package app.doran.car.qa

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.Room
import app.doran.car.CarViewModel
import app.doran.car.data.DoranDatabase
import app.doran.car.data.SecureStorage
import app.doran.car.ui.CarScreen
import app.doran.car.ui.DoranTheme
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Debug-only host. A run must be registered in this process before an activity can use it. */
class QaConversationActivity : ComponentActivity() {
    lateinit var run: QaConversationRun
        private set
    lateinit var model: CarViewModel
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(packageName == "app.doran.car.debug" && applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        run = checkNotNull(QaConversationRun.find(intent.getStringExtra(EXTRA_RUN)))
        run.events.add("activity_created")
        model = ViewModelProvider(
            this,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    check(modelClass == CarViewModel::class.java)
                    return CarViewModel(application, run.secure, run.database, { interrupted ->
                        SyntheticSpeech(interrupted).also { run.speech = it }
                    }, run.api) as T
                }
            }
        )[CarViewModel::class.java]
        setContent {
            val state = model.state.collectAsStateWithLifecycle(lifecycleOwner = this@QaConversationActivity).value
            DoranTheme {
                Column(Modifier.fillMaxSize()) {
                    Text("합성 대화 시험 · 마이크와 음성 재생 없음", color = MaterialTheme.colorScheme.onBackground)
                    androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                        CarScreen(
                            state,
                            model::start,
                            { model.pause() },
                            model::finish,
                            model::connect,
                            model::confirmPairing,
                            {},
                            model::setSoloAsserted
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        model.foreground()
        run.events.add("activity_started")
    }

    override fun onStop() {
        run.events.add("activity_stopped")
        model.background()
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        run.events.add("activity_destroyed")
    }

    companion object {
        const val EXTRA_RUN = "qa_run_id"
    }
}

/** Exact UUID namespaces are owned only after a collision check; no app-default storage is read. */
class QaConversationRun private constructor(private val context: Context) {
    val id: String = UUID.randomUUID().toString()
    private val namespace = "doran_conversation_qa_$id"
    private val preferencesName = "${namespace}_preferences"
    private val keyAlias = "$namespace.key"
    private val databaseName = "$namespace.db"
    val events = CopyOnWriteArrayList<String>()
    val api = SyntheticConversationApi()
    lateinit var speech: SyntheticSpeech
    val secure: SecureStorage
    val database: DoranDatabase

    init {
        check(context.packageName == "app.doran.car.debug")
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        check(!store.containsAlias(keyAlias))
        check(databaseFiles().none(File::exists))
        check(preferenceFiles().none(File::exists))
        secure = SecureStorage(context, preferencesName, keyAlias)
        database = Room.databaseBuilder(context, DoranDatabase::class.java, databaseName).build()
    }

    fun dispose() {
        registry.remove(id, this)
        val failures = mutableListOf<Throwable>()
        fun attempt(action: () -> Unit) {
            try {
                action()
            } catch (error: Throwable) {
                failures.add(error)
            }
        }
        attempt { database.close() }
        attempt {
            context.deleteDatabase(databaseName)
            check(databaseFiles().none(File::exists))
        }
        attempt {
            context.deleteSharedPreferences(preferencesName)
            check(preferenceFiles().none(File::exists))
        }
        attempt {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (store.containsAlias(keyAlias)) store.deleteEntry(keyAlias)
            check(!store.containsAlias(keyAlias))
        }
        if (failures.isNotEmpty()) {
            throw AssertionError("Could not remove every owned QA resource").also { failure -> failures.forEach(failure::addSuppressed) }
        }
    }

    private fun databaseFiles() = listOf("", "-wal", "-shm", "-journal").map { suffix ->
        File(context.getDatabasePath(databaseName).path + suffix)
    }

    private fun preferenceFiles() = listOf(".xml", ".xml.bak").map { suffix ->
        File(context.applicationInfo.dataDir, "shared_prefs/$preferencesName$suffix")
    }

    companion object {
        private val registry = ConcurrentHashMap<String, QaConversationRun>()
        fun create(context: Context): QaConversationRun = QaConversationRun(context.applicationContext).also { registry[it.id] = it }
        internal fun find(id: String?): QaConversationRun? = id?.let(registry::get)
    }
}
