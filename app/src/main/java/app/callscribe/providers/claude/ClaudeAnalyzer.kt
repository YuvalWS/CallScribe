package app.callscribe.providers.claude

import app.callscribe.providers.Analysis
import app.callscribe.providers.AnalysisResult
import app.callscribe.providers.CallAnalyzer
import app.callscribe.providers.CallMeta
import app.callscribe.providers.Cost
import app.callscribe.providers.ProviderError
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Summary + spam verdict with Claude, using structured outputs (JSON schema). */
class ClaudeAnalyzer(
    private val apiKey: String?,
    private val model: String,
) : CallAnalyzer {
    override val id = "claude"
    override val displayName = "Claude (Anthropic)"

    override fun isConfigured() = !apiKey.isNullOrBlank()

    private val client: AnthropicClient by lazy {
        AnthropicOkHttpClient.builder().apiKey(apiKey!!).maxRetries(2).build()
    }

    /** Retrieves the model's metadata — validates key and model id without generating (free). */
    override suspend fun test(): String = withContext(Dispatchers.IO) {
        if (!isConfigured()) throw ProviderError("API key is required", false)
        try {
            val info = client.models().retrieve(model)
            "Connected. Model: ${info.displayName()}"
        } catch (e: UnauthorizedException) {
            throw ProviderError("Invalid API key", false)
        } catch (e: AnthropicServiceException) {
            throw ProviderError("HTTP ${e.statusCode()}: ${e.message}", false)
        } catch (e: AnthropicIoException) {
            throw ProviderError("Network error: ${e.message}", true)
        }
    }

    override suspend fun analyze(transcript: String, meta: CallMeta): AnalysisResult = withContext(Dispatchers.IO) {
        require(isConfigured()) { "Anthropic API key missing" }
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(4000L)
            .system(SYSTEM_PROMPT)
            .outputConfig(
                OutputConfig.builder()
                    .effort(OutputConfig.Effort.LOW)
                    .format(JsonOutputFormat.builder().schema(schema()).build())
                    .build()
            )
            .addUserMessage(userPrompt(transcript, meta))
            .build()

        val msg = try {
            client.messages().create(params)
        } catch (e: UnauthorizedException) {
            throw ProviderError("Anthropic: invalid API key", false)
        } catch (e: RateLimitException) {
            throw ProviderError("Anthropic: rate limited", true)
        } catch (e: AnthropicServiceException) {
            throw ProviderError("Anthropic HTTP ${e.statusCode()}: ${e.message}", e.statusCode() >= 500)
        } catch (e: AnthropicIoException) {
            throw ProviderError("Anthropic network error: ${e.message}", true)
        }

        val inTok = msg.usage().inputTokens()
        val outTok = msg.usage().outputTokens()
        val cost = Cost(price(model, inTok, outTok), "$inTok in / $outTok out tokens")

        val stop = msg.stopReason().orElse(null)
        if (stop == StopReason.REFUSAL) {
            val a = Analysis("המודל סירב לנתח את השיחה.", "unsure", 0.5, "other", "", emptyList())
            return@withContext AnalysisResult(a, "{\"refusal\":true}", cost, inTok, outTok)
        }
        if (stop == StopReason.MAX_TOKENS) throw ProviderError("Anthropic: response cut off (max_tokens)", true)

        val text = msg.content().mapNotNull { b -> b.text().orElse(null)?.text() }.joinToString("")
        val analysis = runCatching { json.decodeFromString(Analysis.serializer(), text) }
            .getOrElse { throw ProviderError("Anthropic: could not parse JSON: ${text.take(200)}", false) }
        AnalysisResult(analysis, text, cost, inTok, outTok)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** USD per million tokens (input, output), Anthropic first-party list prices. */
        val PRICES: Map<String, Pair<Double, Double>> = mapOf(
            "claude-opus-5-5" to (4.0 to 20.0),
            "claude-sonnet-5-5" to (2.0 to 10.0),
            "claude-haiku-5-5" to (0.10 to 0.50),
        )
        val MODELS = PRICES.keys.toList()

        fun price(model: String, inTok: Long, outTok: Long): Double {
            val (i, o) = PRICES[model] ?: return 0.0
            return inTok / 1e6 * i + outTok / 1e6 * o
        }

        val SYSTEM_PROMPT = """
            You analyse transcripts of phone calls received by an Israeli user. Transcripts come from automatic
            Hebrew speech recognition and may contain errors, missing words or English fragments.
            Write summary_he and caller_intent in Hebrew. summary_he is 2-4 sentences.
            Judge whether the call is unwanted. Recorded or robotic messages, telemarketing, loan / insurance /
            investment offers, surveys and political campaigns are spam. Anything asking for codes, ID numbers,
            passwords or payment details is scam_or_phishing. Calls from people the user plausibly knows,
            deliveries, appointments and businesses the user deals with are not spam.
            Samsung's caller-ID label (if given) is a strong hint but can be wrong; the transcript decides.
            A caller saved as a contact defaults to not_spam unless the transcript clearly says otherwise.
            If the call was screened, an AI assistant voice may speak on the user's behalf; focus on what the caller said.
            If the transcript is too short or unclear, use verdict "unsure". Never invent details not in the transcript.
            spam_confidence is the probability (0-1) that the call is unwanted.
        """.trimIndent()

        fun userPrompt(transcript: String, meta: CallMeta): String {
            val cut = transcript.length > 30_000
            return buildString {
                appendLine("Call metadata:")
                appendLine("- Caller as shown on the phone: ${meta.counterparty ?: "(hidden)"}")
                appendLine("- Caller type: ${meta.labelKind}")
                meta.samsungLabel?.let { appendLine("- Samsung caller-ID labelled this call: $it") }
                appendLine("- Duration: ${meta.durationSec} s")
                appendLine("- Local time: ${meta.localTime}")
                appendLine()
                appendLine("<transcript>")
                appendLine(if (cut) transcript.take(30_000) else transcript)
                appendLine("</transcript>")
                if (cut) appendLine("(The transcript was cut at 30,000 characters.)")
            }
        }

        private fun schema(): JsonOutputFormat.Schema {
            fun str(desc: String) = mapOf("type" to "string", "description" to desc)
            val props = mapOf(
                "summary_he" to str("2-4 sentence Hebrew summary"),
                "verdict" to mapOf("type" to "string", "enum" to listOf("spam", "not_spam", "unsure")),
                "spam_confidence" to mapOf("type" to "number", "description" to "0.0-1.0 probability the call is unwanted"),
                "category" to mapOf(
                    "type" to "string",
                    "enum" to listOf(
                        "robocall", "telemarketing", "scam_or_phishing", "survey_or_political",
                        "business", "personal", "delivery_or_service", "other",
                    ),
                ),
                "caller_intent" to str("One short Hebrew line: what the caller wants"),
                "action_items" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
            )
            return JsonOutputFormat.Schema.builder()
                .putAdditionalProperty("type", JsonValue.from("object"))
                .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                .putAdditionalProperty("required", JsonValue.from(props.keys.toList()))
                .putAdditionalProperty("properties", JsonValue.from(props))
                .build()
        }
    }
}
