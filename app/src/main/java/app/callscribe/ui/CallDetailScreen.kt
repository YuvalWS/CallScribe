package app.callscribe.ui

import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.callscribe.AppGraph
import app.callscribe.data.ContactRuleEntity
import app.callscribe.data.RecordingEntity
import app.callscribe.data.RuleList
import app.callscribe.data.RuleMode
import app.callscribe.data.Status
import app.callscribe.detect.WorkScheduler
import app.callscribe.pipeline.ProcessWorker
import app.callscribe.providers.Segment
import app.callscribe.rules.PhoneNumbers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallDetailScreen(graph: AppGraph, id: Long, onBack: () -> Unit, onSetup: () -> Unit) {
    val r by graph.db.recordings().observe(id).collectAsStateWithLifecycle(initialValue = null)
    val settings by graph.settings.flow.collectAsStateWithLifecycle(initialValue = null)
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(r?.let(Fmt::title) ?: "") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { pad ->
        val rec = r ?: return@Scaffold
        val s = settings ?: return@Scaffold
        val transcriberReady = graph.providers.transcriber(s)?.isConfigured() == true
        val analyzerReady = graph.providers.analyzer(s)?.isConfigured() == true
        val busy = rec.status in setOf(Status.QUEUED, Status.TRANSCRIBING, Status.ANALYZING)

        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("${Fmt.time(rec.callTimeSec)} · ${Fmt.duration(rec.durationMs)} · ${rec.samsungLabel ?: Fmt.label(rec.labelKind)}")
            Text(rec.displayName, style = MaterialTheme.typography.bodySmall)
            if (!rec.fileMissing) AudioPlayer(rec.uri)

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!transcriberReady) {
                    Button(onClick = onSetup) { Text("Set up transcription") }
                } else {
                    Button(enabled = !busy && !rec.fileMissing, onClick = {
                        val steps = buildSet {
                            add(ProcessWorker.STEP_TRANSCRIBE)
                            if (s.autoAnalyze && analyzerReady) add(ProcessWorker.STEP_ANALYZE)
                        }
                        scope.launch { graph.db.recordings().setStatus(rec.id, Status.QUEUED) }
                        WorkScheduler.enqueueProcess(ctx, rec.id, steps, force = true)
                    }) { Text(if (rec.transcriptText == null) "Transcribe" else "Re-transcribe") }
                }
                if (rec.transcriptText != null) {
                    OutlinedButton(enabled = !busy && analyzerReady, onClick = {
                        scope.launch { graph.db.recordings().setStatus(rec.id, Status.QUEUED) }
                        WorkScheduler.enqueueProcess(ctx, rec.id, setOf(ProcessWorker.STEP_ANALYZE), force = true)
                    }) { Text(if (rec.verdict == null) "Analyze" else "Re-analyze") }
                }
                if (s.webhookUrl.isNotBlank()) {
                    OutlinedButton(enabled = !rec.fileMissing, onClick = {
                        WorkScheduler.enqueueProcess(ctx, rec.id, setOf(ProcessWorker.STEP_EXPORT), force = true)
                    }) { Text(if (rec.exportedAt == null) "Export" else "Export again") }
                }
            }

            if (busy) {
                Text(Fmt.status(rec.status) + (rec.error?.let { " ($it)" } ?: ""))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else if (rec.status == Status.FAILED) {
                Text("Failed: ${rec.error}", color = MaterialTheme.colorScheme.error)
            } else if (rec.status == Status.SKIPPED_BY_RULE) {
                Text("Skipped by your automatic-processing rules. You can still transcribe it manually.", style = MaterialTheme.typography.bodySmall)
            }

            if (rec.verdict != null) AnalysisCard(rec)
            if (rec.transcriptText != null) TranscriptCard(rec)
            CostCard(rec)
            CallerRules(graph, rec)
        }
    }
}

@Composable
private fun AnalysisCard(r: RecordingEntity) {
    Card(colors = CardDefaults.cardColors(containerColor = verdictColor(r.verdict) ?: MaterialTheme.colorScheme.surfaceVariant)) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val pct = ((r.spamConfidence ?: 0.0) * 100).toInt()
                val v = when (r.verdict) { "spam" -> "ספאם"; "not_spam" -> "לא ספאם"; else -> "לא ברור" }
                Text("$v · $pct% · ${r.category}", fontWeight = FontWeight.Bold, color = androidx.compose.ui.graphics.Color.Black)
                r.callerIntent?.let { Text(it, color = androidx.compose.ui.graphics.Color.Black) }
                r.summary?.let { Text(it, color = androidx.compose.ui.graphics.Color.Black) }
            }
        }
    }
}

private val segJson = Json { ignoreUnknownKeys = true }

@Composable
private fun TranscriptCard(r: RecordingEntity) {
    val segments = remember(r.transcriptJson) {
        r.transcriptJson?.let { runCatching { segJson.decodeFromString(ListSerializer(Segment.serializer()), it) }.getOrNull() }.orEmpty()
    }
    Card {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            SelectionContainer {
                Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("תמלול", fontWeight = FontWeight.Bold)
                    if (segments.isEmpty()) Text(r.transcriptText.orEmpty())
                    segments.forEach { seg ->
                        val t = seg.start.toInt()
                        val who = seg.speaker?.let { "$it: " } ?: ""
                        Text("[${t / 60}:${"%02d".format(t % 60)}] $who${seg.text.trim()}")
                    }
                }
            }
        }
    }
}

@Composable
private fun CostCard(r: RecordingEntity) {
    if (r.transcriptionCostUsd == null && r.analysisCostUsd == null) return
    Column {
        Text("Cost", fontWeight = FontWeight.Bold)
        r.transcriptionCostUsd?.let {
            Text("Transcription: ${Fmt.usd(it)} (≈ ${"%.1f".format((r.transcriptionGpuMs ?: 0) / 1000.0)} s GPU, estimate)", style = MaterialTheme.typography.bodySmall)
        }
        r.analysisCostUsd?.let {
            Text("Analysis: ${Fmt.usd(it)} (${r.analysisInputTokens} in / ${r.analysisOutputTokens} out tokens)", style = MaterialTheme.typography.bodySmall)
        }
        Text("Total: ${Fmt.usd(r.totalCostUsd)}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CallerRules(graph: AppGraph, r: RecordingEntity) {
    val cp = r.counterparty ?: return
    val scope = rememberCoroutineScope()
    var done by remember { mutableStateOf<String?>(null) }
    Column {
        Text("Automatic processing for this caller", fontWeight = FontWeight.Bold)
        Row {
            TextButton(onClick = {
                scope.launch { addRule(graph, cp, RuleMode.INCLUDE); done = "Added to Always process" }
            }) { Text("Always process") }
            TextButton(onClick = {
                scope.launch { addRule(graph, cp, RuleMode.EXCLUDE); done = "Added to Never process" }
            }) { Text("Never process") }
        }
        done?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

private suspend fun addRule(graph: AppGraph, counterparty: String, mode: RuleMode) {
    graph.db.contactRules().insert(
        ContactRuleEntity(
            list = RuleList.AUTO_PROCESS, mode = mode, displayName = counterparty,
            numbers = PhoneNumbers.normalize(counterparty).orEmpty(),
        )
    )
}

@Composable
private fun AudioPlayer(uri: String) {
    val ctx = LocalContext.current
    val player = remember(uri) { runCatching { MediaPlayer.create(ctx, Uri.parse(uri)) }.getOrNull() }
    var playing by remember { mutableStateOf(false) }
    var pos by remember { mutableFloatStateOf(0f) }
    DisposableEffect(player) { onDispose { player?.release() } }
    if (player == null) return
    LaunchedEffect(playing) {
        while (playing) {
            pos = player.currentPosition.toFloat() / player.duration.coerceAtLeast(1)
            if (!player.isPlaying) playing = false
            delay(250)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = {
            if (player.isPlaying) { player.pause(); playing = false } else { player.start(); playing = true }
        }) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play") }
        Slider(value = pos, onValueChange = { pos = it; player.seekTo((it * player.duration).toInt()) }, modifier = Modifier.weight(1f))
    }
}
