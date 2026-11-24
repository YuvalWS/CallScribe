package app.callscribe.providers.ivrit

import app.callscribe.providers.AudioInput
import app.callscribe.providers.Cost
import app.callscribe.providers.ProviderError
import app.callscribe.providers.Segment
import app.callscribe.providers.Transcriber
import app.callscribe.providers.Transcript
import app.callscribe.providers.TranscriptionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * ivrit.ai Hebrew Whisper models on the user's own RunPod serverless endpoint
 * (template: github.com/ivrit-ai/runpod-serverless). Uses /run + /status (non-streaming).
 */
class IvritRunPodTranscriber(
    private val endpointId: String,
    private val apiKey: String?,
    private val model: String,
    private val diarize: Boolean,
    private val pricePerSec: Double,
    private val baseUrl: String = "https://api.runpod.ai/v2",
    private val http: OkHttpClient = defaultClient,
) : Transcriber {
    override val id = "ivrit-runpod"
    override val displayName = "ivrit.ai on RunPod"
    /** RunPod rejects payloads over 10 MiB; base64 adds 4/3, leave room for the JSON envelope. */
    override val maxUploadBytes: Long = 7_300_000

    override fun isConfigured() = endpointId.isNotBlank() && !apiKey.isNullOrBlank()

    /** GET /health — free; reports worker and job counts without running anything. */
    override suspend fun test(): String = withContext(Dispatchers.IO) {
        if (!isConfigured()) throw ProviderError("Endpoint ID and API key are required", false)
        val req = Request.Builder()
            .url("$baseUrl/$endpointId/health")
            .header("Authorization", "Bearer $apiKey")
            .get().build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body.string()
            if (!resp.isSuccessful) throw httpError(resp.code, text)
            val o = Json.parseToJsonElement(text).jsonObject
            val w = o["workers"] as? JsonObject
            fun n(k: String) = w?.get(k)?.jsonPrimitive?.contentOrNull ?: "0"
            "Connected. Workers: ${n("ready")} ready, ${n("running")} running, ${n("initializing")} starting"
        }
    }

    override suspend fun submit(audio: AudioInput): String = withContext(Dispatchers.IO) {
        require(isConfigured()) { "RunPod endpoint or API key missing" }
        val req = Request.Builder()
            .url("$baseUrl/$endpointId/run")
            .header("Authorization", "Bearer $apiKey")
            .post(RunBody(model, diarize, audio.bytes))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body.string()
            if (!resp.isSuccessful) throw httpError(resp.code, text)
            val obj = Json.parseToJsonElement(text).jsonObject
            obj["id"]?.jsonPrimitive?.contentOrNull ?: throw ProviderError("RunPod: no job id in response: ${text.take(200)}", false)
        }
    }

    override suspend fun poll(handle: String): TranscriptionState = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$baseUrl/$endpointId/status/$handle")
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body.string()
            if (resp.code == 404) return@withContext TranscriptionState.Failed("RunPod job expired or not found", retryable = false)
            if (!resp.isSuccessful) throw httpError(resp.code, text)
            parseStatus(text, pricePerSec)
        }
    }

    /** POST /cancel: stops the job on RunPod so its GPU time stops being billed. */
    override suspend fun cancel(handle: String) = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$baseUrl/$endpointId/cancel/$handle")
            .header("Authorization", "Bearer $apiKey")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        runCatching { http.newCall(req).execute().use { app.callscribe.Logx.i("RunPod", "cancel job → HTTP ${it.code}") } }
            .onFailure { app.callscribe.Logx.w("RunPod", "cancel job failed", it) }
        Unit
    }

    private fun httpError(code: Int, body: String) = ProviderError(
        "RunPod HTTP $code: ${body.take(300)}",
        retryable = code == 429 || code >= 500,
    )

    /**
     * The /run request, streamed: the audio is base64-encoded chunk by chunk straight into the socket. Building it as
     * one JSON string held the recording in memory four times over (bytes, base64, JSON, UTF-8), which ran the app
     * out of memory with three uploads in parallel.
     */
    internal class RunBody(model: String, diarize: Boolean, private val audio: ByteArray) : RequestBody() {
        private val head: ByteArray
        private val tail: ByteArray
        init {
            val (h, t) = envelope(model, diarize)
            head = h.toByteArray(); tail = t.toByteArray()
        }
        override fun contentType() = JSON
        override fun contentLength() = head.size + 4L * ((audio.size + 2) / 3) + tail.size
        override fun writeTo(sink: BufferedSink) {
            sink.write(head)
            val enc = Base64.getEncoder()
            var i = 0
            while (i < audio.size) {
                val n = minOf(CHUNK, audio.size - i)
                sink.write(enc.encode(audio.copyOfRange(i, i + n)))
                i += n
            }
            sink.write(tail)
        }
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        /** Multiple of 3, so chunks base64-encode without padding in the middle. */
        private const val CHUNK = 3 * 16_384
        private const val BLOB = "__CALLSCRIBE_AUDIO__"
        const val EXECUTION_TIMEOUT_MS = 15 * 60_000
        const val TTL_MS = 2 * 60 * 60_000

        /** The JSON before and after the base64 audio (each half includes the string's quote). Pure — unit-tested. */
        fun envelope(model: String, diarize: Boolean): Pair<String, String> {
            val json = buildJsonObject {
                // Limits on RunPod's side, so a stuck job can never bill for long: at most 15 min of GPU time, and the
                // job is dropped if it hasn't finished within 2 h (we then report it failed instead of paying again).
                putJsonObject("policy") {
                    put("executionTimeout", EXECUTION_TIMEOUT_MS)
                    put("ttl", TTL_MS)
                }
                putJsonObject("input") {
                    put("type", "blob")
                    put("model", model)
                    put("engine", if (diarize) "stable-whisper" else "faster-whisper")
                    put("streaming", false)
                    putJsonObject("transcribe_args") {
                        put("blob", BLOB)
                        put("language", "he")
                        put("diarize", diarize)
                        putJsonObject("output_options") {
                            put("word_timestamps", false)
                            put("extra_data", false)
                        }
                    }
                }
            }.toString()
            val at = json.indexOf(BLOB)
            return json.substring(0, at) to json.substring(at + BLOB.length)
        }
        private val defaultClient = app.callscribe.providers.HttpLog.builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        /** Parses a RunPod /status response. Pure — unit-tested. */
        fun parseStatus(text: String, pricePerSec: Double): TranscriptionState {
            val obj = Json.parseToJsonElement(text).jsonObject
            val status = obj["status"]?.jsonPrimitive?.contentOrNull ?: "UNKNOWN"
            return when (status) {
                "COMPLETED" -> {
                    val output = obj["output"] ?: return TranscriptionState.Failed("RunPod: completed without output", false)
                    val error = findError(output)
                    if (error != null) return TranscriptionState.Failed("Transcription error: $error", false)
                    val segments = collectSegments(output).sortedBy { it.start }
                    val textOut = segments.joinToString(" ") { it.text.trim() }.trim()
                    val execMs = obj["executionTime"]?.jsonPrimitive?.longOrNull
                    val cost = execMs?.let { Cost(it / 1000.0 * pricePerSec, "≈ ${"%.1f".format(it / 1000.0)} s GPU", it) }
                    TranscriptionState.Done(Transcript(textOut, segments, "he", output.toString()), cost)
                }
                "FAILED" -> TranscriptionState.Failed("RunPod job failed: ${obj["error"]?.toString()?.take(300) ?: "unknown"}", false)
                "CANCELLED", "TIMED_OUT" -> TranscriptionState.Failed("RunPod job $status", true)
                else -> TranscriptionState.Pending(status) // IN_QUEUE, IN_PROGRESS
            }
        }

        /** Walks the output tree, collecting `{type: "segments", data: [...]}` events (and bare `segments` arrays). */
        fun collectSegments(e: JsonElement): List<Segment> {
            val out = mutableListOf<Segment>()
            fun segFrom(o: JsonObject): Segment? {
                val text = o["text"]?.jsonPrimitive?.contentOrNull ?: return null
                val start = o["start"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                val end = o["end"]?.jsonPrimitive?.doubleOrNull ?: start
                val speaker = (o["speakers"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.contentOrNull
                    ?: o["speaker"]?.jsonPrimitive?.contentOrNull
                return Segment(start, end, text, speaker)
            }
            fun walk(x: JsonElement) {
                when (x) {
                    is JsonObject -> {
                        val type = (x["type"] as? JsonPrimitive)?.contentOrNull
                        val data = x["data"]
                        if (type == "segments" && data is JsonArray) {
                            data.forEach { d -> (d as? JsonObject)?.let(::segFrom)?.let(out::add) }
                        } else if (x["segments"] is JsonArray && type == null) {
                            (x["segments"] as JsonArray).forEach { d -> (d as? JsonObject)?.let(::segFrom)?.let(out::add) }
                        } else {
                            x.values.forEach(::walk)
                        }
                    }
                    is JsonArray -> x.forEach(::walk)
                    else -> {}
                }
            }
            walk(e)
            return out
        }

        fun findError(e: JsonElement): String? {
            when (e) {
                is JsonObject -> {
                    val type = (e["type"] as? JsonPrimitive)?.contentOrNull
                    if (type == "error") return e["data"]?.toString()
                    (e["error"] as? JsonPrimitive)?.contentOrNull?.let { return it }
                    for (v in e.values) findError(v)?.let { return it }
                }
                is JsonArray -> for (v in e) findError(v)?.let { return it }
                else -> {}
            }
            return null
        }
    }
}
