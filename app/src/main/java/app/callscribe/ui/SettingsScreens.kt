package app.callscribe.ui

import android.app.Activity
import android.content.Intent
import android.provider.ContactsContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.callscribe.AppGraph
import app.callscribe.data.AppSettings
import app.callscribe.data.ContactRuleEntity
import app.callscribe.data.RuleList
import app.callscribe.data.RuleMode
import app.callscribe.data.SecretStore
import app.callscribe.detect.WorkScheduler
import app.callscribe.providers.ProviderError
import app.callscribe.providers.claude.ClaudeAnalyzer
import app.callscribe.rules.AutoMode
import app.callscribe.rules.PhoneNumbers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Page(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }
}

@Composable
private fun Section(title: String) = Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

@Composable
fun SettingsScreen(graph: AppGraph, onBack: () -> Unit, onProviders: () -> Unit, onRules: () -> Unit) {
    val s by graph.settings.flow.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    Page("Settings", onBack) {
        val st = s ?: return@Page
        NavRow("Transcription & AI providers", "Your own RunPod endpoint and Anthropic key", onProviders)
        NavRow("Automatic processing", "Which calls are processed automatically", onRules)
        HorizontalDivider()

        Section("Recordings folder")
        var prefixes by remember { mutableStateOf(st.pathPrefixes.joinToString(", ")) }
        OutlinedTextField(prefixes, { prefixes = it }, label = { Text("Folders (comma separated)") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    graph.settings.update { it.copy(pathPrefixes = prefixes.split(',').map { p -> p.trim() }.filter { p -> p.isNotEmpty() }) }
                    WorkScheduler.scanNow(ctx)
                }
            }) { Text("Save & rescan") }
            OutlinedButton(onClick = { scope.launch { graph.db.recordings().deleteMissing() } }) { Text("Remove entries without audio") }
        }
        HorizontalDivider()

        Section("Export (webhook)")
        Text("Optional: also upload recordings to your own server, e.g. a Home Assistant webhook.", style = MaterialTheme.typography.bodySmall)
        var url by remember { mutableStateOf(st.webhookUrl) }
        var token by remember { mutableStateOf("") }
        OutlinedTextField(url, { url = it }, label = { Text("Webhook URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(
            token, { token = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text("Bearer token (optional)") },
            placeholder = { Text(graph.secrets.masked(SecretStore.Key.WEBHOOK_TOKEN) ?: "") },
            visualTransformation = PasswordVisualTransformation(),
        )
        SwitchRow("Export automatically processed calls", st.autoExport) { v -> scope.launch { graph.settings.update { it.copy(autoExport = v) } } }
        Button(onClick = {
            scope.launch {
                graph.settings.update { it.copy(webhookUrl = url.trim()) }
                if (token.isNotBlank()) graph.secrets.set(SecretStore.Key.WEBHOOK_TOKEN, token)
                token = ""
            }
        }) { Text("Save") }
        HorizontalDivider()

        Section("Background")
        Text(
            "For reliable automatic processing, let CallScribe run in the background: Settings → Battery → Background usage limits → Never sleeping apps.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = {
            ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }) { Text("Open battery settings") }
    }
}

@Composable
private fun NavRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

@Composable
private fun CheckRow(text: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled) { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange, enabled = enabled)
        Text(text)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvidersScreen(graph: AppGraph, onBack: () -> Unit) {
    val s by graph.settings.flow.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    Page("Providers", onBack) {
        val st = s ?: return@Page
        Text(
            "CallScribe has no servers of its own. Audio goes to the transcription endpoint you set up here, " +
                "and transcripts to your own AI account. You pay those providers directly.",
            style = MaterialTheme.typography.bodySmall,
        )

        Section("Transcription — ivrit.ai on RunPod")
        Text(
            "Deploy the ivrit-ai/runpod-serverless template on runpod.io (min workers 0), then paste the endpoint ID and an API key. See README → Setup.",
            style = MaterialTheme.typography.bodySmall,
        )
        var endpoint by remember { mutableStateOf(st.runpodEndpointId) }
        var rpKey by remember { mutableStateOf("") }
        var model by remember { mutableStateOf(st.runpodModel) }
        var price by remember { mutableStateOf(st.runpodPricePerSec.toString()) }
        OutlinedTextField(endpoint, { endpoint = it.trim() }, label = { Text("Endpoint ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            rpKey, { rpKey = it }, label = { Text("RunPod API key") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(graph.secrets.masked(SecretStore.Key.RUNPOD_API_KEY) ?: "not set") },
            visualTransformation = PasswordVisualTransformation(),
        )
        OutlinedTextField(model, { model = it.trim() }, label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            price, { price = it }, label = { Text("GPU price, USD per second (for cost estimates)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
        )
        SwitchRow("Separate speakers (diarization, slower)", st.diarize) { v -> scope.launch { graph.settings.update { it.copy(diarize = v) } } }
        var rpResult by remember { mutableStateOf<String?>(null) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    graph.settings.update {
                        it.copy(runpodEndpointId = endpoint, runpodModel = model, runpodPricePerSec = price.toDoubleOrNull() ?: it.runpodPricePerSec)
                    }
                    if (rpKey.isNotBlank()) graph.secrets.set(SecretStore.Key.RUNPOD_API_KEY, rpKey)
                    rpKey = ""
                    rpResult = "Saved"
                }
            }) { Text("Save") }
            OutlinedButton(onClick = {
                scope.launch {
                    rpResult = "Testing…"
                    rpResult = runTest { graph.providers.transcriber(graph.settings.get())!!.test() }
                }
            }) { Text("Test connection") }
        }
        rpResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        HorizontalDivider()

        Section("AI analysis — Claude")
        Text("Create an API key at console.anthropic.com. Haiku is the cheapest; Opus is the most capable.", style = MaterialTheme.typography.bodySmall)
        var aKey by remember { mutableStateOf("") }
        OutlinedTextField(
            aKey, { aKey = it }, label = { Text("Anthropic API key") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(graph.secrets.masked(SecretStore.Key.ANTHROPIC_API_KEY) ?: "not set") },
            visualTransformation = PasswordVisualTransformation(),
        )
        var expanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = st.claudeModel, onValueChange = {}, readOnly = true, label = { Text("Model") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                ClaudeAnalyzer.MODELS.forEach { m ->
                    val (i, o) = ClaudeAnalyzer.PRICES.getValue(m)
                    DropdownMenuItem(text = { Text("$m  ($$i / $$o per M tokens)") }, onClick = {
                        expanded = false
                        scope.launch { graph.settings.update { it.copy(claudeModel = m) } }
                    })
                }
            }
        }
        var aResult by remember { mutableStateOf<String?>(null) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (aKey.isNotBlank()) graph.secrets.set(SecretStore.Key.ANTHROPIC_API_KEY, aKey)
                aKey = ""; aResult = "Saved"
            }) { Text("Save key") }
            OutlinedButton(onClick = {
                scope.launch {
                    aResult = "Testing…"
                    aResult = runTest { graph.providers.analyzer(graph.settings.get())!!.test() }
                }
            }) { Text("Test connection") }
        }
        aResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

private suspend fun runTest(block: suspend () -> String): String = try {
    block()
} catch (e: ProviderError) {
    "✗ ${e.message}"
} catch (e: Exception) {
    "✗ ${e.message ?: e.javaClass.simpleName}"
}

@Composable
fun RulesScreen(graph: AppGraph, onBack: () -> Unit) {
    val s by graph.settings.flow.collectAsStateWithLifecycle(initialValue = null)
    val rules by graph.db.contactRules().observe(RuleList.AUTO_PROCESS).collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    fun upd(t: (AppSettings) -> AppSettings) = scope.launch { graph.settings.update(t) }

    var pickMode by remember { mutableStateOf(RuleMode.INCLUDE) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val uri = res.data?.data
        if (res.resultCode != Activity.RESULT_OK || uri == null) return@rememberLauncherForActivityResult
        // A phone-number pick grants one-off read access to that row: no READ_CONTACTS permission needed.
        ctx.contentResolver.query(
            uri, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null,
        )?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getString(0) ?: return@use
                val num = PhoneNumbers.normalize(c.getString(1) ?: "").orEmpty()
                scope.launch {
                    graph.db.contactRules().insert(ContactRuleEntity(list = RuleList.AUTO_PROCESS, mode = pickMode, displayName = name, numbers = num))
                }
            }
        }
    }
    fun pick(mode: RuleMode) {
        pickMode = mode
        picker.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
    }

    Page("Automatic processing", onBack) {
        val st = s ?: return@Page
        val r = st.autoRules
        Text("New recordings are processed automatically according to these rules. Any call can still be transcribed manually.",
            style = MaterialTheme.typography.bodySmall)
        AutoMode.entries.forEach { m ->
            Row(Modifier.fillMaxWidth().clickable { upd { it.copy(autoRules = it.autoRules.copy(mode = m)) } }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(r.mode == m, { upd { it.copy(autoRules = it.autoRules.copy(mode = m)) } })
                Text(when (m) { AutoMode.OFF -> "Off (manual only)"; AutoMode.ALL -> "All calls"; AutoMode.CUSTOM -> "Only these calls:" })
            }
        }
        val custom = r.mode == AutoMode.CUSTOM
        Column(Modifier.padding(start = 24.dp)) {
            CheckRow("Labelled spam by Samsung", r.spam, custom) { v -> upd { it.copy(autoRules = it.autoRules.copy(spam = v)) } }
            CheckRow("Labelled fraud by Samsung", r.fraud, custom) { v -> upd { it.copy(autoRules = it.autoRules.copy(fraud = v)) } }
            CheckRow("Other Samsung labels", r.otherLabels, custom) { v -> upd { it.copy(autoRules = it.autoRules.copy(otherLabels = v)) } }
            CheckRow("Unknown numbers", r.unknownNumbers, custom) { v -> upd { it.copy(autoRules = it.autoRules.copy(unknownNumbers = v)) } }
            CheckRow("Private / hidden numbers", r.privateNumbers, custom) { v -> upd { it.copy(autoRules = it.autoRules.copy(privateNumbers = v)) } }
            CheckRow("Contacts", r.contacts, custom) { v -> upd { it.copy(autoRules = it.autoRules.copy(contacts = v)) } }
        }
        SwitchRow("Run AI analysis after transcription", st.autoAnalyze) { v -> upd { it.copy(autoAnalyze = v) } }
        HorizontalDivider()

        Section("Always process")
        Text("Processed even if the rules above would skip them (unless also on Never process).", style = MaterialTheme.typography.bodySmall)
        RuleList(rules.filter { it.mode == RuleMode.INCLUDE }) { scope.launch { graph.db.contactRules().delete(it.id) } }
        TextButton(onClick = { pick(RuleMode.INCLUDE) }) { Text("+ Add contact") }
        HorizontalDivider()

        Section("Never process")
        Text("Never processed automatically and never uploaded unless you tap Transcribe.", style = MaterialTheme.typography.bodySmall)
        RuleList(rules.filter { it.mode == RuleMode.EXCLUDE }) { scope.launch { graph.db.contactRules().delete(it.id) } }
        TextButton(onClick = { pick(RuleMode.EXCLUDE) }) { Text("+ Add contact") }
    }
}

@Composable
private fun RuleList(items: List<ContactRuleEntity>, onDelete: (ContactRuleEntity) -> Unit) {
    if (items.isEmpty()) Text("—", style = MaterialTheme.typography.bodySmall)
    items.forEach { r ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.displayName)
                if (r.numbers.isNotBlank()) Text("…${r.numbers}", style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = { onDelete(r) }) { Icon(Icons.Default.Delete, "Remove") }
        }
    }
}
