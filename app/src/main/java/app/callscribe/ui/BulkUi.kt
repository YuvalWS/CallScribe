package app.callscribe.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.callscribe.AppGraph
import app.callscribe.data.AppSettings
import app.callscribe.data.RecordingEntity
import app.callscribe.pipeline.BulkEstimate
import app.callscribe.pipeline.BulkEstimator
import app.callscribe.pipeline.BulkPlan
import app.callscribe.pipeline.BulkProgress
import kotlinx.coroutines.launch

/** Human duration for estimates: "about 3 min", "about 2 h 10 min". */
internal fun aboutMinutes(min: Double): String {
    val m = Math.round(min).toInt().coerceAtLeast(1)
    return if (m < 60) "about $m min" else "about ${m / 60} h" + (if (m % 60 >= 5) " ${m % 60} min" else "")
}

internal fun estimateFor(graph: AppGraph, s: AppSettings, rows: List<RecordingEntity>, all: List<RecordingEntity>, summarize: Boolean): Pair<BulkPlan, BulkEstimate> {
    val analyzer = graph.providers.analyzer(s)?.takeIf { it.isConfigured() }
    val plan = BulkPlan.of(rows, summarize && analyzer != null)
    return plan to BulkEstimator.estimate(plan, all, s.transcriberId, analyzer?.id)
}

/** The numbers of an estimate, as a few lines. Shared by the confirm dialog and onboarding. */
@Composable
internal fun EstimateLines(plan: BulkPlan, e: BulkEstimate) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "${e.calls} call${if (e.calls == 1) "" else "s"} · %.0f min of audio".format(e.audioMinutes),
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            (if (e.isFree) "Cost: free" else "Cost: about ${Fmt.usd(e.usd)}") + " · Time: ${aboutMinutes(e.minutes)}",
            fontWeight = FontWeight.SemiBold,
        )
        if (plan.analyzeOnly.isNotEmpty()) Text("${plan.analyzeOnly.size} already transcribed — only the summary is added.", style = MaterialTheme.typography.bodySmall)
        if (plan.skipped > 0) Text("${plan.skipped} skipped (already done, in progress or audio deleted).", style = MaterialTheme.typography.bodySmall)
        e.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (!e.isFree) Text("Stops by itself if it costs more than ${Fmt.usd(e.capUsd)}.", style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Confirms a bulk run for [rows]: transcribe only or transcribe + summarize, with the estimate for each.
 * [onStarted] runs after the calls are queued.
 */
@Composable
fun BulkConfirmDialog(graph: AppGraph, rows: List<RecordingEntity>, onDismiss: () -> Unit, onStarted: () -> Unit) {
    val scope = rememberCoroutineScope()
    val s by graph.settings.flow.collectAsStateWithLifecycle(initialValue = null)
    val all by graph.db.recordings().observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val st = s ?: return
    val hasAnalyzer = graph.providers.analyzer(st)?.isConfigured() == true
    var summarize by remember { mutableStateOf(hasAnalyzer) }
    val (plan, est) = remember(rows, all, st, summarize) { estimateFor(graph, st, rows, all, summarize) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Process ${rows.size} call${if (rows.size == 1) "" else "s"}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hasAnalyzer) {
                    ChoiceRow("Transcribe + summarize", summarize) { summarize = true }
                    ChoiceRow("Transcribe only", !summarize) { summarize = false }
                }
                if (plan.isEmpty) Text("Nothing to do: these calls are already processed, in progress, or their audio was deleted.")
                else EstimateLines(plan, est)
                Text("Runs in the background. You can cancel from the notification or the call list.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = !plan.isEmpty, onClick = {
                scope.launch { graph.bulk.start(plan, est.capUsd); onStarted() }
            }) { Text(if (est.isFree) "Start" else "Start · ~${Fmt.usd(est.usd)}") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick)
        Text(label)
    }
}

/** Progress of the current bulk run on the call list, with Cancel / dismiss. */
@Composable
fun BulkBanner(graph: AppGraph, rows: List<RecordingEntity>) {
    val run by graph.bulk.run.collectAsStateWithLifecycle()
    val r = run ?: return
    val p = remember(r, rows) { BulkProgress.of(r, rows) }
    val active = p.running && r.stoppedReason == null
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    when {
                        active -> "Processing ${p.done} of ${p.total} calls"
                        r.stoppedReason != null -> r.stoppedReason
                        else -> "Processed ${p.done - p.failed} of ${p.total} calls"
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                if (active) LinearProgressIndicator(progress = { p.done.toFloat() / p.total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                Text("Spent ${Fmt.usd(p.spentUsd)}" + (if (p.failed > 0) " · ${p.failed} failed" else ""), style = MaterialTheme.typography.bodySmall)
            }
            if (active) TextButton(onClick = { graph.bulk.cancel() }) { Text("Cancel") }
            else IconButton(onClick = { graph.bulk.dismiss() }) { Icon(Icons.Default.Close, "Hide") }
        }
    }
}
