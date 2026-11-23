package app.callscribe.pipeline

import android.content.Context
import android.net.Uri
import android.util.Log
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
        const val STEP_EXPORT = "export"
        const val STEP_TRANSCRIBE = "transcribe"
        const val STEP_ANALYZE = "analyze"
        private const val TAG = "ProcessWorker"
        private const val MAX_ATTEMPTS = 6
        private val segJson = Json { ignoreUnknownKeys = true }
    }

    private val graph get() = (applicationContext as CallScribeApp).graph
    private val dao get() = graph.db.recordings()

    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_ID, -1)
        val steps = inputData.getString(KEY_STEPS).orEmpty().split(',').filter { it.isNotBlank() }.toSet()
        val force = inputData.getBoolean(KEY_FORCE, false)
        var row = dao.get(id) ?: return Result.failure()
        if (row.fileMissing) {
            dao.setStatus(id, Status.FAILED, "Audio file is missing"); return Result.failure()
        }
        val settings = graph.settings.get()

        try {
            if (STEP_EXPORT in steps) export(row, settings.webhookUrl)

            if (STEP_TRANSCRIBE in steps && (force || row.transcriptText == null || row.providerJobId != null)) {
                when (val r = transcribe(row, force)) {
                    is Outcome.Retry -> return if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else fail(id, r.reason)
                    is Outcome.Fail -> return fail(id, r.reason)
                    Outcome.Ok -> {}
                }
                row = dao.get(id)!!
            }

            if (STEP_ANALYZE in steps && row.transcriptText != null && (force || row.verdict == null || STEP_TRANSCRIBE in steps)) {
                analyze(row)
                row = dao.get(id)!!
            }
            graph.notifier.notifyResult(row)
            return Result.success()
        } catch (e: ProviderError) {
            Log.w(TAG, "provider error", e)
            return if (e.retryable && runAttemptCount < MAX_ATTEMPTS) {
                dao.setStatus(id, row.status, e.message); Result.retry()
            } else fail(id, e.message ?: "error")
        } catch (e: Exception) {
            Log.e(TAG, "processing failed", e)
            return if (runAttemptCount < 2) Result.retry() else fail(id, e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun fail(id: Long, reason: String): Result {
        dao.setStatus(id, Status.FAILED, reason)
        dao.get(id)?.let { graph.notifier.notifyResult(it) }
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

        var row = row0
        // Resume a job submitted by an earlier attempt instead of paying twice.
        var jobId = row.providerJobId.takeIf { row.transcriberId == tr.id && !(force && runAttemptCount == 0) }
        if (jobId == null) {
            dao.setStatus(row.id, Status.TRANSCRIBING)
            val audio = loadAudio(row, tr.maxUploadBytes)
            jobId = tr.submit(audio)
            row = dao.get(row.id)!!.copy(providerJobId = jobId, transcriberId = tr.id, status = Status.TRANSCRIBING, error = null)
            dao.update(row)
        }

        // Poll up to ~4 minutes inside this attempt; cold starts can take a while.
        val deadline = System.currentTimeMillis() + 4 * 60_000
        while (System.currentTimeMillis() < deadline) {
            when (val st = tr.poll(jobId)) {
                is TranscriptionState.Pending -> {
                    dao.update(dao.get(row.id)!!.copy(error = "Provider status: ${st.status}"))
                    delay(4_000)
                }
                is TranscriptionState.Failed -> {
                    dao.update(dao.get(row.id)!!.copy(providerJobId = null))
                    return if (st.retryable) Outcome.Retry(st.message) else Outcome.Fail(st.message)
                }
                is TranscriptionState.Done -> {
                    val segs = segJson.encodeToString(ListSerializer(Segment.serializer()), st.transcript.segments)
                    val cur = dao.get(row.id)!!
                    dao.update(
                        cur.copy(
                            transcriptText = st.transcript.text,
                            transcriptJson = segs,
                            transcriberId = tr.id,
                            providerJobId = null,
                            // Costs accumulate across re-transcriptions: it's what was actually spent on this call.
                            transcriptionCostUsd = (cur.transcriptionCostUsd ?: 0.0) + (st.cost?.usd ?: 0.0),
                            transcriptionGpuMs = (cur.transcriptionGpuMs ?: 0) + (st.cost?.gpuMs ?: 0),
                            status = Status.TRANSCRIBED,
                            error = null,
                            updatedAt = System.currentTimeMillis(),
                        )
                    )
                    return Outcome.Ok
                }
            }
        }
        return Outcome.Retry("Still waiting for the transcription server")
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

    private suspend fun analyze(row: RecordingEntity) {
        val settings = graph.settings.get()
        val an = graph.providers.analyzer(settings) ?: return
        if (!an.isConfigured()) return
        dao.setStatus(row.id, Status.ANALYZING)
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
        OkHttpClient().newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw ProviderError("Webhook HTTP ${resp.code}", resp.code >= 500)
        }
        dao.update(dao.get(row.id)!!.copy(exportedAt = System.currentTimeMillis()))
    }
}
