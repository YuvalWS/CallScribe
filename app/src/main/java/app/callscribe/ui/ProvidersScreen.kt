package app.callscribe.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.callscribe.AppGraph
import app.callscribe.data.AppSettings
import app.callscribe.data.SecretStore
import app.callscribe.providers.ProviderError
import app.callscribe.providers.ondevice.OnDeviceTranscriber
import kotlinx.coroutines.launch

@Composable
fun ProvidersScreen(graph: AppGraph, onBack: () -> Unit) {
    val s by graph.settings.flow.collectAsStateWithLifecycle(initialValue = null)

    Page("Providers", onBack) {
        val st = s ?: return@Page
        Text(
            "CallScribe has no servers of its own. Audio goes to the transcription endpoint you set up here, " +
                "and transcripts to the AI provider you choose. You pay those providers directly.",
            style = MaterialTheme.typography.bodySmall,
        )

        Section("Transcription")
        TranscriptionProviderFields(graph, st)
        HorizontalDivider()

        Section("AI summary & spam verdict")
        AnalyzerProviderFields(graph, st)
    }
}

/** Transcription choice (RunPod cloud / on this phone) + the chosen provider's fields. Shared by Providers and onboarding. */
@Composable
internal fun TranscriptionProviderFields(graph: AppGraph, st: AppSettings) {
    val scope = rememberCoroutineScope()
    listOf(
        "ivrit-runpod" to "ivrit.ai on your RunPod endpoint (cloud, most accurate)",
        OnDeviceTranscriber.ID to "On this phone (free, audio never leaves the phone)",
    ).forEach { (id, label) ->
        val pick = { scope.launch { graph.settings.update { it.copy(transcriberId = id) } } }
        Row(Modifier.fillMaxWidth().clickable { pick() }, verticalAlignment = Alignment.CenterVertically) {
            RadioButton(st.transcriberId == id, { pick() })
            Text(label)
        }
    }
    if (st.transcriberId == OnDeviceTranscriber.ID) OnDeviceSection(graph, st) else RunPodSection(graph, st)
}

/** AI provider choice (Gemini / OpenRouter / Claude) + the chosen provider's fields. Shared with onboarding. */
@Composable
internal fun AnalyzerProviderFields(graph: AppGraph, st: AppSettings) {
    val scope = rememberCoroutineScope()
    fun choose(id: String) = scope.launch { graph.settings.update { it.copy(analyzerId = id) } }
    val options = listOf(
        "gemini" to "Google Gemini — free tier available (rate limited)",
        "openrouter" to "OpenRouter — any model, pay per request",
        "claude" to "Claude (Anthropic API)",
    )
    options.forEach { (id, label) ->
        Row(Modifier.fillMaxWidth().clickable { choose(id) }, verticalAlignment = Alignment.CenterVertically) {
            RadioButton(st.analyzerId == id, { choose(id) })
            Text(label)
        }
    }
    when (st.analyzerId) {
        "gemini" -> GeminiSection(graph, st)
        "openrouter" -> OpenRouterSection(graph, st)
        "claude" -> ClaudeSection(graph, st)
    }
}

@Composable
private fun RunPodSection(graph: AppGraph, st: AppSettings) {
    val scope = rememberCoroutineScope()
    Text(
        "Endpoint ID: the short ID of your RunPod serverless endpoint (Serverless → your endpoint; also in its URL " +
            "api.runpod.ai/v2/<ID>/run). Deploy the ivrit-ai/runpod-serverless template with min workers 0.",
        style = MaterialTheme.typography.bodySmall,
    )
    var endpoint by remember { mutableStateOf(st.runpodEndpointId) }
    var model by remember { mutableStateOf(st.runpodModel) }
    var price by remember { mutableStateOf(st.runpodPricePerSec.toBigDecimal().toPlainString()) }
    Field("Endpoint ID", endpoint) { endpoint = it.trim() }
    KeyField(graph, SecretStore.Key.RUNPOD_API_KEY, "RunPod API key")
    Field("Model", model) { model = it.trim() }
    Field("GPU price, USD per second (for cost estimates)", price, KeyboardType.Decimal) { price = it }
    SwitchRow("Separate speakers (diarization, slower)", st.diarize) { v -> scope.launch { graph.settings.update { it.copy(diarize = v) } } }
    SaveAndTest(
        onSave = {
            graph.settings.update {
                it.copy(runpodEndpointId = endpoint, runpodModel = model, runpodPricePerSec = price.toDoubleOrNull() ?: it.runpodPricePerSec)
            }
        },
        onTest = { graph.providers.transcriber(graph.settings.get().copy(transcriberId = "ivrit-runpod"))!!.test() },
    )
}

@Composable
private fun GeminiSection(graph: AppGraph, st: AppSettings) {
    Text("Get a key at aistudio.google.com → API keys. The free tier allows ~15 requests/minute; calls are spaced automatically.",
        style = MaterialTheme.typography.bodySmall)
    Text("Privacy: on Gemini's free tier Google may use your prompts (i.e. call transcripts) to improve its products. " +
        "Use a paid tier or another provider for private calls.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    var model by remember { mutableStateOf(st.geminiModel) }
    var rpm by remember { mutableStateOf(st.geminiRpm.toString()) }
    var pin by remember { mutableStateOf(st.geminiPriceIn.toString()) }
    var pout by remember { mutableStateOf(st.geminiPriceOut.toString()) }
    KeyField(graph, SecretStore.Key.GEMINI_API_KEY, "Gemini API key")
    Field("Model (e.g. gemini-3.5-flash-lite)", model) { model = it.trim() }
    Field("Requests per minute allowed", rpm, KeyboardType.Number) { rpm = it }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Field("$ / M input tokens", pin, KeyboardType.Decimal, Modifier.weight(1f)) { pin = it }
        Field("$ / M output tokens", pout, KeyboardType.Decimal, Modifier.weight(1f)) { pout = it }
    }
    Text("Leave prices at 0 on the free tier.", style = MaterialTheme.typography.bodySmall)
    SaveAndTest(
        onSave = {
            graph.settings.update {
                it.copy(
                    geminiModel = model, geminiRpm = rpm.toIntOrNull() ?: it.geminiRpm,
                    geminiPriceIn = pin.toDoubleOrNull() ?: 0.0, geminiPriceOut = pout.toDoubleOrNull() ?: 0.0,
                )
            }
        },
        onTest = { graph.providers.analyzer(graph.settings.get())!!.test() },
    )
}

@Composable
private fun OpenRouterSection(graph: AppGraph, st: AppSettings) {
    Text("Get a key at openrouter.ai → Keys. Each request is billed; the exact cost is recorded per call. " +
        "Default model anthropic/claude-haiku-5.5 costs about $0.0005 per call.", style = MaterialTheme.typography.bodySmall)
    var model by remember { mutableStateOf(st.openrouterModel) }
    KeyField(graph, SecretStore.Key.OPENROUTER_API_KEY, "OpenRouter API key")
    Field("Model (must support structured outputs)", model) { model = it.trim() }
    SaveAndTest(
        onSave = { graph.settings.update { it.copy(openrouterModel = model) } },
        onTest = { graph.providers.analyzer(graph.settings.get())!!.test() },
    )
}

@Composable
private fun ClaudeSection(graph: AppGraph, st: AppSettings) {
    Text("Create an API key at console.anthropic.com.", style = MaterialTheme.typography.bodySmall)
    val models = app.callscribe.providers.claude.ClaudeAnalyzer.PRICES
    var model by remember { mutableStateOf(st.claudeModel) }
    KeyField(graph, SecretStore.Key.ANTHROPIC_API_KEY, "Anthropic API key")
    models.forEach { (m, p) ->
        Row(Modifier.fillMaxWidth().clickable { model = m }, verticalAlignment = Alignment.CenterVertically) {
            RadioButton(model == m, { model = m })
            Text("$m  ($${p.first} / $${p.second} per M tokens)")
        }
    }
    SaveAndTest(
        onSave = { graph.settings.update { it.copy(claudeModel = model) } },
        onTest = { graph.providers.analyzer(graph.settings.get())!!.test() },
    )
}


@Composable
private fun Field(label: String, value: String, type: KeyboardType = KeyboardType.Text, modifier: Modifier = Modifier.fillMaxWidth(), onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = type),
    )
}

/** Key input: never shows the stored key, only a masked hint; saves immediately on "Save key". */
@Composable
private fun KeyField(graph: AppGraph, key: SecretStore.Key, label: String) {
    var value by remember { mutableStateOf("") }
    var masked by remember { mutableStateOf(graph.secrets.masked(key)) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value, { value = it }, label = { Text(label) }, singleLine = true, modifier = Modifier.weight(1f),
            placeholder = { Text(masked ?: "not set") },
            supportingText = { Text(if (masked != null) "Saved: $masked" else "Not set") },
            visualTransformation = PasswordVisualTransformation(),
        )
        OutlinedButton(enabled = value.isNotBlank(), onClick = {
            graph.secrets.set(key, value); value = ""; masked = graph.secrets.masked(key)
        }) { Text("Save key") }
    }
}

@Composable
private fun SaveAndTest(onSave: suspend () -> Unit, onTest: suspend () -> String) {
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<String?>(null) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { scope.launch { onSave(); result = "Saved" } }) { Text("Save") }
        OutlinedButton(onClick = {
            scope.launch {
                onSave()
                result = "Testing…"
                result = try { "✓ " + onTest() } catch (e: ProviderError) { "✗ ${e.message}" } catch (e: Exception) { "✗ ${e.message ?: e.javaClass.simpleName}" }
            }
        }) { Text("Test connection") }
    }
    result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}
