package app.callscribe.providers.claude

import app.callscribe.providers.Analysis
import app.callscribe.providers.AnalysisPrompt
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
            .system(AnalysisPrompt.SYSTEM)
            .outputConfig(
                OutputConfig.builder()
                    .effort(OutputConfig.Effort.LOW)
                    .format(JsonOutputFormat.builder().schema(schema()).build())
                    .build()
            )
            .addUserMessage(AnalysisPrompt.user(transcript, meta))
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
        val analysis = AnalysisPrompt.parse(text)
        AnalysisResult(analysis, text, cost, inTok, outTok)
    }

    companion object {

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

        private fun schema(): JsonOutputFormat.Schema {
            val m = AnalysisPrompt.schemaMap()
            val b = JsonOutputFormat.Schema.builder()
            m.forEach { (k, v) -> b.putAdditionalProperty(k, JsonValue.from(v)) }
            return b.build()
        }
    }
}
