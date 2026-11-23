package app.callscribe.detect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.callscribe.CallScribeApp
import app.callscribe.data.RecordingEntity
import app.callscribe.data.Status
import app.callscribe.pipeline.ProcessWorker
import app.callscribe.rules.PolicyResolver
import java.time.Duration
import java.util.concurrent.TimeUnit

object WorkScheduler {
    private const val TRIGGER = "scan-trigger"
    private const val PERIODIC = "scan-periodic"

    fun armAll(context: Context) {
        armTrigger(context)
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ScanWorker>(15, TimeUnit.MINUTES).build(),
        )
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "retention-daily", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<app.callscribe.pipeline.RetentionWorker>(1, TimeUnit.DAYS).build(),
        )
    }

    /** Content-URI trigger fires once per change batch; ScanWorker re-arms it every run. */
    fun armTrigger(context: Context) {
        val constraints = Constraints.Builder()
            .addContentUriTrigger(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true)
            .setTriggerContentUpdateDelay(Duration.ofSeconds(5))
            .setTriggerContentMaxDelay(Duration.ofSeconds(30))
            .build()
        val req = OneTimeWorkRequestBuilder<ScanWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(ScanWorker.KEY_FROM_TRIGGER to true))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(TRIGGER, ExistingWorkPolicy.REPLACE, req)
    }

    fun scanNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "scan-now", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<ScanWorker>().build(),
        )
    }

    fun enqueueProcess(context: Context, recordingId: Long, steps: Set<String>, force: Boolean) {
        val req = OneTimeWorkRequestBuilder<ProcessWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(30))
            .setInputData(
                workDataOf(
                    ProcessWorker.KEY_ID to recordingId,
                    ProcessWorker.KEY_STEPS to steps.joinToString(","),
                    ProcessWorker.KEY_FORCE to force,
                )
            )
            .build()
        val policy = if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        WorkManager.getInstance(context).enqueueUniqueWork("process-$recordingId", policy, req)
    }
}

class ScanWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    companion object {
        const val KEY_FROM_TRIGGER = "from_trigger"
        private const val TAG = "ScanWorker"
    }

    override suspend fun doWork(): Result {
        val graph = (applicationContext as CallScribeApp).graph
        try {
            var settings = graph.settings.get()
            if (settings.autoProcessFromSec == 0L) {
                // First run: only calls recorded from now on are processed automatically.
                val now = System.currentTimeMillis() / 1000
                graph.settings.update { it.copy(autoProcessFromSec = now) }
                settings = graph.settings.get()
            }
            val files = graph.scanner.list(settings.pathPrefixes)
            val result = graph.syncFiles(files)
            Log.i(TAG, "scan: ${files.size} files, ${result.newRows.size} new, ${result.relinked} relinked, ${result.missing} missing")

            graph.db.policies().ensureGroupDefaults()
            val policies = graph.db.policies().all()
            for (row in result.newRows) {
                if (row.dateAddedSec < settings.autoProcessFromSec) continue
                handleNew(row, policies, settings)
            }
            return Result.success()
        } catch (e: SecurityException) {
            Log.w(TAG, "no media permission yet", e)
            return Result.success()
        } finally {
            if (inputData.getBoolean(KEY_FROM_TRIGGER, false)) WorkScheduler.armTrigger(applicationContext)
        }
    }

    private suspend fun handleNew(
        row: RecordingEntity,
        policies: List<app.callscribe.data.PolicyEntity>,
        settings: app.callscribe.data.AppSettings,
    ) {
        val graph = (applicationContext as CallScribeApp).graph
        val policy = PolicyResolver.forProcessing(row, policies)
        val canTranscribe = graph.providers.transcriber(settings)?.isConfigured() == true
        if (!settings.autoEnabled || !policy.transcribe) {
            graph.db.recordings().setStatus(row.id, Status.SKIPPED_BY_RULE)
            return
        }
        val steps = buildSet {
            if (settings.autoExport && settings.webhookUrl.isNotBlank()) add(ProcessWorker.STEP_EXPORT)
            if (canTranscribe) {
                add(ProcessWorker.STEP_TRANSCRIBE)
                if (policy.summarize) add(ProcessWorker.STEP_ANALYZE)
            }
        }
        if (steps.isEmpty()) return
        graph.db.recordings().setStatus(row.id, Status.QUEUED)
        WorkScheduler.enqueueProcess(applicationContext, row.id, steps, force = false)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WorkScheduler.armAll(context)
    }
}
