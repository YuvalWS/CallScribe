package app.callscribe.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.callscribe.AppGraph
import app.callscribe.data.AppSettings
import app.callscribe.data.SecretStore
import app.callscribe.providers.ModelCatalog
import app.callscribe.providers.ModelOption
import app.callscribe.providers.ProviderError
import app.callscribe.providers.ivrit.RunPodSetup
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
    fun upd(t: (AppSettings) -> AppSettings) = scope.launch { graph.settings.update(t) }
    listOf(
        "ivrit-runpod" to "ivrit.ai on your RunPod endpoint (cloud, most accurate)",
        OnDeviceTranscriber.ID to "On this phone (free, audio never leaves the phone)",
    ).forEach { (id, label) ->
        val pick = { pickTranscriber(graph, st, id, ::upd) }
        Row(Modifier.fillMaxWidth().clickable(onClick = pick), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(st.transcriberId == id, pick)
            Text(label)
        }
    }
    if (st.transcriberId == OnDeviceTranscriber.ID) OnDeviceSection(graph, st) else {
        OnDeviceLeftover(graph)
        RunPodSection(graph, st)
    }
    DeleteModelPrompt(graph)
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
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var endpoint by remember { mutableStateOf(st.runpodEndpointId) }
    var model by remember { mutableStateOf(st.runpodModel) }
    var price by remember { mutableStateOf(st.runpodPricePerSec.toBigDecimal().toPlainString()) }
    var setup by remember { mutableStateOf<TestResult?>(null) }
    val check = rememberConnectionCheck(
        graph, "RunPod", SecretStore.Key.RUNPOD_API_KEY,
        onSave = {
            graph.settings.update {
                it.copy(runpodEndpointId = endpoint, runpodModel = model, runpodPricePerSec = price.toDoubleOrNull() ?: it.runpodPricePerSec)
            }
        },
        onTest = { graph.providers.transcriber(graph.settings.get().copy(transcriberId = "ivrit-runpod"))!!.test() },
    )
    Text(
        "1. Create a RunPod API key (RunPod → Settings → API Keys → Create API Key, access \"All\" or Read/Write) " +
            "and paste it below. RunPod shows it only once.",
        style = MaterialTheme.typography.bodySmall,
    )
    OutlinedButton(onClick = {
        runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(RunPodSetup.KEY_PAGE))) }
    }) { Text("Open RunPod API keys") }
    KeyField(check, "RunPod API key")
    Text(
        "2. Tap Set up automatically: CallScribe uses your existing ivrit.ai endpoint, or creates one with 0 idle " +
            "workers — it costs nothing until a call is transcribed.",
        style = MaterialTheme.typography.bodySmall,
    )
    Button(enabled = setup != TestResult.Testing, onClick = {
        scope.launch {
            check.commitDraft()
            val key = graph.secrets.get(SecretStore.Key.RUNPOD_API_KEY)
            if (key.isNullOrBlank()) { setup = TestResult.NoKey("RunPod"); return@launch }
            setup = TestResult.Testing
            setup = try {
                val out = RunPodSetup(key).findOrCreate()
                endpoint = out.endpoint.id
                graph.settings.update { it.copy(runpodEndpointId = out.endpoint.id) }
                val health = runCatching { graph.providers.transcriber(graph.settings.get().copy(transcriberId = "ivrit-runpod"))!!.test() }
                    .getOrElse { "Endpoint saved; the connection check failed: ${it.message}" }
                TestResult.Ok(
                    (if (out.created) "Created endpoint \"${out.endpoint.name}\" in your RunPod account." else "Found your ivrit.ai endpoint \"${out.endpoint.name}\".") +
                        " Saved. $health",
                )
            } catch (e: Exception) {
                TestResult.Error(e.message ?: e.javaClass.simpleName)
            }
        }
    }) { Text("Set up automatically") }
    setup?.let { ResultCard(it, okTitle = "✓ RunPod is set up", errorTitle = "✗ Automatic setup failed", busy = "Setting up your endpoint…") }
    Text("Or fill in your own endpoint:", style = MaterialTheme.typography.bodySmall)
    Field("Endpoint ID", endpoint) { endpoint = it.trim() }
    Field("Model", model) { model = it.trim() }
    Field("Worker price, USD per second (for cost estimates)", price, KeyboardType.Decimal) { price = it }
    Text("RunPod bills the worker's start-up and idle time too. This price is corrected from your RunPod balance after each bulk run.",
        style = MaterialTheme.typography.bodySmall)
    SwitchRow("Separate speakers (diarization, slower)", st.diarize) { v -> scope.launch { graph.settings.update { it.copy(diarize = v) } } }
    SaveAndTest(check)
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
    val check = rememberConnectionCheck(
        graph, "Gemini", SecretStore.Key.GEMINI_API_KEY,
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
    KeyField(check, "Gemini API key")
    Field("Model", model) { model = it.trim() }
    ModelPicker(current = model, recommended = AppSettings().geminiModel, onPick = { model = it }) {
        check.commitDraft()
        val key = graph.secrets.get(SecretStore.Key.GEMINI_API_KEY) ?: throw ProviderError("No Gemini API key entered: paste it above first.", false)
        ModelCatalog.gemini(key)
    }
    Field("Requests per minute allowed", rpm, KeyboardType.Number) { rpm = it }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Field("$ / M input tokens", pin, KeyboardType.Decimal, Modifier.weight(1f)) { pin = it }
        Field("$ / M output tokens", pout, KeyboardType.Decimal, Modifier.weight(1f)) { pout = it }
    }
    Text("Leave prices at 0 on the free tier.", style = MaterialTheme.typography.bodySmall)
    SaveAndTest(check)
}

@Composable
private fun OpenRouterSection(graph: AppGraph, st: AppSettings) {
    Text("Get a key at openrouter.ai → Keys. Each request is billed; the exact cost is recorded per call. " +
        "Default model anthropic/claude-haiku-5.5 costs about $0.0005 per call.", style = MaterialTheme.typography.bodySmall)
    var model by remember { mutableStateOf(st.openrouterModel) }
    val check = rememberConnectionCheck(
        graph, "OpenRouter", SecretStore.Key.OPENROUTER_API_KEY,
        onSave = { graph.settings.update { it.copy(openrouterModel = model) } },
        onTest = { graph.providers.analyzer(graph.settings.get())!!.test() },
    )
    KeyField(check, "OpenRouter API key")
    Field("Model (must support structured outputs)", model) { model = it.trim() }
    ModelPicker(current = model, recommended = AppSettings().openrouterModel, onPick = { model = it }) { ModelCatalog.openRouter() }
    SaveAndTest(check)
}

@Composable
private fun ClaudeSection(graph: AppGraph, st: AppSettings) {
    Text("Create an API key at console.anthropic.com.", style = MaterialTheme.typography.bodySmall)
    val models = app.callscribe.providers.claude.ClaudeAnalyzer.PRICES
    var model by remember { mutableStateOf(st.claudeModel) }
    val check = rememberConnectionCheck(
        graph, "Anthropic", SecretStore.Key.ANTHROPIC_API_KEY,
        onSave = { graph.settings.update { it.copy(claudeModel = model) } },
        onTest = { graph.providers.analyzer(graph.settings.get())!!.test() },
    )
    KeyField(check, "Anthropic API key")
    models.forEach { (m, p) ->
        Row(Modifier.fillMaxWidth().clickable { model = m }, verticalAlignment = Alignment.CenterVertically) {
            RadioButton(model == m, { model = m })
            Text("$m  ($${p.first} / $${p.second} per M tokens)")
        }
    }
    SaveAndTest(check)
}


@Composable
private fun Field(label: String, value: String, type: KeyboardType = KeyboardType.Text, modifier: Modifier = Modifier.fillMaxWidth(), onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = type),
    )
}

/**
 * Key input: never shows the stored key, only a masked hint. "Save key" saves it and checks the connection in the
 * background; a key typed but not saved is saved anyway by Save / Test connection / Set up automatically.
 */
@Composable
private fun KeyField(check: ConnectionCheck, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            check.draft, { check.draft = it }, label = { Text(label) }, singleLine = true, modifier = Modifier.weight(1f),
            placeholder = { Text(check.masked ?: "not set") },
            supportingText = { Text(if (check.masked != null) "Saved: ${check.masked}" else "Not set") },
            visualTransformation = PasswordVisualTransformation(),
        )
        OutlinedButton(enabled = check.draft.isNotBlank(), onClick = { check.saveAndCheck() }) { Text("Save key") }
    }
}

/**
 * One provider's Save / Test connection. The check runs in the app's scope, so it finishes even if the user leaves the
 * screen; a failure found after they left becomes a notification (on screen it's the red card).
 */
private class ConnectionCheck(
    val graph: AppGraph,
    val name: String,
    val key: SecretStore.Key,
    var onSave: suspend () -> Unit,
    var onTest: suspend () -> String,
) {
    var draft by mutableStateOf("")
    var masked by mutableStateOf(graph.secrets.masked(key))
    var result by mutableStateOf<TestResult?>(null)
    @Volatile var onScreen = true

    /** Saves a key that was typed but not saved yet. */
    fun commitDraft() {
        if (draft.isBlank()) return
        graph.secrets.set(key, draft.trim()); draft = ""; masked = graph.secrets.masked(key)
    }

    /** Save: store everything, then check the connection in the background. */
    fun saveAndCheck() {
        commitDraft()
        graph.appScope.launch { onSave(); run(afterSave = true) }
    }

    fun test() {
        commitDraft()
        graph.appScope.launch { onSave(); run(afterSave = false) }
    }

    private suspend fun run(afterSave: Boolean) {
        if (graph.secrets.get(key).isNullOrBlank()) { result = TestResult.NoKey(name); return }
        result = if (afterSave) TestResult.Checking else TestResult.Testing
        val r = try { TestResult.Ok(onTest()) } catch (e: Exception) { TestResult.Error(e.message ?: e.javaClass.simpleName) }
        result = r
        if (r is TestResult.Error) {
            app.callscribe.Logx.w("Providers", "$name connection check failed: ${r.message}")
            if (!onScreen) graph.notifier.notifyConnectionFailed(name, connectionHint(r.message) ?: r.message)
        }
    }
}

@Composable
private fun rememberConnectionCheck(
    graph: AppGraph, name: String, key: SecretStore.Key, onSave: suspend () -> Unit, onTest: suspend () -> String,
): ConnectionCheck {
    val check = remember(key) { ConnectionCheck(graph, name, key, onSave, onTest) }
    check.onSave = onSave // the latest field values
    check.onTest = onTest
    androidx.compose.runtime.DisposableEffect(check) {
        check.onScreen = true
        onDispose { check.onScreen = false }
    }
    return check
}

private sealed interface TestResult {
    data object Saved : TestResult
    data object Testing : TestResult
    /** Saved; the connection is being checked in the background. */
    data object Checking : TestResult
    /** No key entered at all (instead of letting the provider call fail with "key not working"). */
    data class NoKey(val provider: String) : TestResult
    data class Ok(val message: String) : TestResult
    data class Error(val message: String) : TestResult
}

/** A plain-language hint for common connection errors. Pure. */
internal fun connectionHint(message: String): String? {
    val m = message.lowercase()
    return when {
        "401" in m || "403" in m || "unauthor" in m || "api key" in m || "permission" in m -> "The API key looks wrong or has no access. Paste it again and tap Save key."
        "404" in m || "not found" in m -> "Not found: check the endpoint ID or the model name."
        "unable to resolve host" in m || "failed to connect" in m || "timeout" in m || "timed out" in m || "network" in m ->
            "No connection to the server. Check the phone's internet and try again."
        "429" in m || "quota" in m || "rate" in m -> "The provider is limiting requests right now. Wait a minute and try again."
        else -> null
    }
}

@Composable
private fun SaveAndTest(check: ConnectionCheck) {
    val busy = check.result == TestResult.Testing || check.result == TestResult.Checking
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { check.saveAndCheck() }) { Text("Save") }
        OutlinedButton(enabled = !busy, onClick = { check.test() }) { Text("Test connection") }
    }
    check.result?.let { ResultCard(it) }
}

/** A large green / red result card, scrolled into view so it isn't missed below the fold. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ResultCard(
    r: TestResult, okTitle: String = "✓ Connection works", errorTitle: String = "✗ Connection failed", busy: String = "Testing the connection…",
) {
    val bring = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    androidx.compose.runtime.LaunchedEffect(r) { runCatching { bring.bringIntoView() } }
    val cs = MaterialTheme.colorScheme
    val (bg, fg) = when (r) {
        is TestResult.Ok -> androidx.compose.ui.graphics.Color(0xFFD7F5DD) to androidx.compose.ui.graphics.Color(0xFF0B5A1E)
        is TestResult.Error, is TestResult.NoKey -> cs.errorContainer to cs.onErrorContainer
        else -> cs.surfaceVariant to cs.onSurfaceVariant
    }
    androidx.compose.material3.Card(
        Modifier.fillMaxWidth().bringIntoViewRequester(bring),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = bg, contentColor = fg),
    ) {
        androidx.compose.foundation.layout.Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (r) {
                TestResult.Saved -> Text("Saved", style = MaterialTheme.typography.titleMedium)
                TestResult.Testing, TestResult.Checking -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(if (r == TestResult.Checking) "Saved · checking the connection…" else busy, style = MaterialTheme.typography.titleMedium)
                }
                is TestResult.NoKey -> {
                    Text("✗ No API key entered", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Paste your ${r.provider} API key in the key field above, then tap Test connection.")
                }
                is TestResult.Ok -> {
                    Text(okTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(r.message)
                }
                is TestResult.Error -> {
                    Text(errorTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    connectionHint(r.message)?.let { Text(it, fontWeight = FontWeight.SemiBold) }
                    Text(r.message, style = MaterialTheme.typography.bodySmall)
                    Text("Your settings are saved. You can fix this later in Settings → Providers.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/**
 * "Choose from list": fetches the provider's models only when tapped (free call, the user's own key), then shows them
 * in a searchable dialog. The app's recommended model is marked; tapping one fills the model field (Save applies it).
 */
@Composable
private fun ModelPicker(current: String, recommended: String, onPick: (String) -> Unit, load: suspend () -> List<ModelOption>) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var models by remember { mutableStateOf<List<ModelOption>?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = !loading, onClick = {
            scope.launch {
                loading = true; error = null
                try { models = load() } catch (e: Exception) { error = e.message ?: e.javaClass.simpleName }
                loading = false
            }
        }) { Text(if (loading) "Loading models…" else "Choose from list") }
        if (current != recommended) {
            androidx.compose.material3.TextButton(onClick = { onPick(recommended) }) { Text("Use recommended") }
        }
    }
    error?.let { Text("Couldn't load the list: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    models?.let { list ->
        var query by remember { mutableStateOf("") }
        // The recommended and current models first, then the provider's order (OpenRouter: cheapest first).
        val ordered = list.sortedBy { if (it.id == recommended) 0 else if (it.id == current) 1 else 2 }
        val shown = ordered.filter { query.isBlank() || query.lowercase() in (it.id + " " + it.name).lowercase() }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { models = null },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { models = null }) { Text("Close") } },
            title = { Text("Choose a model") },
            text = {
                androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Search ${list.size} models") })
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(shown.size) { i ->
                            val m = shown[i]
                            androidx.compose.foundation.layout.Column(
                                Modifier.fillMaxWidth().clickable { onPick(m.id); models = null }.padding(vertical = 8.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    RadioButton(m.id == current, { onPick(m.id); models = null })
                                    Text(m.name, fontWeight = if (m.id == recommended) FontWeight.SemiBold else null)
                                }
                                val note = listOfNotNull(m.id.takeIf { it != m.name }, m.price, "recommended".takeIf { m.id == recommended })
                                if (note.isNotEmpty()) Text(note.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(start = 48.dp))
                            }
                        }
                    }
                }
            },
        )
    }
}
