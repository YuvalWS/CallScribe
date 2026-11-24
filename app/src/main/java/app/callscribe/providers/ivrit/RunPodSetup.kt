package app.callscribe.providers.ivrit

import app.callscribe.Logx
import app.callscribe.providers.HttpLog
import app.callscribe.providers.ProviderError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Sets up the user's RunPod endpoint from just their API key: reuses an existing ivrit.ai endpoint if the account has
 * one, otherwise creates one with the settings ivrit.ai's own endpoint generator uses (0 workers when idle, so it
 * costs nothing until a call is transcribed). Uses RunPod's management API (api.runpod.io/v2), with the user's key.
 */
class RunPodSetup(
    private val apiKey: String,
    private val baseUrl: String = "https://api.runpod.io/v2",
    private val http: OkHttpClient = client,
) {
    data class Endpoint(val id: String, val name: String, val image: String)
    data class Outcome(val endpoint: Endpoint, val created: Boolean)

    suspend fun findOrCreate(): Outcome = withContext(Dispatchers.IO) {
        val existing = pick(list())
        if (existing != null) {
            Logx.i(TAG, "reusing existing ivrit.ai endpoint")
            return@withContext Outcome(existing, created = false)
        }
        Outcome(create(), created = true).also { Logx.i(TAG, "created endpoint ${it.endpoint.name}") }
    }

    private fun list(): List<Endpoint> {
        val req = Request.Builder().url("$baseUrl/serverless").header("Authorization", "Bearer $apiKey").get().build()
        return http.newCall(req).execute().use { r ->
            val text = r.body.string()
            if (!r.isSuccessful) throw error(r.code, text)
            parseList(text)
        }
    }

    private fun create(): Endpoint {
        val req = Request.Builder().url("$baseUrl/serverless").header("Authorization", "Bearer $apiKey")
            .post(createBody().toRequestBody("application/json".toMediaType())).build()
        return http.newCall(req).execute().use { r ->
            val text = r.body.string()
            if (!r.isSuccessful) throw error(r.code, text)
            parseEndpoint(Json.parseToJsonElement(text).jsonObject)
                ?: throw ProviderError("RunPod created the endpoint but returned no ID: ${text.take(200)}", false)
        }
    }

    private fun error(code: Int, body: String) = when (code) {
        401, 403 -> ProviderError(
            "RunPod refused the key ($code). It needs permission to read and create serverless endpoints — " +
                "use a key with \"All\" or Read/Write access to serverless.", false,
        )
        else -> ProviderError("RunPod HTTP $code: ${body.take(300)}", code == 429 || code >= 500)
    }

    companion object {
        private const val TAG = "RunPodSetup"
        /** ivrit.ai's worker image (from github.com/ivrit-ai/runpod-serverless). Pinned: the tag ivrit.ai's generator deploys. */
        const val IMAGE_REPO = "yairlifshitz/whisper-runpod-serverless"
        const val IMAGE = "$IMAGE_REPO:ivrit-2026.05.31"
        const val NAME = "callscribe-ivrit"
        const val KEY_PAGE = "https://console.runpod.io/user/credentials"
        private val client = HttpLog.builder().readTimeout(60, TimeUnit.SECONDS).build()

        /** The endpoint to reuse: one running ivrit.ai's image, preferring one we created. Pure — unit-tested. */
        fun pick(endpoints: List<Endpoint>): Endpoint? {
            val ivrit = endpoints.filter { it.image.substringBefore(':') == IMAGE_REPO }
            return ivrit.firstOrNull { it.name == NAME } ?: ivrit.firstOrNull()
        }

        fun parseList(text: String): List<Endpoint> {
            val root = Json.parseToJsonElement(text)
            val arr = (root as? JsonObject)?.get("endpoints") as? JsonArray ?: (root as? JsonArray) ?: return emptyList()
            return arr.mapNotNull { (it as? JsonObject)?.let(::parseEndpoint) }
        }

        private fun parseEndpoint(o: JsonObject): Endpoint? {
            val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return null
            return Endpoint(id, o["name"]?.jsonPrimitive?.contentOrNull.orEmpty(), o["image"]?.jsonPrimitive?.contentOrNull.orEmpty())
        }

        /**
         * The new endpoint: ivrit.ai's settings — 16–48 GB GPUs, 0 workers when idle (no cost), at most 2 at once,
         * 5 s idle timeout, FlashBoot for faster cold starts, 15 min limit per call. Pure — unit-tested.
         */
        fun createBody(): String = buildJsonObject {
            put("name", NAME)
            put("type", "QUEUE")
            put("image", IMAGE)
            put("disk", 50)
            putJsonObject("gpu") {
                put("pools", JsonArray(listOf("ADA_24", "AMPERE_24", "AMPERE_48", "AMPERE_16").map { kotlinx.serialization.json.JsonPrimitive(it) }))
                put("count", 1)
                put("minCudaVersion", "12.0")
            }
            putJsonObject("workers") {
                put("min", 0)
                put("max", 2)
                put("idleTimeout", 5)
            }
            putJsonObject("scaling") {
                put("type", "QUEUE_DELAY")
                put("queueDelay", 4)
            }
            put("timeout", IvritRunPodTranscriber.EXECUTION_TIMEOUT_MS)
            put("flashboot", "FLASHBOOT")
        }.toString()
    }
}
