package app.callscribe.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.callscribe.AppGraph
import app.callscribe.data.PolicyEntity
import app.callscribe.data.RecordingEntity
import app.callscribe.rules.Retention
import app.callscribe.rules.RetentionImpact
import kotlinx.coroutines.launch

/**
 * Checks what a change would delete right away, before it is saved. Nothing about existing calls changes
 * silently: if the change makes calls due, the UI asks first (see [RetentionWarningDialog]).
 */
object RetentionGuard {
    suspend fun impactOfPolicies(graph: AppGraph, after: List<PolicyEntity>): RetentionImpact {
        val rows = graph.db.recordings().all()
        val before = graph.db.policies().all()
        val groups = graph.contactGroups.index()
        val now = System.currentTimeMillis() / 1000
        return RetentionImpact.of(Retention.plan(rows, before, now, groups = groups), Retention.plan(rows, after, now, groups = groups))
    }

    /** What removing "keep forever" from [r] would delete right away. */
    suspend fun impactOfUnkeep(graph: AppGraph, r: RecordingEntity): RetentionImpact {
        val policies = graph.db.policies().all()
        val groups = graph.contactGroups.index()
        val now = System.currentTimeMillis() / 1000
        return RetentionImpact.of(
            Retention.plan(listOf(r.copy(keepForever = true)), policies, now, groups = groups),
            Retention.plan(listOf(r.copy(keepForever = false)), policies, now, groups = groups),
        )
    }
}

/** Toggles "keep forever"; removing it from a call that is already past its retention asks first. */
internal fun toggleKeep(
    scope: kotlinx.coroutines.CoroutineScope, graph: AppGraph, r: RecordingEntity, ask: (PendingRetentionChange) -> Unit,
) = scope.launch {
    val dao = graph.db.recordings()
    if (r.keepForever) {
        val impact = RetentionGuard.impactOfUnkeep(graph, r)
        if (!impact.isEmpty) {
            ask(PendingRetentionChange("Stop keeping this call forever?", impact, apply = { scope.launch { dao.setKeepForever(r.id, false) } }))
            return@launch
        }
    }
    dao.setKeepForever(r.id, !r.keepForever)
}

/** A change waiting for the user because it would delete existing calls right away. */
class PendingRetentionChange(
    val title: String,
    val impact: RetentionImpact,
    val apply: () -> Unit,
    /** "Only calls from now on" — null when it doesn't apply (removing a policy, reordering, keep-forever). */
    val limit: (() -> Unit)? = null,
)

@Composable
fun RetentionWarningDialog(change: PendingRetentionChange, onDismiss: () -> Unit) {
    val i = change.impact
    var showCalls by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(change.title) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "This affects ${i.calls.size} existing call${if (i.calls.size == 1) "" else "s"} right away:",
                    fontWeight = FontWeight.SemiBold,
                )
                if (i.audio.isNotEmpty()) Text(
                    "• ${i.audio.size} recording${if (i.audio.size == 1) "" else "s"} (%.1f MB) become due for the trash — you'll confirm before they move".format(i.audioBytes / 1e6),
                )
                if (i.text.isNotEmpty()) Text("• ${i.text.size} transcript${if (i.text.size == 1) "" else "s"} and summaries will be deleted (daily, automatically)")
                if (i.cloud.isNotEmpty()) Text("• ${i.cloud.size} Google Drive cop${if (i.cloud.size == 1) "y" else "ies"} will be deleted at the next backup")
                if (change.limit != null) Text(
                    "Choose \"Only calls from now on\" to keep existing calls as they are; the new rule then applies to calls recorded from today.",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { showCalls = !showCalls }) { Text(if (showCalls) "Hide the calls" else "See the calls") }
                if (showCalls) {
                    i.calls.take(100).forEach { r -> Text("${Fmt.title(r)} · ${Fmt.time(r.callTimeSec)}", style = MaterialTheme.typography.bodySmall) }
                    if (i.calls.size > 100) Text("… and ${i.calls.size - 100} more", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                TextButton(onClick = { change.apply(); onDismiss() }) {
                    Text(if (change.limit != null) "Apply to these too" else "Apply")
                }
                change.limit?.let { limit -> TextButton(onClick = { limit(); onDismiss() }) { Text("Only calls from now on") } }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
