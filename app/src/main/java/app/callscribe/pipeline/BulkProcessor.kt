package app.callscribe.pipeline

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.callscribe.AppGraph
import app.callscribe.CallScribeApp
import app.callscribe.R
import app.callscribe.data.RecordingEntity
import app.callscribe.data.Status
import app.callscribe.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Duration

/** One bulk run: which calls, what it cost before it started, and where it stops. Persisted across restarts. */
data class BulkRun(val ids: List<Long>, val startUsd: Double, val capUsd: Double, val stoppedReason: String? = null)

/** Progress of a [BulkRun], computed from the calls themselves. Pure — unit-tested. */
data class BulkProgress(val total: Int, val done: Int, val failed: Int, val spentUsd: Double, val running: Boolean) {
    companion object {
        private val BUSY = setOf(Status.QUEUED, Status.TRANSCRIBING, Status.ANALYZING)
        fun of(run: BulkRun, rows: List<RecordingEntity>): BulkProgress {
            val mine = rows.filter { it.id in run.ids.toSet() }
            return BulkProgress(
                total = run.ids.size,
                done = mine.count { it.status !in BUSY },
                failed = mine.count { it.status == Status.FAILED },
                spentUsd = (mine.sumOf { it.totalCostUsd } - run.startUsd).coerceAtLeast(0.0),
                running = mine.any { it.status in BUSY },
            )
        }
    }
}

/**
 * Processes many calls in the background: calls go into a few sequential WorkManager chains ("lanes") so a cloud
 * provider gets a little parallelism and an on-device model runs one call at a time. Bulk jobs never fail the chain
 * (a failed call is recorded on the call itself), stop when the run is cancelled or over its spending cap, and
 * show one progress notification instead of one per call.
 */
class BulkProcessor(private val context: Context, private val graph: AppGraph) {
    companion object {
        const val TAG = "bulk"
        const val CHANNEL = "bulk"
        private const val NOTE_ID = -4
        private const val PREFS = "bulk_run"
        const val ACTION_CANCEL = "app.callscribe.BULK_CANCEL"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _run = MutableStateFlow(load())
    /** The current or last run (null = none yet / dismissed). */
    val run: StateFlow<BulkRun?> = _run

    private fun load(): BulkRun? {
        val ids = prefs.getString("ids", null)?.split(',')?.mapNotNull { it.toLongOrNull() } ?: return null
        return BulkRun(ids, prefs.getFloat("start", 0f).toDouble(), prefs.getFloat("cap", 0f).toDouble(), prefs.getString("stopped", null))
    }

    private fun save(r: BulkRun?) {
        prefs.edit().apply {
            if (r == null) clear() else {
                putString("ids", r.ids.joinToString(","))
                putFloat("start", r.startUsd.toFloat())
                putFloat("cap", r.capUsd.toFloat())
                putString("stopped", r.stoppedReason)
            }
        }.apply()
        _run.value = r
    }

    /** Queues [plan]. Calls already in a running batch are left where they are. */
    suspend fun start(plan: BulkPlan, capUsd: Double) {
        if (plan.isEmpty) return
        val s = graph.settings.get()
        val onDevice = graph.providers.isOnDevice(s)
        val analyze = plan.summarize && graph.providers.analyzer(s)?.isConfigured() == true
        val dao = graph.db.recordings()

        // Adding to a run that is still going extends it; otherwise this is a new run.
        val previous = _run.value?.takeIf { it.stoppedReason == null && BulkProgress.of(it, dao.all()).running }
        val ids = (previous?.ids.orEmpty() + plan.all.map { it.id }).distinct()
        val startUsd = (previous?.startUsd ?: 0.0) + plan.all.sumOf { it.totalCostUsd }
        save(BulkRun(ids, startUsd, (previous?.capUsd ?: 0.0) + capUsd))

        val lanes = if (onDevice) 1 else BulkEstimator.CLOUD_LANES
        val wm = WorkManager.getInstance(context)
        plan.all.withIndex().groupBy({ it.index % lanes }, { it.value }).forEach { (lane, rows) ->
            val reqs = rows.map { r ->
                dao.setStatus(r.id, Status.QUEUED)
                val steps = buildSet {
                    if (r.transcriptText == null) add(ProcessWorker.STEP_TRANSCRIBE)
                    if (analyze) add(ProcessWorker.STEP_ANALYZE)
                }
                val needsNetwork = !onDevice || ProcessWorker.STEP_ANALYZE in steps
                OneTimeWorkRequestBuilder<ProcessWorker>()
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(if (needsNetwork) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED)
                            .setRequiresCharging(onDevice && s.onDeviceOnlyWhileCharging)
                            .build()
                    )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(30))
                    .setInputData(
                        workDataOf(
                            ProcessWorker.KEY_ID to r.id,
                            ProcessWorker.KEY_STEPS to steps.joinToString(","),
                            ProcessWorker.KEY_FORCE to false,
                            ProcessWorker.KEY_BULK to true,
                        )
                    )
                    .addTag(TAG)
                    .build()
            }
            wm.beginUniqueWork("bulk-lane-$lane", ExistingWorkPolicy.APPEND_OR_REPLACE, reqs).enqueue()
        }
        updateNotification()
    }

    /** Stops the run: queued calls go back to where they were; a call already being processed finishes. */
    fun cancel(reason: String = "Cancelled") {
        val r = _run.value ?: return
        save(r.copy(stoppedReason = reason))
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
        scope.launch {
            val dao = graph.db.recordings()
            r.ids.forEach { id ->
                val row = dao.get(id) ?: return@forEach
                if (row.status == Status.QUEUED) dao.setStatus(id, restingStatus(row))
            }
            updateNotification()
        }
    }

    /** Hides the finished run's summary. */
    fun dismiss() {
        save(null)
        NotificationManagerCompat.from(context).cancel(NOTE_ID)
    }

    /**
     * Called by a bulk [ProcessWorker] before it does anything. False = this run was cancelled or went over its cap:
     * the call is put back and skipped.
     */
    suspend fun mayProceed(row: RecordingEntity): Boolean {
        val r = _run.value
        if (r == null || r.stoppedReason != null || row.id !in r.ids) {
            if (row.status == Status.QUEUED) graph.db.recordings().setStatus(row.id, restingStatus(row))
            return false
        }
        val p = BulkProgress.of(r, graph.db.recordings().all())
        if (p.spentUsd > r.capUsd) {
            cancel("Stopped: it cost more than the %s limit".format(app.callscribe.ui.Fmt.usd(r.capUsd)))
            return false
        }
        return true
    }

    fun restingStatus(r: RecordingEntity) = when {
        r.verdict != null -> Status.ANALYZED
        r.transcriptText != null -> Status.TRANSCRIBED
        else -> Status.NOT_TRANSCRIBED
    }

    /** One notification for the whole run, refreshed after each call. */
    suspend fun updateNotification() {
        val r = _run.value ?: return
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val p = BulkProgress.of(r, graph.db.recordings().all())
        val nm = NotificationManagerCompat.from(context)
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Bulk processing", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            context, NOTE_ID, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val spent = "spent ${app.callscribe.ui.Fmt.usd(p.spentUsd)}"
        val failed = if (p.failed > 0) " · ${p.failed} failed" else ""
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
        if (p.running && r.stoppedReason == null) {
            val cancel = PendingIntent.getBroadcast(
                context, NOTE_ID, Intent(context, BulkCancelReceiver::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE,
            )
            b.setContentTitle("Processing calls: ${p.done} of ${p.total}")
                .setContentText("$spent$failed")
                .setProgress(p.total, p.done, false)
                .setOngoing(true)
                .addAction(0, "Cancel", cancel)
        } else {
            b.setContentTitle(r.stoppedReason ?: "Processed ${p.done - p.failed} of ${p.total} calls")
                .setContentText("$spent$failed")
                .setAutoCancel(true)
        }
        nm.notify(NOTE_ID, b.build())
    }
}

/** The notification's Cancel button. */
class BulkCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BulkProcessor.ACTION_CANCEL) return
        (context.applicationContext as CallScribeApp).graph.bulk.cancel()
    }
}
