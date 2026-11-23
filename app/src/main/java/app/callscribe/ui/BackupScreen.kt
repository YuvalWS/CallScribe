package app.callscribe.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.callscribe.backup.BackupInfo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.callscribe.AppGraph
import app.callscribe.backup.DriveBackupSettings
import com.google.android.gms.auth.api.identity.AuthorizationResult
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun BackupScreen(graph: AppGraph, onBack: () -> Unit, onPolicies: () -> Unit) {
    val drive = graph.drive
    val s by drive.settings.flow.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var confirmRestore by remember { mutableStateOf(false) }
    Page("Google Drive backup", onBack) {
        val st = s ?: return@Page
        Text(
            "Keeps your transcripts, summaries, policies and settings — and, if you choose, the recordings — in a " +
                "\"CallScribe Backup\" folder in your own Google Drive, with a separate subfolder for each phone. " +
                "API keys never leave this phone. " +
                "CallScribe can only see the files it created there.",
            style = MaterialTheme.typography.bodySmall,
        )
        DriveBackupToggles(graph, st)
        if (!st.enabled) return@Page

        Text(statusLine(st), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { drive.backupNow() }) { Text("Back up now") }
            OutlinedButton(onClick = { confirmRestore = true }) { Text("Restore…") }
        }
        HorizontalDivider()

        Section("When")
        SwitchRow("Only on Wi-Fi", st.wifiOnly) { v -> scope.launch { drive.settings.update { it.copy(wifiOnly = v) }; drive.reschedule() } }
        SwitchRow("Only while charging", st.chargingOnly) { v -> scope.launch { drive.settings.update { it.copy(chargingOnly = v) }; drive.reschedule() } }
        Text("Runs once a day. Only new or changed calls and new recordings are uploaded.", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()

        Section("How long backups are kept")
        Text(
            "Each policy decides how long its recordings stay in the cloud backup (forever, N days, or same as on the phone). " +
                "Expired recordings go to the Drive trash. Calls marked \"keep forever\" are always kept.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = onPolicies) { Text("Open policies") }
        HorizontalDivider()

        TextButton(onClick = { scope.launch { drive.disconnect() } }) { Text("Disconnect Google Drive") }
        Text(
            "Disconnecting stops backups; files already in Drive stay there. To revoke access entirely: " +
                "myaccount.google.com → Security → Third-party connections.",
            style = MaterialTheme.typography.bodySmall,
        )
    }

    if (confirmRestore) {
        var withAudio by remember { mutableStateOf(false) }
        var backups by remember { mutableStateOf<List<BackupInfo>?>(null) }
        var loadError by remember { mutableStateOf<String?>(null) }
        var chosen by remember { mutableStateOf<BackupInfo?>(null) }
        LaunchedEffect(Unit) {
            runCatching { drive.backups() }
                .onSuccess { list ->
                    backups = list
                    // Default: the most recent backup of another phone (restoring this phone's own backup is rarely wanted).
                    chosen = list.firstOrNull { it.deviceId != s?.deviceId } ?: list.firstOrNull()
                }
                .onFailure { loadError = it.message ?: "Couldn't read your Drive" }
        }
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text("Restore from Drive") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val list = backups
                    when {
                        loadError != null -> Text(loadError!!, color = MaterialTheme.colorScheme.error)
                        list == null -> Text("Looking for backups…")
                        list.isEmpty() -> Text("No CallScribe backups in this Google account yet.")
                        else -> {
                            Text("Choose which phone's backup to restore:")
                            list.forEach { b ->
                                Row(
                                    Modifier.fillMaxWidth().clickable { chosen = b },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(selected = chosen == b, onClick = { chosen = b })
                                    Column {
                                        Text(b.name + if (b.deviceId == s?.deviceId) " (this phone)" else "")
                                        Text(
                                            listOfNotNull(
                                                b.lastBackupSec?.let { "backed up " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it * 1000)) },
                                                b.calls?.let { "$it calls" },
                                                b.recordings?.takeIf { it > 0 }?.let { "$it recordings" },
                                            ).joinToString(" · "),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            }
                            Text(
                                "Calls, transcripts and summaries are matched to recordings on this phone; results already here are kept. " +
                                    "Policies and settings are replaced by the backed-up ones (API keys must be entered again). " +
                                    "This phone keeps backing up into its own folder.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            CheckRow("Also download recordings that aren't on this phone", withAudio) { withAudio = it }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = chosen != null,
                    onClick = { confirmRestore = false; chosen?.let { drive.restore(it, withAudio) } },
                ) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text("Cancel") } },
        )
    }
}

private fun statusLine(st: DriveBackupSettings): String = when {
    st.needsSignIn -> "⚠ Paused: Google needs you to sign in again. Turn backup off and on to reconnect."
    st.lastSuccessMs > 0 -> "Last backup: ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(st.lastSuccessMs))}" +
        (st.lastResult.takeIf { it.isNotBlank() }?.let { "\n$it" } ?: "")
    st.lastResult.isNotBlank() -> st.lastResult
    else -> "No backup yet — the first one runs soon (or tap Back up now)."
}

/**
 * The main switches: "Back up to Google Drive" (connects the Google account on first use) and "Include recordings".
 * Shared with onboarding (docs/plans/drive-backup.md → Onboarding).
 */
@Composable
fun DriveBackupToggles(graph: AppGraph, st: DriveBackupSettings) {
    val drive = graph.drive
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun connected(r: AuthorizationResult) {
        drive.auth.remember(r)
        drive.settings.update { it.copy(enabled = true, needsSignIn = false) }
        drive.reschedule()
        drive.backupNow()
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        scope.launch {
            if (res.resultCode != Activity.RESULT_OK) { error = "Google sign-in was cancelled"; return@launch }
            runCatching { drive.auth.resultFromIntent(res.data) }
                .onSuccess { connected(it) }
                .onFailure { error = it.message }
        }
    }

    SwitchRow("Back up to Google Drive", st.enabled && !st.needsSignIn) { on ->
        error = null
        scope.launch {
            if (!on) {
                drive.settings.update { it.copy(enabled = false) }; drive.reschedule(); return@launch
            }
            runCatching { drive.auth.authorize() }
                .onSuccess { r ->
                    val pi = r.pendingIntent
                    if (r.hasResolution() && pi != null) launcher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                    else connected(r)
                }
                .onFailure { error = it.message }
        }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    SwitchRow("Include recordings (audio)", st.includeAudio) { v ->
        scope.launch { drive.settings.update { it.copy(includeAudio = v) } }
    }
    Text(
        "Recordings take about 1 MB per minute of Drive storage. A policy with cloud retention of 0 days keeps that group's audio off Drive.",
        style = MaterialTheme.typography.bodySmall,
    )
}
