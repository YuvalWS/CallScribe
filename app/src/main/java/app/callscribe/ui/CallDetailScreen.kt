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
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import app.callscribe.data.PolicyEntity
import app.callscribe.data.RecordingEntity
import app.callscribe.data.Relationship
import app.callscribe.data.Status
import app.callscribe.detect.WorkScheduler
import app.callscribe.pipeline.ProcessWorker
import app.callscribe.providers.AnalysisPrompt
import app.callscribe.providers.Segment
import app.callscribe.rules.PhoneNumbers
import app.callscribe.rules.PolicyResolver
import app.callscribe.rules.keptForever
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallDetailScreen(graph: AppGraph, id: Long, onBack: () -> Unit, onSetup: () -> Unit, onPolicies: () -> Unit) {
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
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                IconButton(
                    onClick = { scope.launch { graph.db.recordings().setKeepForever(rec.id, !rec.keepForever) } },
                    modifier = androidx.compose.ui.Modifier.semantics { contentDescription = if (rec.keepForever) "Kept forever" else "Keep forever" },
                ) {
                    Icon(if (rec.keepForever) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, null,
                        tint = if (rec.keepForever) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    if (rec.keepForever) "Kept forever — retention policies never delete this call's audio or transcript."
                    else "Keep forever: protect this call from retention policies.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (!rec.fileMissing) AudioPlayer(rec.uri)

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!transcriberReady) {
                    Button(onClick = onSetup) { Text("Set up transcription") }
                } else {
                    Button(enabled = !busy && !rec.fileMissing, onClick = {
                        scope.launch { processNow(ctx, graph, rec) }
                    }) { Text(if (rec.transcriptText == null) "Transcribe" else "Re-transcribe") }
                }
                if (rec.transcriptText != null && !analyzerReady) {
                    OutlinedButton(onClick = onSetup) { Text("Set up AI summary") }
                } else if (rec.transcriptText != null) {
                    OutlinedButton(enabled = !busy, onClick = {
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
            TagPicker(graph, rec)
            if (rec.transcriptText != null) TranscriptCard(rec)
            CostCard(rec)
            CallerRules(graph, rec, onPolicies)
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
                val tag = r.relationship?.let { " · ${it.he}" }.orEmpty()
                Text("$v · $pct% · ${r.category}$tag", fontWeight = FontWeight.Bold, color = androidx.compose.ui.graphics.Color.Black)
                r.callerIntent?.let { Text(it, color = androidx.compose.ui.graphics.Color.Black) }
                r.summary?.let { Text(it, color = androidx.compose.ui.graphics.Color.Black) }
            }
        }
    }
}

/** Tag chips; picking one is a manual override that re-analysis keeps. "Use AI tag" restores the analysed tag. */
@Composable
private fun TagPicker(graph: AppGraph, r: RecordingEntity) {
    val scope = rememberCoroutineScope()
    val dao = graph.db.recordings()
    Column {
        val note = when {
            r.relationship == null -> "not tagged yet"
            r.relationshipManual -> "set by you"
            else -> "set by AI"
        }
        Text("Tag · $note", fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Relationship.entries.forEach { t ->
                FilterChip(
                    selected = r.relationship == t,
                    onClick = { scope.launch { dao.setRelationship(r.id, t.name, manual = true) } },
                    label = { Text(t.en) },
                )
            }
        }
        if (r.relationshipManual) TextButton(onClick = {
            val ai = AnalysisPrompt.relationshipFrom(r.analysisJson)
            scope.launch { dao.setRelationship(r.id, ai?.name, manual = false) }
        }) { Text(if (r.analysisJson != null) "Use AI tag" else "Clear (let AI tag it)") }
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
                    val speakers = segments.mapNotNull { it.speaker }.distinct()
                    var last: String? = null
                    segments.forEach { seg ->
                        val t = seg.start.toInt()
                        val time = "${t / 60}:${"%02d".format(t % 60)}"
                        if (seg.speaker != null && seg.speaker != last) {
                            Text("דובר ${speakers.indexOf(seg.speaker) + 1} · $time", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
                            last = seg.speaker
                        }
                        Text(seg.text.trim())
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
private fun CallerRules(graph: AppGraph, r: RecordingEntity, onPolicies: () -> Unit) {
    val scope = rememberCoroutineScope()
    val policies by graph.db.policies().observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val resolution = PolicyResolver.explain(r, policies)
    val effective = resolution.policy
    val own = policies.firstOrNull { PolicyResolver.matches(it, r.counterparty) }
    Column {
        Text("Policy for this call", fontWeight = FontWeight.Bold)
        Text(resolution.reason { Fmt.label(it) }, style = MaterialTheme.typography.bodySmall)
        val days = { d: Int -> if (d < 0) "forever" else "$d days" }
        val what = (if (effective.transcribe) "transcribe" else "don't transcribe") +
            (if (effective.transcribe && effective.summarize) " + summarize" else "")
        val text = if (effective.textRetentionDays < 0) "" else ", text ${days(effective.textRetentionDays)}"
        Text(
            "$what · recording on phone ${days(effective.audioRetentionDays)}$text · cloud ${cloudLabel(effective).lowercase()}",
            style = MaterialTheme.typography.bodySmall,
        )
        if (r.keptForever()) Text("Marked keep forever: never deleted, whatever the policy says.", style = MaterialTheme.typography.bodySmall)
        val cp = r.counterparty
        if (cp != null) Row {
            TextButton(onClick = { scope.launch { setCallerPolicy(graph, cp, own, effective, true) } }) { Text("Always process") }
            TextButton(onClick = { scope.launch { setCallerPolicy(graph, cp, own, effective, false) } }) { Text("Never process") }
        }
        TextButton(onClick = onPolicies) { Text("Edit policies…") }
    }
}

private suspend fun setCallerPolicy(graph: AppGraph, counterparty: String, own: PolicyEntity?, base: PolicyEntity, process: Boolean) {
    if (own != null) {
        graph.db.policies().update(own.copy(transcribe = process, summarize = process))
    } else {
        graph.db.policies().insert(
            base.copy(
                id = 0, groupKind = null, tag = null, displayName = counterparty,
                numbers = PhoneNumbers.normalize(counterparty).orEmpty(), transcribe = process, summarize = process,
            )
        )
    }
}

/** Manual "process": transcribe (if needed) and summarize when an AI provider is set up. Used by the list and detail screens. */
suspend fun processNow(ctx: android.content.Context, graph: AppGraph, r: RecordingEntity) {
    val s = graph.settings.get()
    val steps = buildSet {
        add(ProcessWorker.STEP_TRANSCRIBE)
        if (graph.providers.analyzer(s)?.isConfigured() == true) add(ProcessWorker.STEP_ANALYZE)
    }
    graph.db.recordings().setStatus(r.id, Status.QUEUED)
    WorkScheduler.enqueueProcess(ctx, r.id, steps, force = true)
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
