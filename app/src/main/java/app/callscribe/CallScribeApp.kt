package app.callscribe

import android.app.Application
import android.content.Context
import app.callscribe.data.AppDatabase
import app.callscribe.data.RecordingEntity
import app.callscribe.data.SecretStore
import app.callscribe.data.SettingsRepository
import app.callscribe.detect.ContactGroupsReader
import app.callscribe.detect.RecordingScanner
import app.callscribe.detect.RecordingStore
import app.callscribe.detect.SyncEngine
import app.callscribe.detect.WorkScheduler
import app.callscribe.pipeline.Notifier
import app.callscribe.providers.ProviderRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Tiny manual dependency graph. */
class AppGraph(context: Context) {
    val db = AppDatabase.create(context)
    val settings = SettingsRepository(context)
    val secrets = SecretStore(context)
    val scanner = RecordingScanner(context)
    val models = app.callscribe.providers.ondevice.ModelStore(context)
    val providers = ProviderRegistry(secrets, models)
    val notifier = Notifier(context)
    /** Optional Google Drive backup (off by default). */
    val drive by lazy { app.callscribe.backup.DriveBackup(context.applicationContext, this) }
    val contactGroups = ContactGroupsReader(context)
    /** Processing many calls at once (selection on the call list, onboarding). */
    val bulk by lazy { app.callscribe.pipeline.BulkProcessor(context.applicationContext, this) }

    private val syncMutex = Mutex()
    private val store = object : RecordingStore {
        private val dao = db.recordings()
        override suspend fun byMediaId(mediaId: Long) = dao.byMediaId(mediaId)
        override suspend fun byHash(hash: String) = dao.byHash(hash)
        override suspend fun insert(r: RecordingEntity) = dao.insert(r)
        override suspend fun update(r: RecordingEntity) = dao.update(r)
        override suspend fun all() = dao.all()
    }
    private val engine = SyncEngine(store, { f -> withContext(Dispatchers.IO) { scanner.sha256(f) } })

    /** Serialized so the content trigger and periodic scan never race. */
    suspend fun syncFiles(files: List<app.callscribe.detect.MediaFile>) = syncMutex.withLock { engine.sync(files) }
}

class CallScribeApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.notifier.createChannel()
        // Temp files from a compression that was interrupted (process killed).
        cacheDir.listFiles { f -> f.name.startsWith("pcm") || f.name.startsWith("compressed") }
            ?.filter { System.currentTimeMillis() - it.lastModified() > 60 * 60_000 }
            ?.forEach { it.delete() }
        WorkScheduler.armAll(this)
    }
}
