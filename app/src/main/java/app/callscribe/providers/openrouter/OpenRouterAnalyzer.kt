package app.callscribe.providers.openrouter

import app.callscribe.providers.AnalysisPrompt
import app.callscribe.providers.AnalysisResult
import app.callscribe.providers.CallAnalyzer
import app.callscribe.providers.CallMeta
import app.callscribe.providers.Cost
import app.callscribe.providers.ProviderError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
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
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Any model on OpenRouter (pay per request). The response reports the exact cost charged. */
class OpenRouterAnalyzer(
    private val apiKey: String?,
    private val model: String,
    private val http: OkHttpClient = client,
) : CallAnalyzer {
    override val id = "openrouter"
    override val displayName = "OpenRouter"
    override fun isConfigured() = !apiKey.isNullOrBlank() && model.isNotBlank()

    private val base = "https://openrouter.ai/api/v1"

    /** GET /key — free; shows credit used and remaining. */
    override suspend fun test(): String = withContext(Dispatchers.IO) {
        if (!isConfigured()) throw ProviderError("API key is required", false)
        val req = Request.Builder().url("$base/key").header("Authorization", "Bearer $apiKey").get().build()
        http.newCall(req).execute().use { r ->
            val body = r.body.string()
            if (!r.isSuccessful) throw ProviderError("OpenRouter HTTP ${r.code}: ${body.take(200)}", false)
            val d = Json.parseToJsonElement(body).jsonObject["data"]?.jsonObject
            val used = d?.get("usage")?.jsonPrimitive?.doubleOrNull ?: 0.0
            val left = d?.get("limit_remaining")?.jsonPrimitive?.contentOrNull ?: "no limit"
            "Connected. Used $%.4f, remaining: %s".format(used, left)
        }
    }

    override suspend fun analyze(transcript: String, meta: CallMeta): AnalysisResult = withContext(Dispatchers.IO) {
        if (!isConfigured()) throw ProviderError("OpenRouter API key missing", false)
        val body = buildJsonObject {
            put("model", model)
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", AnalysisPrompt.SYSTEM) })
                add(buildJsonObject { put("role", "user"); put("content", AnalysisPrompt.user(transcript, meta)) })
            })
            putJsonObject("response_format") {
                put("type", "json_schema")
                putJsonObject("json_schema") {
                    put("name", "call_analysis")
                    put("strict", true)
                    put("schema", AnalysisPrompt.schemaJson())
                }
            }
            put("max_tokens", 2000)
            putJsonObject("usage") { put("include", true) }
        }.toString()
        val req = Request.Builder()
            .url("$base/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("X-Title", "CallScribe")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val text = http.newCall(req).execute().use { r ->
            val t = r.body.string()
            if (!r.isSuccessful) throw when (r.code) {
                401 -> ProviderError("OpenRouter: invalid API key", false)
                402 -> ProviderError("OpenRouter: out of credit", false)
                429 -> ProviderError("OpenRouter rate limit (will retry)", true)
                else -> ProviderError("OpenRouter HTTP ${r.code}: ${t.take(200)}", r.code >= 500)
            }
            t
        }
        val root = Json.parseToJsonElement(text).jsonObject
        root["error"]?.let { throw ProviderError("OpenRouter error: ${it.toString().take(200)}", false) }
        val usage = root["usage"] as? JsonObject
        val inTok = usage?.get("prompt_tokens")?.jsonPrimitive?.longOrNull ?: 0
        val outTok = usage?.get("completion_tokens")?.jsonPrimitive?.longOrNull ?: 0
        val usd = usage?.get("cost")?.jsonPrimitive?.doubleOrNull ?: 0.0
        val msg = (root["choices"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
        val out = msg?.get("content")?.jsonPrimitive?.contentOrNull
            ?: throw ProviderError("OpenRouter returned no answer: ${text.take(200)}", false)
        AnalysisResult(AnalysisPrompt.parse(out), out, Cost(usd, "$inTok in / $outTok out tokens"), inTok, outTok)
    }

    companion object {
        private val client = OkHttpClient.Builder().readTimeout(120, TimeUnit.SECONDS).build()
    }
}
