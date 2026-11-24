package app.callscribe.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit

/** One model a provider offers, for the "Choose model" list. */
data class ModelOption(
    val id: String,
    val name: String,
    /** Short price note (e.g. "≈ $0.0005 / call"), or null when the provider doesn't publish prices in its list. */
    val price: String? = null,
    /** Estimated cost of one call summary, used to sort cheapest first; null when unknown. */
    val usdPerCall: Double? = null,
)

/**
 * Fetches the models a provider offers, on request ("Choose model" button). Free calls — nothing is generated.
 * Parsers are pure and unit-tested.
 */
object ModelCatalog {
    /** A typical call summary: transcript + instructions in, the JSON verdict out (measured on real calls). */
    const val TYPICAL_IN_TOKENS = 2_500
    const val TYPICAL_OUT_TOKENS = 300

    private val client: OkHttpClient by lazy {
        HttpLog.builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    }

    suspend fun gemini(apiKey: String): List<ModelOption> = get(
        Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000")
            .header("x-goog-api-key", apiKey).build(),
    ).let(::parseGemini)

    /** OpenRouter's list is public; the key isn't needed for it. */
    suspend fun openRouter(): List<ModelOption> = get(
        Request.Builder().url("https://openrouter.ai/api/v1/models").build(),
    ).let(::parseOpenRouter)

    private suspend fun get(req: Request): String = withContext(Dispatchers.IO) {
        try {
            client.newCall(req).execute().use { r ->
                val body = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw ProviderError("HTTP ${r.code}: ${body.take(200)}", retryable = r.code >= 500)
                body
            }
        } catch (e: java.io.IOException) {
            throw ProviderError("Network error: ${e.message}", retryable = true)
        }
    }

    /** Text models that can write a summary: generateContent, no embedding / speech / image / live variants. */
    fun parseGemini(text: String): List<ModelOption> {
        val arr = (Json.parseToJsonElement(text) as? JsonObject)?.get("models") as? JsonArray ?: return emptyList()
        val skip = listOf("embedding", "tts", "image", "live", "native-audio", "aqa", "robotics", "computer-use", "banana", "transcribe", "customtools")
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.str("name")?.removePrefix("models/") ?: return@mapNotNull null
            val methods = (o["supportedGenerationMethods"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
            if ("generateContent" !in methods || !id.startsWith("gemini") || skip.any { it in id }) return@mapNotNull null
            ModelOption(id, o.str("displayName") ?: id)
        }.sortedBy { it.id }
    }

    /** Models that support structured outputs (the summary is strict JSON), cheapest first, with a per-call price. */
    fun parseOpenRouter(text: String): List<ModelOption> {
        val arr = (Json.parseToJsonElement(text) as? JsonObject)?.get("data") as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            val params = (o["supported_parameters"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
            if ("structured_outputs" !in params) return@mapNotNull null
            val pricing = o["pricing"] as? JsonObject
            val pin = pricing?.str("prompt")?.toDoubleOrNull()
            val pout = pricing?.str("completion")?.toDoubleOrNull()
            // OpenRouter uses -1 for "variable" (router) pricing.
            val usd = if (pin == null || pout == null || pin < 0 || pout < 0) null
            else pin * TYPICAL_IN_TOKENS + pout * TYPICAL_OUT_TOKENS
            ModelOption(id, o.str("name") ?: id, usd?.let(::perCall), usd)
        }.sortedWith(compareBy<ModelOption> { it.usdPerCall ?: Double.MAX_VALUE }.thenBy { it.id })
    }

    fun perCall(usd: Double): String = when {
        usd == 0.0 -> "free"
        usd < 0.0001 -> "< $0.0001 / call"
        else -> "≈ $" + String.format(Locale.US, if (usd < 0.01) "%.4f" else "%.3f", usd) + " / call"
    }

    private fun JsonObject.str(k: String) = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
}
