package app.callscribe.providers.gemini

import app.callscribe.providers.AnalysisPrompt
import app.callscribe.providers.AnalysisResult
import app.callscribe.providers.CallAnalyzer
import app.callscribe.providers.CallMeta
import app.callscribe.providers.Cost
import app.callscribe.providers.ProviderError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Google Gemini (AI Studio API key). Calls are spaced to respect a requests-per-minute limit
 * (free tier is 15 RPM); a 429 is reported as retryable so WorkManager backs off.
 */
class GeminiAnalyzer(
    private val apiKey: String?,
    private val model: String,
    private val rpm: Int,
    private val priceInPerM: Double,
    private val priceOutPerM: Double,
    private val http: OkHttpClient = client,
) : CallAnalyzer {
    override val id = "gemini"
    override val displayName = "Google Gemini"
    override fun isConfigured() = !apiKey.isNullOrBlank() && model.isNotBlank()

    private val base = "https://generativelanguage.googleapis.com/v1beta/models"

    override suspend fun test(): String = withContext(Dispatchers.IO) {
        if (!isConfigured()) throw ProviderError("API key is required", false)
        val req = Request.Builder().url("$base/$model").header("x-goog-api-key", apiKey!!).get().build()
        http.newCall(req).execute().use { r ->
            val body = r.body.string()
            if (!r.isSuccessful) throw error(r.code, body)
            val name = Json.parseToJsonElement(body).jsonObject["displayName"]?.jsonPrimitive?.contentOrNull ?: model
            "Connected. Model: $name"
        }
    }

    override suspend fun analyze(transcript: String, meta: CallMeta): AnalysisResult = withContext(Dispatchers.IO) {
        if (!isConfigured()) throw ProviderError("Gemini API key missing", false)
        val body = buildJsonObject {
            putJsonObject("systemInstruction") {
                put("parts", buildJsonArray { add(buildJsonObject { put("text", AnalysisPrompt.SYSTEM) }) })
            }
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray { add(buildJsonObject { put("text", AnalysisPrompt.user(transcript, meta)) }) })
                })
            })
            putJsonObject("generationConfig") {
                put("responseMimeType", "application/json")
                put("responseJsonSchema", AnalysisPrompt.schemaJson(strict = false))
            }
        }.toString()
        val req = Request.Builder()
            .url("$base/$model:generateContent")
            .header("x-goog-api-key", apiKey!!)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val text = throttle(rpm) { http.newCall(req).execute() }.use { r ->
            val t = r.body.string()
            if (!r.isSuccessful) throw error(r.code, t)
            t
        }
        val root = Json.parseToJsonElement(text).jsonObject
        val usage = root["usageMetadata"] as? JsonObject
        val inTok = usage?.get("promptTokenCount")?.jsonPrimitive?.longOrNull ?: 0
        val outTok = (usage?.get("candidatesTokenCount")?.jsonPrimitive?.longOrNull ?: 0) +
            (usage?.get("thoughtsTokenCount")?.jsonPrimitive?.longOrNull ?: 0)
        val cand = (root["candidates"] as? JsonArray)?.firstOrNull()?.jsonObject
            ?: throw ProviderError("Gemini returned no answer: ${text.take(200)}", false)
        val finish = cand["finishReason"]?.jsonPrimitive?.contentOrNull
        if (finish == "SAFETY" || finish == "PROHIBITED_CONTENT") throw ProviderError("Gemini blocked the request ($finish)", false)
        val out = (cand["content"]?.jsonObject?.get("parts") as? JsonArray)
            ?.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }.orEmpty()
        val usd = inTok / 1e6 * priceInPerM + outTok / 1e6 * priceOutPerM
        AnalysisResult(AnalysisPrompt.parse(out), out, Cost(usd, "$inTok in / $outTok out tokens"), inTok, outTok)
    }

    private fun error(code: Int, body: String) = when (code) {
        400, 403 -> ProviderError("Gemini HTTP $code: ${body.take(300)}", false)
        404 -> ProviderError("Gemini: model '$model' not available: ${body.take(200)}", false)
        429 -> ProviderError("Gemini rate limit reached (will retry)", true)
        else -> ProviderError("Gemini HTTP $code: ${body.take(200)}", code >= 500)
    }

    companion object {
        private val client = OkHttpClient.Builder().readTimeout(120, TimeUnit.SECONDS).build()
        private val lock = Mutex()
        private var lastCallMs = 0L

        /** Serializes calls app-wide and spaces them 60/rpm seconds apart. */
        private suspend fun <T> throttle(rpm: Int, block: () -> T): T = lock.withLock {
            val gap = 60_000L / rpm.coerceAtLeast(1) + 200
            val wait = lastCallMs + gap - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            try { block() } finally { lastCallMs = System.currentTimeMillis() }
        }
    }
}
