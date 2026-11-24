package app.callscribe.backup

import app.callscribe.Logx
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.provider.MediaStore
import androidx.room.withTransaction
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.callscribe.AppGraph
import app.callscribe.CallScribeApp
import app.callscribe.data.AppSettings
import app.callscribe.data.PolicyEntity
import app.callscribe.data.RecordingEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Android glue for the Drive backup: scheduling, the DB as source/sink, and audio file access. */
class DriveBackup(private val context: Context, private val graph: AppGraph) {
    companion object {
        private const val TAG = "DriveBackup"
        private const val PERIODIC = "drive-backup"
        private const val NOW = "drive-backup-now"
        private const val NEXT = "drive-backup-next"
        private const val RESTORE = "drive-restore"
        /**
         * Stay inside the job time limit: 10 minutes on stock Android, but Samsung was measured stopping this job after
         * 5 minutes. The rest continues in a follow-up job.
         */
        private val BUDGET_MS = TimeUnit.MINUTES.toMillis(4)
    }

    val settings = BackupSettingsRepository(context)
    val auth by lazy { DriveAuth(context) }
    private val mutex = Mutex()
    private val wm get() = WorkManager.getInstance(context)

    // ---- scheduling ----

    private fun constraints(s: DriveBackupSettings) = Constraints.Builder()
        .setRequiredNetworkType(if (s.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresCharging(s.chargingOnly)
        .build()

    /** Call after any change to the backup settings. */
    suspend fun reschedule() {
        val s = settings.get()
        if (!s.enabled) {
            wm.cancelUniqueWork(PERIODIC); wm.cancelUniqueWork(NEXT); return
        }
        wm.enqueueUniquePeriodicWork(
            PERIODIC, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<DriveBackupWorker>(1, TimeUnit.DAYS)
                .setConstraints(constraints(s))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofMinutes(15))
                .build(),
        )
    }

    /** "Back up now": any network, not waiting for Wi-Fi or charging. */
    fun backupNow() {
        wm.enqueueUniqueWork(
            NOW, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<DriveBackupWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
    }

    /** This phone: a random id (created once) and its name from Settings → About phone. */
    suspend fun device(): BackupDevice {
        var id = settings.get().deviceId
        if (id.isBlank()) {
            id = java.util.UUID.randomUUID().toString()
            settings.update { it.copy(deviceId = id) }
        }
        val name = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER} ${Build.MODEL}"
        return BackupDevice(id, name)
    }

    /** Backups of all phones in the connected Google account (network). */
    suspend fun backups(): List<BackupInfo> = engine().backups()

    fun restore(from: BackupInfo, downloadAudio: Boolean) {
        wm.enqueueUniqueWork(
            RESTORE, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<DriveBackupWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(
                    workDataOf(
                        DriveBackupWorker.KEY_MODE to DriveBackupWorker.MODE_RESTORE, DriveBackupWorker.KEY_AUDIO to downloadAudio,
                        DriveBackupWorker.KEY_FOLDER to from.folderId, DriveBackupWorker.KEY_NAME to from.name,
                    )
                )
                .build(),
        )
    }

    private suspend fun continueLater() {
        wm.enqueueUniqueWork(
            NEXT, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<DriveBackupWorker>().setConstraints(constraints(settings.get()))
                .setInitialDelay(Duration.ofMinutes(1)).build(),
        )
    }

    suspend fun disconnect() {
        auth.disconnect()
        settings.update { it.copy(enabled = false, needsSignIn = false) }
        reschedule()
    }

    // ---- running ----

    /** Contact groups feed the cloud-retention plan so group policies apply to the Drive copy too. */
    private suspend fun engine(): DriveBackupEngine {
        val groups = graph.contactGroups.index()
        return DriveBackupEngine(DriveRestApi(auth::token), planner = { r, p, now -> app.callscribe.rules.Retention.plan(r, p, now, groups = groups) })
    }

    /** [restoreFrom] null = back up; otherwise restore from that phone's backup. */
    /** True while a backup or restore runs (for the "Backing up" status). */
    val running = kotlinx.coroutines.flow.MutableStateFlow(false)

    suspend fun run(restoreFrom: BackupInfo?, downloadAudio: Boolean): ListenableWorker.Result = mutex.withLock {
        running.value = true
        try { runLocked(restoreFrom, downloadAudio) } finally { running.value = false }
    }

    private suspend fun runLocked(restoreFrom: BackupInfo?, downloadAudio: Boolean): ListenableWorker.Result {
        val restore = restoreFrom != null
        val s = settings.get()
        if (!restore && !s.enabled) return ListenableWorker.Result.success()
        val start = System.currentTimeMillis()
        settings.update { it.copy(lastAttemptMs = start) }
        return try {
            if (restore) {
                val msg = doRestore(restoreFrom!!, downloadAudio)
                settings.update { it.copy(lastResult = msg, needsSignIn = false) }
            } else {
                val rep = engine().backup(device(), source, s.includeAudio, start / 1000) { System.currentTimeMillis() - start > BUDGET_MS }
                Logx.i(TAG, rep.line())
                settings.update {
                    it.copy(
                        lastResult = rep.line(), needsSignIn = false,
                        lastSuccessMs = if (rep.failures == 0) System.currentTimeMillis() else it.lastSuccessMs,
                    )
                }
                if (rep.incomplete) continueLater()
            }
            ListenableWorker.Result.success()
        } catch (e: DriveAuthRequiredException) {
            settings.update { it.copy(needsSignIn = true, lastResult = e.message.orEmpty()) }
            graph.notifier.notifyBackupSignIn()
            ListenableWorker.Result.success()
        } catch (e: IOException) {
            settings.update { it.copy(lastResult = "Network error, will retry: ${e.message}") }
            ListenableWorker.Result.retry()
        } catch (e: Exception) {
            Logx.w(TAG, "backup failed", e)
            settings.update { it.copy(lastResult = "Failed: ${e.message ?: e.javaClass.simpleName}") }
            ListenableWorker.Result.failure()
        }
    }

    private val source = object : BackupSource {
        override suspend fun recordings() = graph.db.recordings().all()
        override suspend fun policies(): List<PolicyEntity> {
            graph.db.policies().ensureGroupDefaults()
            return graph.db.policies().all()
        }
        override suspend fun settings() = graph.settings.get()
        override fun openAudio(r: RecordingEntity): UploadContent.Stream? {
            val uri = r.uri.takeIf { it.isNotBlank() }?.let(Uri::parse) ?: return null
            val size = runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull()
                ?.takeIf { it > 0 } ?: return null
            val name = BackupFormat.audioFileName(r)
            return UploadContent.Stream(size, BackupFormat.audioMime(name)) {
                context.contentResolver.openInputStream(uri) ?: throw IOException("can't open $name")
            }
        }
    }

    private val sink = object : RestoreSink {
        private val dao get() = graph.db.recordings()
        override suspend fun byHash(hash: String) = dao.byHash(hash)
        override suspend fun insert(r: RecordingEntity) { dao.insert(r) }
        override suspend fun update(r: RecordingEntity) = dao.update(r)
        override suspend fun replacePolicies(policies: List<PolicyEntity>) = graph.db.withTransaction {
            val p = graph.db.policies()
            p.all().forEach { p.delete(it.id) }
            policies.forEach { p.insert(it) }
            p.ensureGroupDefaults()
        }
        override suspend fun applySettings(cloud: AppSettings) =
            graph.settings.update { local -> DriveBackupEngine.restoredSettings(local, cloud) }
    }

    private suspend fun rescan() = runCatching {
        graph.syncFiles(graph.scanner.list(graph.settings.get().pathPrefixes))
    }.onFailure { Logx.w(TAG, "rescan after restore failed", it) }

    private suspend fun doRestore(from: BackupInfo, downloadAudio: Boolean): String {
        val e = engine()
        val rep = e.restore(sink, from, System.currentTimeMillis())
        rescan() // re-links restored rows to audio already on this phone, by content hash
        var downloaded = 0
        var missing = e.missingAudio(graph.db.recordings().all(), from)
        if (downloadAudio) {
            val folder = graph.settings.get().pathPrefixes.firstOrNull() ?: "Recordings/Call"
            for (a in missing) if (runCatching { saveAudio(e, a, folder) }.onFailure { Logx.w(TAG, "download ${a.name}", it) }.isSuccess) downloaded++
            if (downloaded > 0) rescan()
            missing = e.missingAudio(graph.db.recordings().all(), from)
        }
        return buildString {
            append("Restored from ${from.name}: ${rep.inserted} calls, updated ${rep.merged}")
            if (rep.policies > 0) append(", ${rep.policies} policies")
            if (rep.settings) append(", settings")
            if (downloaded > 0) append(" · downloaded $downloaded recordings")
            if (missing.isNotEmpty()) append(" · ${missing.size} recordings only on Drive")
        }
    }

    /** Writes a recording from Drive into the recordings folder; the next scan re-links it by hash. */
    private suspend fun saveAudio(e: DriveBackupEngine, a: CloudAudio, folder: String) = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, a.name)
            put(MediaStore.Audio.Media.MIME_TYPE, BackupFormat.audioMime(a.name))
            put(MediaStore.Audio.Media.RELATIVE_PATH, folder.trimEnd('/') + "/")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IOException("can't create ${a.name}")
        try {
            e.download(a) { input -> resolver.openOutputStream(uri)!!.use { input.copyTo(it) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
    }
}

class DriveBackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    companion object {
        const val KEY_MODE = "mode"
        const val KEY_AUDIO = "download_audio"
        const val MODE_RESTORE = "restore"
        const val KEY_FOLDER = "folder_id"
        const val KEY_NAME = "device_name"
    }

    override suspend fun doWork(): Result {
        val drive = (applicationContext as CallScribeApp).graph.drive
        val from = if (inputData.getString(KEY_MODE) == MODE_RESTORE) {
            BackupInfo(inputData.getString(KEY_FOLDER) ?: return Result.failure(), "", inputData.getString(KEY_NAME).orEmpty(), null, null, null)
        } else null
        return drive.run(restoreFrom = from, downloadAudio = inputData.getBoolean(KEY_AUDIO, false))
    }
}
