package app.callscribe.providers.ivrit

import app.callscribe.providers.HttpLog
import app.callscribe.providers.ProviderError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Reads the RunPod account balance (GraphQL `myself.clientBalance`, readable with a restricted key). RunPod's job
 * status has no price, so the real cost of a bulk run is the balance drop around it. Charges appear on the balance
 * ~5 minutes after the work ([SETTLE_MINUTES]).
 */
object RunPodBalance {
    const val SETTLE_MINUTES = 7L
    private const val URL = "https://api.runpod.io/graphql"
    private val client by lazy { HttpLog.builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build() }

    suspend fun read(apiKey: String): Double = withContext(Dispatchers.IO) {
        val body = buildJsonObject { put("query", "{ myself { clientBalance } }") }.toString()
        val req = Request.Builder().url(URL).header("Authorization", "Bearer $apiKey")
            .post(body.toRequestBody("application/json".toMediaType())).build()
        try {
            client.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw ProviderError("RunPod balance: HTTP ${r.code}", r.code >= 500)
                parse(text) ?: throw ProviderError("RunPod balance: unexpected answer", false)
            }
        } catch (e: java.io.IOException) {
            throw ProviderError("RunPod balance: ${e.message}", true)
        }
    }

    /** Pure — unit-tested. */
    fun parse(text: String): Double? = runCatching {
        val root = Json.parseToJsonElement(text) as JsonObject
        ((root["data"] as JsonObject)["myself"] as JsonObject)["clientBalance"]!!.jsonPrimitive.doubleOrNull
    }.getOrNull()

    /**
     * New price per second after a measured run: scales the current price by charged / estimated, within the range
     * of RunPod's serverless GPUs. Null when the run is too small to measure (balance rounding, other activity).
     * Pure — unit-tested.
     */
    fun calibratedPrice(current: Double, estimatedUsd: Double, chargedUsd: Double): Double? {
        if (estimatedUsd < 0.01 || chargedUsd <= 0) return null
        return (current * chargedUsd / estimatedUsd).coerceIn(0.00010, 0.0020)
    }
}
