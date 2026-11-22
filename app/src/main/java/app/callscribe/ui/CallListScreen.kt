package app.callscribe.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.callscribe.AppGraph
import app.callscribe.data.LabelKind
import app.callscribe.data.RecordingEntity
import app.callscribe.data.Status
import app.callscribe.detect.WorkScheduler

private enum class Filter(val title: String, val test: (RecordingEntity) -> Boolean) {
    ALL("All", { true }),
    SPAM("Spam / fraud", { it.labelKind == LabelKind.SPAM || it.labelKind == LabelKind.FRAUD || it.verdict == "spam" }),
    UNKNOWN("Unknown", { it.labelKind == LabelKind.UNKNOWN_NUMBER || it.labelKind == LabelKind.PRIVATE }),
    CONTACTS("Contacts", { it.labelKind == LabelKind.CONTACT }),
    TODO("Not transcribed", { it.transcriptText == null }),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallListScreen(graph: AppGraph, onOpen: (Long) -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val rows by graph.db.recordings().observeAll().collectAsStateWithLifecycle(initialValue = null)
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("CallScribe") },
            actions = {
                IconButton(onClick = { WorkScheduler.scanNow(ctx) }) { Icon(Icons.Default.Refresh, "Rescan") }
                IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") }
            },
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            val all = rows.orEmpty()
            val total = remember(all) { all.sumOf { it.totalCostUsd } }
            val processed = remember(all) { all.count { it.transcriptText != null } }
            Text(
                "${all.size} recordings · $processed transcribed · spent ${Fmt.usd(total)}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Filter.entries.forEach { f ->
                    FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.title) })
                }
            }
            if (rows == null) return@Column
            val shown = all.filter(filter.test)
            if (shown.isEmpty()) {
                Text(
                    if (all.isEmpty()) "No call recordings found yet. Turn on call recording in the Phone app, then tap Rescan."
                    else "Nothing here.",
                    modifier = Modifier.padding(24.dp),
                )
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { it.id }) { r ->
                    CallRow(r) { onOpen(r.id) }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun CallRow(r: RecordingEntity, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                Fmt.title(r), style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Text(Fmt.time(r.callTimeSec), style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(Fmt.duration(r.durationMs), style = MaterialTheme.typography.bodySmall)
            if (r.labelKind != LabelKind.CONTACT) SmallChip(r.samsungLabel ?: Fmt.label(r.labelKind), labelColor(r.labelKind))
            when {
                r.verdict != null -> SmallChip(
                    when (r.verdict) { "spam" -> "Spam ${((r.spamConfidence ?: 0.0) * 100).toInt()}%"; "not_spam" -> "Not spam"; else -> "Unsure" },
                    verdictColor(r.verdict),
                )
                else -> SmallChip(Fmt.status(r.status))
            }
            if (r.totalCostUsd > 0) Text(Fmt.usd(r.totalCostUsd), style = MaterialTheme.typography.bodySmall)
            if (r.fileMissing) Text("audio missing", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        val line = r.callerIntent ?: r.summary ?: r.transcriptText ?: (if (r.status == Status.FAILED) r.error else null)
        if (line != null) {
            Text(line, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
