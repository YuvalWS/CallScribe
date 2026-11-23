package app.callscribe.providers

import app.callscribe.data.AppSettings
import app.callscribe.data.SecretStore
import kotlinx.serialization.Serializable

@Serializable
data class Segment(
    val start: Double,
    val end: Double,
    val text: String,
    val speaker: String? = null,
)

data class Transcript(
    val text: String,
    val segments: List<Segment>,
    val language: String,
    val rawJson: String,
)

data class Cost(val usd: Double, val detail: String, val gpuMs: Long? = null)

sealed interface TranscriptionState {
    data class Pending(val status: String) : TranscriptionState
    data class Done(val transcript: Transcript, val cost: Cost?) : TranscriptionState
    data class Failed(val message: String, val retryable: Boolean) : TranscriptionState
}

class AudioInput(val bytes: ByteArray, val mimeType: String, val fileName: String)

/** Speech-to-text provider. Async by design: submit returns a job handle that survives worker restarts. */
interface Transcriber {
    val id: String
    val displayName: String
    /** Largest audio payload (bytes) this provider accepts; larger files are compressed first. */
    val maxUploadBytes: Long
    fun isConfigured(): Boolean
    /** Free check (no transcription): returns a human-readable status or throws ProviderError. */
    suspend fun test(): String
    suspend fun submit(audio: AudioInput): String
    suspend fun poll(handle: String): TranscriptionState
}

data class CallMeta(
    val counterparty: String?,
    val samsungLabel: String?,
    val labelKind: String,
    val durationSec: Long,
    val localTime: String,
)

@Serializable
data class Analysis(
    val summary_he: String,
    val verdict: String,
    val spam_confidence: Double,
    val category: String,
    val caller_intent: String,
    val action_items: List<String> = emptyList(),
)

data class AnalysisResult(val analysis: Analysis, val rawJson: String, val cost: Cost?, val inputTokens: Long, val outputTokens: Long)

/** LLM that summarizes a call and judges spam. */
interface CallAnalyzer {
    val id: String
    val displayName: String
    fun isConfigured(): Boolean
    /** Free check (no generation): returns a human-readable status or throws ProviderError. */
    suspend fun test(): String
    suspend fun analyze(transcript: String, meta: CallMeta): AnalysisResult
}

class ProviderError(message: String, val retryable: Boolean) : Exception(message)

/** Maps provider ids to implementations built from the current settings. Add new providers here. */
class ProviderRegistry(private val secrets: SecretStore) {
    val transcriberIds = listOf("ivrit-runpod")
    val analyzerIds = listOf("gemini", "openrouter", "claude")

    fun transcriber(s: AppSettings): Transcriber? = when (s.transcriberId) {
        "ivrit-runpod" -> app.callscribe.providers.ivrit.IvritRunPodTranscriber(
            endpointId = s.runpodEndpointId,
            apiKey = secrets.get(SecretStore.Key.RUNPOD_API_KEY),
            model = s.runpodModel,
            diarize = s.diarize,
            pricePerSec = s.runpodPricePerSec,
            baseUrl = s.runpodBaseUrl,
        )
        else -> null
    }

    fun analyzer(s: AppSettings): CallAnalyzer? = when (s.analyzerId) {
        "claude" -> app.callscribe.providers.claude.ClaudeAnalyzer(
            apiKey = secrets.get(SecretStore.Key.ANTHROPIC_API_KEY),
            model = s.claudeModel,
        )
        "gemini" -> app.callscribe.providers.gemini.GeminiAnalyzer(
            apiKey = secrets.get(SecretStore.Key.GEMINI_API_KEY),
            model = s.geminiModel, rpm = s.geminiRpm,
            priceInPerM = s.geminiPriceIn, priceOutPerM = s.geminiPriceOut,
        )
        "openrouter" -> app.callscribe.providers.openrouter.OpenRouterAnalyzer(
            apiKey = secrets.get(SecretStore.Key.OPENROUTER_API_KEY),
            model = s.openrouterModel,
        )
        else -> null
    }
}
