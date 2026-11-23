package app.callscribe.ui

import android.content.Intent
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
import app.callscribe.data.SecretStore
import app.callscribe.detect.WorkScheduler
import app.callscribe.rules.PhoneNumbers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Page(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
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
internal fun Section(title: String) = Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

@Composable
fun SettingsScreen(graph: AppGraph, onBack: () -> Unit, onProviders: () -> Unit, onRules: () -> Unit, onRetention: () -> Unit) {
    val s by graph.settings.flow.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    Page("Settings", onBack) {
        val st = s ?: return@Page
        NavRow("Transcription & AI providers", "Your RunPod endpoint and your AI provider (Gemini, OpenRouter, Claude)", onProviders)
        NavRow("Processing & retention policies", "Per caller type or contact: transcribe, summarize, how long to keep", onRules)
        NavRow("Retention review", "Recordings past their retention period", onRetention)
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
internal fun NavRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun SwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

@Composable
internal fun CheckRow(text: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled) { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange, enabled = enabled)
        Text(text)
    }
}
