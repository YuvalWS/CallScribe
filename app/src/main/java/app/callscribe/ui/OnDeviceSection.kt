package app.callscribe.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import app.callscribe.providers.ondevice.OnDeviceTranscriber
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.callscribe.AppGraph
import app.callscribe.data.AppSettings
import app.callscribe.providers.ProviderError
import app.callscribe.providers.ondevice.ModelState
import app.callscribe.providers.ondevice.OnDeviceModels
import kotlinx.coroutines.launch

private val askDelete = mutableStateOf(false)

/** Switching away from on-device transcription offers to free the model's space. */
internal fun pickTranscriber(graph: AppGraph, st: AppSettings, id: String, upd: ((AppSettings) -> AppSettings) -> Any) {
    if (id == st.transcriberId) return
    upd { it.copy(transcriberId = id) }
    if (st.transcriberId == OnDeviceTranscriber.ID && graph.models.isInstalled(OnDeviceModels.ZIPFORMER_HE)) askDelete.value = true
}

@Composable
internal fun DeleteModelPrompt(graph: AppGraph) {
    if (!askDelete.value) return
    val model = OnDeviceModels.ZIPFORMER_HE
    AlertDialog(
        onDismissRequest = { askDelete.value = false },
        title = { Text("Delete the on-device model?") },
        text = { Text("It uses ${"%.0f MB".format(graph.models.usedBytes(model) / 1e6)} on the phone. You can download it again any time.") },
        confirmButton = { TextButton(onClick = { graph.models.delete(model); askDelete.value = false }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { askDelete.value = false }) { Text("Keep") } },
    )
}

/** While another transcriber is selected, a downloaded model still shows here so its space can be freed. */
@Composable
internal fun OnDeviceLeftover(graph: AppGraph) {
    val model = OnDeviceModels.ZIPFORMER_HE
    val state by graph.models.state(model).collectAsStateWithLifecycle()
    if (state != ModelState.Installed) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("On-device model downloaded (${"%.0f MB".format(graph.models.usedBytes(model) / 1e6)}), not in use.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { graph.models.delete(model) }) { Text("Delete") }
    }
}

/** Settings → Providers → "On this phone": model download / delete, threads, charging switch, test. */
@Composable
internal fun OnDeviceSection(graph: AppGraph, st: AppSettings) {
    val scope = rememberCoroutineScope()
    val model = OnDeviceModels.ZIPFORMER_HE
    val state by graph.models.state(model).collectAsStateWithLifecycle()
    val mb = { b: Long -> "%.0f MB".format(b / 1e6) }

    Text(
        "Runs ${model.title} with sherpa-onnx on the phone. Free and private: the audio never leaves the phone. " +
            "Hebrew accuracy is close to the cloud model; numbers and English words are weaker. " +
            "A 1-minute call takes about 25 seconds.",
        style = MaterialTheme.typography.bodySmall,
    )
    Text("Model: ${model.repo} (${model.license}), ${mb(model.totalBytes)} download.", style = MaterialTheme.typography.bodySmall)

    when (val s = state) {
        ModelState.NotInstalled -> Button(onClick = { graph.models.startDownload(model) }) { Text("Download model (${mb(model.totalBytes)})") }
        is ModelState.Downloading -> {
            Text("Downloading… ${mb(s.doneBytes)} of ${mb(s.totalBytes)}")
            LinearProgressIndicator(progress = { s.doneBytes.toFloat() / s.totalBytes }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { graph.models.cancelDownload(model) }) { Text("Cancel") }
        }
        is ModelState.Failed -> {
            Text("✗ ${s.message}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Button(onClick = { graph.models.startDownload(model) }) { Text("Retry download") }
        }
        ModelState.Installed -> {
            var result by remember { mutableStateOf<String?>(null) }
            Text("✓ Model downloaded (${mb(graph.models.usedBytes(model))} on the phone). Cost per call: $0.",
                style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    scope.launch {
                        result = "Testing…"
                        result = try {
                            "✓ " + graph.providers.transcriber(graph.settings.get())!!.test()
                        } catch (e: ProviderError) { "✗ ${e.message}" } catch (e: Exception) { "✗ ${e.message ?: e.javaClass.simpleName}" }
                    }
                }) { Text("Test model") }
                OutlinedButton(onClick = { graph.models.delete(model); result = null }) { Text("Delete model") }
            }
            result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }

    var threads by remember { mutableFloatStateOf(st.onDeviceThreads.toFloat()) }
    Text("CPU threads: ${threads.toInt()} (2 is usually fastest; more can be slower)", style = MaterialTheme.typography.bodySmall)
    Slider(
        value = threads, onValueChange = { threads = it }, valueRange = 1f..4f, steps = 2,
        onValueChangeFinished = { scope.launch { graph.settings.update { it.copy(onDeviceThreads = threads.toInt()) } } },
    )
    SwitchRow("Automatic transcription only while charging", st.onDeviceOnlyWhileCharging) { v ->
        scope.launch { graph.settings.update { it.copy(onDeviceOnlyWhileCharging = v) } }
    }
    Text("The Transcribe button always runs right away.", style = MaterialTheme.typography.bodySmall)
}
