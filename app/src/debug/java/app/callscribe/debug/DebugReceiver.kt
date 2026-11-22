package app.callscribe.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.callscribe.CallScribeApp
import app.callscribe.pipeline.AudioCompressor
import java.io.File

/**
 * Debug builds only. Lets a developer exercise parts of the pipeline over adb:
 *   adb shell am broadcast -n app.callscribe/.debug.DebugReceiver -a app.callscribe.DEBUG_COMPRESS --el id 12
 *   adb shell am broadcast -n app.callscribe/.debug.DebugReceiver -a app.callscribe.DEBUG_DUMP
 * Work runs in WorkManager (like the real pipeline) so Samsung's background freezer doesn't stall it.
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val req = OneTimeWorkRequestBuilder<DebugWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(workDataOf("action" to intent.action, "id" to intent.getLongExtra("id", -1)))
            .build()
        WorkManager.getInstance(context).enqueue(req)
    }
}

class DebugWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun getForegroundInfo() =
        androidx.work.ForegroundInfo(
            999,
            androidx.core.app.NotificationCompat.Builder(applicationContext, app.callscribe.pipeline.Notifier.CHANNEL)
                .setSmallIcon(app.callscribe.R.drawable.ic_launcher_foreground).setContentTitle("Debug").build(),
        )

    override suspend fun doWork(): Result {
        val graph = (applicationContext as CallScribeApp).graph
        try {
            when (inputData.getString("action")) {
                "app.callscribe.DEBUG_COMPRESS" -> {
                    val id = inputData.getLong("id", -1)
                    val r = graph.db.recordings().get(id) ?: error("no recording $id")
                    val t0 = System.currentTimeMillis()
                    val bytes = AudioCompressor(applicationContext).compress(Uri.parse(r.uri))
                    val out = File(applicationContext.getExternalFilesDir(null), "compressed-$id.m4a")
                    out.writeBytes(bytes)
                    Log.i(TAG, "compressed $id: ${r.sizeBytes} -> ${bytes.size} bytes in ${System.currentTimeMillis() - t0} ms")
                }
                "app.callscribe.DEBUG_CLAUDE_SMOKE" -> {
                    // Uses a deliberately invalid key: proves the SDK loads, builds the request (incl. JSON schema)
                    // and maps a 401 — without needing or spending a real key.
                    val an = app.callscribe.providers.claude.ClaudeAnalyzer("sk-ant-invalid-smoke-test", "claude-haiku-5-5")
                    Log.i(TAG, "claude test(): " + runCatching { an.test() }.fold({ it }, { "${it.javaClass.simpleName}: ${it.message}" }))
                    val meta = app.callscribe.providers.CallMeta("050-0000000", "Suspected spam", "SPAM", 30, "Mon 10:00")
                    Log.i(TAG, "claude analyze(): " + runCatching { an.analyze("שלום, זו הודעה מוקלטת", meta) }
                        .fold({ it.toString() }, { "${it.javaClass.simpleName}: ${it.message}" }))
                }
                "app.callscribe.DEBUG_DUMP" -> {
                    val all = graph.db.recordings().all()
                    Log.i(TAG, "rows=${all.size} by kind=${all.groupingBy { it.labelKind }.eachCount()} by status=${all.groupingBy { it.status }.eachCount()}")
                    all.sortedByDescending { it.durationMs }.take(3).forEach {
                        Log.i(TAG, "longest id=${it.id} dur=${it.durationMs / 1000}s size=${it.sizeBytes}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "debug action failed", e)
        }
        return Result.success()
    }

    companion object { private const val TAG = "CallScribeDebug" }
}
