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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Debug builds only. Lets a developer exercise parts of the pipeline over adb:
 *   adb shell am broadcast -n app.callscribe/.debug.DebugReceiver -a app.callscribe.DEBUG_COMPRESS --el id 12
 *   adb shell am broadcast -n app.callscribe/.debug.DebugReceiver -a app.callscribe.DEBUG_DUMP
 *   adb shell am broadcast -n app.callscribe/.debug.DebugReceiver -a app.callscribe.DEBUG_ONBOARDING   (show first-run setup again, keeps data)
 * Work runs in WorkManager (like the real pipeline) so Samsung's background freezer doesn't stall it.
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val req = OneTimeWorkRequestBuilder<DebugWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(workDataOf("action" to intent.action, "id" to intent.getLongExtra("id", -1), "threads" to intent.getIntExtra("threads", 2)))
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
                "app.callscribe.DEBUG_FAKE_RUNPOD" -> {
                    // Point the transcriber at a fake server on the dev PC (adb reverse tcp:8099 tcp:8099).
                    graph.settings.update { it.copy(runpodBaseUrl = "http://127.0.0.1:8099/v2", runpodEndpointId = "fake") }
                    graph.secrets.set(app.callscribe.data.SecretStore.Key.RUNPOD_API_KEY, "fake-key-not-a-secret")
                    Log.i(TAG, "transcriber -> fake server")
                }
                "app.callscribe.DEBUG_RESET_PROVIDER" -> {
                    graph.settings.update { it.copy(runpodBaseUrl = app.callscribe.data.AppSettings().runpodBaseUrl, runpodEndpointId = "") }
                    graph.secrets.set(app.callscribe.data.SecretStore.Key.RUNPOD_API_KEY, null)
                    Log.i(TAG, "transcriber settings reset")
                }
                "app.callscribe.DEBUG_PROCESS" -> {
                    val id = inputData.getLong("id", -1)
                    app.callscribe.detect.WorkScheduler.enqueueProcess(
                        applicationContext, id, setOf(app.callscribe.pipeline.ProcessWorker.STEP_TRANSCRIBE, app.callscribe.pipeline.ProcessWorker.STEP_ANALYZE), force = true,
                    )
                    Log.i(TAG, "enqueued process $id")
                }
                "app.callscribe.DEBUG_SHOW" -> {
                    val r = graph.db.recordings().get(inputData.getLong("id", -1))
                    Log.i(TAG, "row ${r?.id}: status=${r?.status} error=${r?.error} segs=${r?.transcriptJson?.length} text=${r?.transcriptText?.take(40)} cost=${r?.transcriptionCostUsd} gpuMs=${r?.transcriptionGpuMs} job=${r?.providerJobId}")
                }
                "app.callscribe.DEBUG_ONBOARDING" -> {
                    graph.settings.update { it.copy(onboardingDone = false, setupBannerDismissed = false) }
                    Log.i(TAG, "onboarding reset")
                }
                "app.callscribe.DEBUG_ONDEVICE" -> onDeviceBenchmark(graph, inputData.getLong("id", -1), inputData.getInt("threads", 2))
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

    /**
     * Downloads the on-device model if needed, then transcribes one recording (id, or the one closest to 60 s) on the
     * phone and logs time, CPU time and battery current. Does not touch settings or the database.
     *   adb shell am broadcast -n app.callscribe/.debug.DebugReceiver -a app.callscribe.DEBUG_ONDEVICE [--el id 12] [--ei threads 2]
     */
    private suspend fun onDeviceBenchmark(graph: app.callscribe.AppGraph, id: Long, threads: Int) = kotlinx.coroutines.coroutineScope {
        val m = app.callscribe.providers.ondevice.OnDeviceModels.ZIPFORMER_HE
        if (!graph.models.isInstalled(m)) {
            val t0 = System.currentTimeMillis()
            graph.models.startDownload(m)
            val end = graph.models.state(m).first {
                it is app.callscribe.providers.ondevice.ModelState.Installed || it is app.callscribe.providers.ondevice.ModelState.Failed
            }
            Log.i(TAG, "model download: $end in ${System.currentTimeMillis() - t0} ms")
            if (end !is app.callscribe.providers.ondevice.ModelState.Installed) return@coroutineScope
        }
        val rows = graph.db.recordings().all().filter { !it.fileMissing }
        val r = rows.firstOrNull { it.id == id } ?: rows.minByOrNull { kotlin.math.abs(it.durationMs - 60_000) } ?: return@coroutineScope
        val bm = applicationContext.getSystemService(android.os.BatteryManager::class.java)
        fun currentUa() = bm.getLongProperty(android.os.BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        fun chargeUah() = bm.getLongProperty(android.os.BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val idle = (1..5).map { kotlinx.coroutines.delay(1000); currentUa() }.average()
        val samples = mutableListOf<Long>()
        val sampler = launch { while (true) { samples += currentUa(); kotlinx.coroutines.delay(500) } }
        val pct0 = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val q0 = chargeUah()
        val cpu0 = android.os.Process.getElapsedCpuTime()
        val t0 = System.currentTimeMillis()
        val tr = app.callscribe.providers.ondevice.OnDeviceTranscriber(graph.models, threads)
        val st = tr.transcribeLocal(Uri.parse(r.uri)) { }
        val wall = System.currentTimeMillis() - t0
        val cpu = android.os.Process.getElapsedCpuTime() - cpu0
        sampler.cancel()
        val busy = samples.average()
        Log.i(TAG, "ondevice id=${r.id} dur=${r.durationMs / 1000}s threads=$threads wall=${wall}ms cpu=${cpu}ms " +
            "rtf=${"%.3f".format(wall / r.durationMs.toDouble())} battery%=$pct0->${bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)} " +
            "chargeCounter dUAh=${chargeUah() - q0} currentNow idle=${idle.toLong()}uA busy=${busy.toLong()}uA plugged=${bm.isCharging}")
        when (st) {
            is app.callscribe.providers.TranscriptionState.Done ->
                Log.i(TAG, "ondevice segs=${st.transcript.segments.size} raw=${st.transcript.rawJson} text=${st.transcript.text.take(300)}")
            else -> Log.i(TAG, "ondevice result $st")
        }
    }

    companion object { private const val TAG = "CallScribeDebug" }
}
