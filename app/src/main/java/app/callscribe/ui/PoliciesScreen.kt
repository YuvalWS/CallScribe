package app.callscribe.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.callscribe.AppGraph
import app.callscribe.data.LabelKind
import app.callscribe.data.PolicyEntity
import app.callscribe.data.PolicyTarget
import app.callscribe.data.RelationshipTags
import app.callscribe.pipeline.RetentionWorker
import app.callscribe.rules.PhoneNumbers
import app.callscribe.rules.PolicyChecks
import app.callscribe.rules.RetentionPlan
import kotlinx.coroutines.launch

private val RETENTION_CHOICES = listOf(-1, 1, 7, 30, 90, 180, 365)
private fun retentionLabel(d: Int) = when (d) { -1 -> "Forever"; 1 -> "1 day"; 365 -> "1 year"; else -> "$d days" }

/** Cloud choice label; "same as phone" shows what it resolves to, e.g. "Same as phone (forever)". */
internal fun cloudLabel(p: PolicyEntity) =
    if (p.cloudRetentionDays == PolicyEntity.SAME_AS_LOCAL) "Same as phone (${retentionLabel(p.audioRetentionDays).lowercase()})"
    else retentionLabel(p.cloudRetentionDays)

/** Tag policies need the AI relationship tag on recordings (another branch). Flip on once it lands. */
private const val TAG_POLICIES_ENABLED = false

private fun groupTitle(k: LabelKind) = when (k) {
    LabelKind.SPAM -> "Labelled spam by Samsung"
    LabelKind.FRAUD -> "Labelled fraud by Samsung"
    LabelKind.OTHER_LABEL -> "Other Samsung labels"
    LabelKind.UNKNOWN_NUMBER -> "Unknown numbers"
    LabelKind.PRIVATE -> "Private / hidden numbers"
    LabelKind.CONTACT -> "Contacts (default for all contacts)"
}

@Composable
fun PoliciesScreen(graph: AppGraph, onBack: () -> Unit, onRetention: () -> Unit) {
    val s by graph.settings.flow.collectAsStateWithLifecycle(initialValue = null)
    val policies by graph.db.policies().observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { graph.db.policies().ensureGroupDefaults() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val uri = res.data?.data
        if (res.resultCode != Activity.RESULT_OK || uri == null) return@rememberLauncherForActivityResult
        // Picking a phone number grants one-off read access to that row: no READ_CONTACTS permission needed.
        ctx.contentResolver.query(
            uri, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null,
        )?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getString(0) ?: return@use
                val num = PhoneNumbers.normalize(c.getString(1) ?: "").orEmpty()
                val base = policies.firstOrNull { it.groupKind == LabelKind.CONTACT } ?: PolicyEntity.defaultFor(LabelKind.CONTACT)
                scope.launch {
                    graph.db.policies().insert(base.copy(id = 0, groupKind = null, displayName = name, numbers = num, transcribe = true, summarize = true))
                }
            }
        }
    }

    Page("Policies", onBack) {
        val st = s ?: return@Page
        SwitchRow("Process new calls automatically", st.autoEnabled) { v -> scope.launch { graph.settings.update { it.copy(autoEnabled = v) } } }
        Text(
            "Choose per caller type or contact whether new calls are transcribed and summarized automatically, and how " +
                "long to keep them. Each call follows exactly one policy, the most specific that matches: contact, then " +
                "relationship tag, then caller type. Calls marked \"keep forever\" are never deleted. " +
                "Any call can still be processed manually.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = onRetention) { Text("Review retention now") }
        HorizontalDivider()

        Section("Caller types")
        LabelKind.entries.forEach { k ->
            policies.firstOrNull { it.groupKind == k }?.let { p ->
                PolicyCard(groupTitle(k), p, onChange = { scope.launch { graph.db.policies().update(it) } })
            }
        }
        HorizontalDivider()

        if (TAG_POLICIES_ENABLED) {
            Section("Relationship tags (set by AI)")
            Text("Retention only: tags are known after a call is analyzed. A tag policy beats the caller type.", style = MaterialTheme.typography.bodySmall)
            RelationshipTags.ALL.forEach { t ->
                val p = policies.firstOrNull { it.tag == t }
                if (p != null) PolicyCard(
                    RelationshipTags.label(t), p,
                    onChange = { scope.launch { graph.db.policies().update(it) } },
                    onDelete = { scope.launch { graph.db.policies().delete(p.id) } },
                ) else TextButton(onClick = {
                    scope.launch { graph.db.policies().insert(PolicyEntity(tag = t, transcribe = true, summarize = true)) }
                }) { Text("+ Policy for \"${RelationshipTags.label(t)}\"") }
            }
            HorizontalDivider()
        }

        Section("Specific contacts")
        val contacts = policies.filter { !it.isGroup }
        if (contacts.isEmpty()) Text("None yet. Add a contact, or use the buttons on a call's page.", style = MaterialTheme.typography.bodySmall)
        contacts.forEach { p ->
            PolicyCard(
                p.displayName.ifBlank { "…" + p.numbers }, p,
                subtitle = p.numbers.takeIf { it.isNotBlank() }?.let { "…$it" },
                onChange = { scope.launch { graph.db.policies().update(it) } },
                onDelete = { scope.launch { graph.db.policies().delete(p.id) } },
            )
        }
        TextButton(onClick = { picker.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)) }) {
            Text("+ Add contact")
        }
    }
}

@Composable
private fun PolicyCard(
    title: String, p: PolicyEntity, subtitle: String? = null,
    onChange: (PolicyEntity) -> Unit, onDelete: (() -> Unit)? = null,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.Bold)
                    subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                if (onDelete != null) IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Remove") }
            }
            if (p.target != PolicyTarget.TAG) {
                CheckRow("Transcribe automatically", p.transcribe) { onChange(p.copy(transcribe = it, summarize = p.summarize && it)) }
                CheckRow("Summarize & judge spam", p.summarize, enabled = p.transcribe) { onChange(p.copy(summarize = it)) }
            }
            DaysRow("Keep recording on phone", p.audioRetentionDays) { onChange(p.copy(audioRetentionDays = it)) }
            CheckRow("Also delete transcript & summary", p.textRetentionDays >= 0) {
                onChange(p.copy(textRetentionDays = if (it) maxOf(p.audioRetentionDays, 30) else -1))
            }
            if (p.textRetentionDays >= 0) DaysRow("Keep text", p.textRetentionDays, allowForever = false) { onChange(p.copy(textRetentionDays = it)) }
            CloudRow(p) { onChange(p.copy(cloudRetentionDays = it)) }
            PolicyChecks.warnings(p).forEach {
                Text("\u26A0 $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

@Composable
private fun DaysRow(label: String, days: Int, allowForever: Boolean = true, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Column {
            TextButton(onClick = { open = true }) { Text(retentionLabel(days)) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                RETENTION_CHOICES.filter { allowForever || it >= 0 }.forEach { d ->
                    DropdownMenuItem(text = { Text(retentionLabel(d)) }, onClick = { open = false; onPick(d) })
                }
            }
        }
    }
}

@Composable
private fun CloudRow(p: PolicyEntity, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Keep in cloud backup")
            Text("Applies when Drive backup is on", style = MaterialTheme.typography.bodySmall)
        }
        Column {
            TextButton(onClick = { open = true }) { Text(cloudLabel(p)) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text("Same as phone") }, onClick = { open = false; onPick(PolicyEntity.SAME_AS_LOCAL) })
                RETENTION_CHOICES.forEach { d ->
                    DropdownMenuItem(text = { Text(retentionLabel(d)) }, onClick = { open = false; onPick(d) })
                }
            }
        }
    }
}

/** Shows what retention will delete and moves due recordings to the system trash after confirmation. */
@Composable
fun RetentionScreen(graph: AppGraph, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var plan by remember { mutableStateOf<RetentionPlan?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    suspend fun refresh() { plan = RetentionWorker.currentPlan(graph) }
    LaunchedEffect(Unit) { refresh() }

    val trashLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val p = plan ?: return@rememberLauncherForActivityResult
        scope.launch {
            if (res.resultCode == Activity.RESULT_OK) {
                val now = System.currentTimeMillis()
                p.trashAudio.forEach { r -> graph.db.recordings().update(r.copy(audioDeletedAt = now, fileMissing = true, updatedAt = now)) }
                message = "Moved ${p.trashAudio.size} recordings to the trash (recoverable for 30 days)."
            } else message = "Cancelled — nothing was deleted."
            refresh()
        }
    }

    Page("Retention review", onBack) {
        val p = plan ?: return@Page
        Text(
            "Recordings past the retention period set in Policies are moved to the system trash (recoverable for 30 days). " +
                "Android asks you to confirm each batch, unless you give CallScribe \"Media management\" access.",
            style = MaterialTheme.typography.bodySmall,
        )
        Section("Recordings due: ${p.trashAudio.size} (%.1f MB)".format(p.audioBytes / 1e6))
        p.trashAudio.take(50).forEach { r -> Text("• ${Fmt.title(r)} · ${Fmt.time(r.callTimeSec)}", style = MaterialTheme.typography.bodySmall) }
        if (p.trashAudio.size > 50) Text("… and ${p.trashAudio.size - 50} more", style = MaterialTheme.typography.bodySmall)
        Button(enabled = p.trashAudio.isNotEmpty(), onClick = {
            val uris = p.trashAudio.map { Uri.parse(it.uri) }
            val pi = MediaStore.createTrashRequest(ctx.contentResolver, uris, true)
            trashLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        }) { Text("Move ${p.trashAudio.size} to trash") }
        HorizontalDivider()

        Section("Transcripts due for deletion: ${p.deleteText.size}")
        Text("Only for policies where \"Also delete transcript & summary\" is on. This also runs automatically once a day.",
            style = MaterialTheme.typography.bodySmall)
        Button(enabled = p.deleteText.isNotEmpty(), onClick = {
            scope.launch { val n = RetentionWorker.deleteText(graph, p); message = "Deleted text of $n calls."; refresh() }
        }) { Text("Delete text of ${p.deleteText.size} calls") }
        HorizontalDivider()
        OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.parse("package:${ctx.packageName}"))) }) {
            Text("Allow media management (skip confirmations)")
        }
        message?.let { Text(it) }
    }
}
