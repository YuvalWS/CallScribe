package app.callscribe.pipeline

import app.callscribe.Logx
import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.callscribe.CallScribeApp
import app.callscribe.data.RecordingEntity
import app.callscribe.data.Relationship
import app.callscribe.data.SecretStore
import app.callscribe.data.Status
import app.callscribe.providers.AudioInput
import app.callscribe.providers.CallMeta
import app.callscribe.providers.ProviderError
import app.callscribe.providers.Segment
import app.callscribe.providers.TranscriptionState
import app.callscribe.providers.ondevice.LocalTranscriber
import app.callscribe.providers.ondevice.SegmentAssembler
import androidx.work.WorkInfo
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Processes one recording: optional webhook export → transcription → analysis.
 * Used for both automatic (rules) and manual (button) processing.
 */
class ProcessWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    companion object {
        const val KEY_ID = "id"
        const val KEY_STEPS = "steps"
        const val KEY_FORCE = "force"
        /** Part of a bulk run: never fails the lane, no per-call notification, honours cancel and the spending cap. */
        const val KEY_BULK = "bulk"
        const val STEP_EXPORT = "export"
        const val STEP_TRANSCRIBE = "transcribe"
        const val STEP_ANALYZE = "analyze"
        private const val TAG = "ProcessWorker"
        /** Retries for "still waiting" / temporary provider errors (each attempt polls for up to 4 min). */
        private const val MAX_ATTEMPTS = 6
        /** Paid uploads per request at most, however many retries happen: retries normally resume the same job. */
        const val MAX_SUBMITS = 2
        private val segJson = Json { ignoreUnknownKeys = true }
    }

    private val graph get() = (applicationContext as CallScribeApp).graph
    private val dao get() = graph.db.recordings()

    override suspend fun doWork(): Result {
        val res = work()
        if (res !is Result.Retry) submitCounts().edit().remove(this.id.toString()).apply()
        return res
    }

    private suspend fun work(): Result {
        val id = inputData.getLong(KEY_ID, -1)
        val steps = inputData.getString(KEY_STEPS).orEmpty().split(',').filter { it.isNotBlank() }.toSet()
        val force = inputData.getBoolean(KEY_FORCE, false)
        val bulk = inputData.getBoolean(KEY_BULK, false)
        Logx.i(TAG, "call $id: start steps=$steps bulk=$bulk attempt=$runAttemptCount")
        if (!bulk) return run(id, steps, force, bulk = false)
        val row = dao.get(id) ?: return Result.success()
        if (!graph.bulk.mayProceed(row)) { Logx.i(TAG, "call $id: skipped (bulk run stopped)"); return Result.success() }
        val res = try { run(id, steps, force, bulk = true) } finally { graph.bulk.updateNotification() }
        // A failed call is recorded on the call; the next one in the lane still runs.
        return if (res is Result.Failure) Result.success() else res
    }

    private suspend fun run(id: Long, steps: Set<String>, force: Boolean, bulk: Boolean): Result {
        var row = dao.get(id) ?: return Result.failure()
        // This exact job was already stopped by Android's time limit and its partial transcript saved: don't loop.
        if (timeoutMarks().getString(id.toString(), null) == this.id.toString()) return Result.failure()
        if (row.fileMissing) {
            dao.setStatus(id, Status.FAILED, "Audio file is missing"); return Result.failure()
        }
        val settings = graph.settings.get()

        try {
            if (STEP_EXPORT in steps) export(row, settings.webhookUrl)

            if (STEP_TRANSCRIBE in steps && (force || row.transcriptText == null || row.providerJobId != null)) {
                when (val r = transcribe(row, force)) {
                    is Outcome.Retry -> {
                        Logx.i(TAG, "call $id: retry later (attempt $runAttemptCount): ${r.reason}")
                        return if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else fail(id, r.reason, bulk)
                    }
                    is Outcome.Fail -> return fail(id, r.reason, bulk)
                    Outcome.Ok -> {}
                }
                row = dao.get(id)!!
            }

            if (STEP_ANALYZE in steps && row.transcriptText != null && (force || row.verdict == null || STEP_TRANSCRIBE in steps)) {
                analyze(row)
                row = dao.get(id)!!
            }
            if (!bulk) graph.notifier.notifyResult(row)
            Logx.i(TAG, "call $id: done (${row.status})")
            return Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // stopped by WorkManager; it reschedules (or the time-limit handler already recorded why)
        } catch (e: ProviderError) {
            Logx.w(TAG, "provider error", e)
            return if (e.retryable && runAttemptCount < MAX_ATTEMPTS) {
                dao.setStatus(id, row.status, e.message); Result.retry()
            } else fail(id, e.message ?: "error", bulk)
        } catch (e: Exception) {
            Logx.e(TAG, "processing failed", e)
            return if (runAttemptCount < 2) Result.retry() else fail(id, e.message ?: e.javaClass.simpleName, bulk)
        }
    }

    private suspend fun fail(id: Long, reason: String, bulk: Boolean = false): Result {
        Logx.w(TAG, "call $id: failed: $reason")
        dao.setStatus(id, Status.FAILED, reason)
        if (!bulk) dao.get(id)?.let { graph.notifier.notifyResult(it) }
        return Result.failure()
    }

    private sealed interface Outcome {
        data object Ok : Outcome
        data class Retry(val reason: String) : Outcome
        data class Fail(val reason: String) : Outcome
    }

    private suspend fun transcribe(row0: RecordingEntity, force: Boolean): Outcome {
        val settings = graph.settings.get()
        val tr = graph.providers.transcriber(settings)
            ?: return Outcome.Fail("No transcription provider selected")
        if (!tr.isConfigured()) return Outcome.Fail("Transcription provider is not set up (Settings → Providers)")
        if (tr is LocalTranscriber) return transcribeOnDevice(row0, tr)

        var row = row0
        // Resume a job submitted by an earlier attempt instead of paying twice.
        var jobId = row.providerJobId.takeIf { row.transcriberId == tr.id && !(force && runAttemptCount == 0) }
        if (jobId == null) {
            val submits = submitCounts().getInt(this.id.toString(), 0)
            if (submits >= MAX_SUBMITS) {
                return Outcome.Fail("Gave up after $submits attempts on the transcription server, to avoid paying again. Tap Transcribe to try once more.")
            }
            submitCounts().edit().putInt(this.id.toString(), submits + 1).apply()
            dao.setStatus(row.id, Status.TRANSCRIBING)
            val audio = loadAudio(row, tr.maxUploadBytes)
            Logx.i(TAG, "call ${row.id}: uploading ${audio.bytes.size / 1024} KB (${row.durationMs / 1000} s audio)")
            jobId = tr.submit(audio)
            Logx.i(TAG, "call ${row.id}: submitted")
            row = dao.get(row.id)!!.copy(providerJobId = jobId, transcriberId = tr.id, status = Status.TRANSCRIBING, error = null)
            dao.update(row)
        }

        // Poll up to ~4 minutes inside this attempt; cold starts can take a while.
        val deadline = System.currentTimeMillis() + 4 * 60_000
        var lastStatus: String? = null
        while (System.currentTimeMillis() < deadline) {
            when (val st = tr.poll(jobId)) {
                is TranscriptionState.Pending -> {
                    if (st.status != lastStatus) Logx.i(TAG, "call ${row.id}: provider status ${st.status}")
                    lastStatus = st.status
                    dao.update(dao.get(row.id)!!.copy(error = "Provider status: ${st.status}"))
                    delay(4_000)
                }
                is TranscriptionState.Failed -> {
                    dao.update(dao.get(row.id)!!.copy(providerJobId = null))
                    return if (st.retryable) Outcome.Retry(st.message) else Outcome.Fail(st.message)
                }
                is TranscriptionState.Done -> {
                    Logx.i(TAG, "call ${row.id}: transcribed, cost ${st.cost?.usd}")
                    saveTranscript(row.id, tr.id, st)
                    return Outcome.Ok
                }
            }
        }
        return Outcome.Retry("Still waiting for the transcription server")
    }

    private suspend fun saveTranscript(id: Long, transcriberId: String, st: TranscriptionState.Done) {
        val segs = segJson.encodeToString(ListSerializer(Segment.serializer()), st.transcript.segments)
        val cur = dao.get(id)!!
        dao.update(
            cur.copy(
                transcriptText = st.transcript.text,
                transcriptJson = segs,
                transcriberId = transcriberId,
                providerJobId = null,
                // Costs accumulate across re-transcriptions: it's what was actually spent on this call.
                transcriptionCostUsd = (cur.transcriptionCostUsd ?: 0.0) + (st.cost?.usd ?: 0.0),
                transcriptionGpuMs = (cur.transcriptionGpuMs ?: 0) + (st.cost?.gpuMs ?: 0),
                status = Status.TRANSCRIBED,
                error = null,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    /**
     * Runs the model on the phone. Can take minutes for long calls, so the worker asks to become a foreground service
     * with a progress notification; if Android refuses (app in the background), it carries on as a normal worker.
     */
    private suspend fun transcribeOnDevice(row: RecordingEntity, tr: LocalTranscriber): Outcome {
        dao.setStatus(row.id, Status.TRANSCRIBING)
        val note = TranscribeProgress(applicationContext, row)
        try {
            setForeground(note.foregroundInfo(0))
        } catch (e: Exception) {
            Logx.i(TAG, "running on-device transcription without a foreground service: ${e.message}")
        }
        val partial = java.util.Collections.synchronizedList(mutableListOf<Segment>())
        val st = try {
            tr.transcribeLocal(
                Uri.parse(row.uri),
                onProgress = { pct ->
                    note.update(pct)
                    if (pct % 10 == 0) setProgressAsync(androidx.work.workDataOf("progress" to pct))
                },
                onSegment = { partial += it },
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (stopReason == WorkInfo.STOP_REASON_TIMEOUT || stopReason == WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT) {
                withContext(NonCancellable) { onTimeLimit(row, partial.toList()) }
            }
            throw e
        } finally {
            note.cancel()
        }
        return when (st) {
            is TranscriptionState.Done -> { saveTranscript(row.id, (tr as app.callscribe.providers.Transcriber).id, st); Outcome.Ok }
            is TranscriptionState.Failed -> if (st.retryable) Outcome.Retry(st.message) else Outcome.Fail(st.message)
            is TranscriptionState.Pending -> Outcome.Retry(st.status)
        }
    }

    private fun loadAudio(row: RecordingEntity, maxBytes: Long): AudioInput {
        val uri = Uri.parse(row.uri)
        if (row.sizeBytes in 1..maxBytes) {
            val bytes = applicationContext.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            return AudioInput(bytes, "audio/mp4", row.displayName)
        }
        val compressed = AudioCompressor(applicationContext).compress(uri)
        if (compressed.size > maxBytes) {
            throw ProviderError("Recording too long for this provider even after compression (${compressed.size / 1_000_000} MB)", false)
        }
        return AudioInput(compressed, "audio/mp4", row.displayName)
    }

    /** Paid submissions made by this work request (keyed by its WorkManager id), see [MAX_SUBMITS]. */
    private fun submitCounts() = applicationContext.getSharedPreferences("submits", Context.MODE_PRIVATE)

    private fun timeoutMarks() = applicationContext.getSharedPreferences("ondevice_timeouts", Context.MODE_PRIVATE)

    /**
     * Android stopped an on-device transcription at its background time limit (10 min without a foreground service).
     * Keep what was transcribed, say why, and mark this job so WorkManager's automatic re-run doesn't start over in a loop.
     */
    private suspend fun onTimeLimit(row: RecordingEntity, partial: List<Segment>) {
        timeoutMarks().edit().putString(row.id.toString(), id.toString()).apply()
        val doneMin = (partial.lastOrNull()?.end ?: 0.0) / 60
        val reason = "Android stopped the transcription after 10 minutes in the background" +
            (if (partial.isNotEmpty()) " — transcript covers only the first %.0f min".format(doneMin) else "") +
            ". Open CallScribe and tap Transcribe to finish it."
        val cur = dao.get(row.id) ?: return
        dao.update(
            cur.copy(
                transcriptText = if (partial.isNotEmpty()) SegmentAssembler.join(partial) else cur.transcriptText,
                transcriptJson = if (partial.isNotEmpty()) segJson.encodeToString(ListSerializer(Segment.serializer()), partial) else cur.transcriptJson,
                status = Status.FAILED,
                error = reason,
                updatedAt = System.currentTimeMillis(),
            )
        )
        dao.get(row.id)?.let { graph.notifier.notifyResult(it) }
    }

    private suspend fun analyze(row: RecordingEntity) {
        val settings = graph.settings.get()
        val an = graph.providers.analyzer(settings) ?: return
        if (!an.isConfigured()) return
        dao.setStatus(row.id, Status.ANALYZING)
        Logx.i(TAG, "call ${row.id}: analyzing with ${an.id}")
        val time = DateTimeFormatter.ofPattern("EEE yyyy-MM-dd HH:mm")
            .format(Instant.ofEpochSecond(row.callTimeSec).atZone(ZoneId.systemDefault()))
        val meta = CallMeta(row.counterparty, row.samsungLabel, row.labelKind.name, row.durationMs / 1000, time)
        val res = an.analyze(row.transcriptText!!, meta)
        val cur = dao.get(row.id)!!
        dao.update(
            cur.copy(
                analyzerId = an.id,
                analysisJson = res.rawJson,
                summary = res.analysis.summary_he,
                verdict = res.analysis.verdict,
                spamConfidence = res.analysis.spam_confidence,
                category = res.analysis.category,
                callerIntent = res.analysis.caller_intent,
                relationship = if (cur.relationshipManual) cur.relationship else Relationship.fromKey(res.analysis.relationship),
                analysisCostUsd = (cur.analysisCostUsd ?: 0.0) + (res.cost?.usd ?: 0.0),
                analysisInputTokens = (cur.analysisInputTokens ?: 0) + res.inputTokens,
                analysisOutputTokens = (cur.analysisOutputTokens ?: 0) + res.outputTokens,
                status = Status.ANALYZED,
                error = null,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    private suspend fun export(row: RecordingEntity, url: String) {
        if (url.isBlank()) return
        val bytes = applicationContext.contentResolver.openInputStream(Uri.parse(row.uri))!!.use { it.readBytes() }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", row.displayName, bytes.toRequestBody("audio/mp4".toMediaType()))
            .addFormDataPart("display_name", row.displayName)
            .addFormDataPart("counterparty", row.counterparty ?: "")
            .addFormDataPart("label", row.samsungLabel ?: row.labelKind.name)
            .addFormDataPart("call_time", row.callTimeSec.toString())
            .addFormDataPart("duration_ms", row.durationMs.toString())
            .build()
        val req = Request.Builder().url(url).post(body).apply {
            graph.secrets.get(SecretStore.Key.WEBHOOK_TOKEN)?.let { header("Authorization", "Bearer $it") }
        }.build()
        app.callscribe.providers.HttpLog.builder().build().newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw ProviderError("Webhook HTTP ${resp.code}", resp.code >= 500)
        }
        dao.update(dao.get(row.id)!!.copy(exportedAt = System.currentTimeMillis()))
    }
}
