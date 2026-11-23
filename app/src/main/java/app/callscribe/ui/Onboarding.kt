package app.callscribe.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.telecom.TelecomManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.callscribe.AppGraph
import app.callscribe.detect.WorkScheduler
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private fun granted(ctx: Context, perm: String) = ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

/**
 * Live [SetupStatus], refreshed when settings or recordings change, when the app resumes (the user may come
 * back from system settings) and when [refreshKey] changes. Null until settings have loaded.
 */
@Composable
fun rememberSetupStatus(graph: AppGraph, refreshKey: Any? = null): SetupStatus? {
    val ctx = LocalContext.current
    val settings by graph.settings.flow.collectAsStateWithLifecycle(initialValue = null)
    val count by remember { graph.db.recordings().observeAll().map { it.size } }.collectAsStateWithLifecycle(initialValue = 0)
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose { }
    }
    return remember(settings, count, resumes, refreshKey) {
        val s = settings ?: return@remember null
        SetupStatus(
            audioGranted = granted(ctx, Manifest.permission.READ_MEDIA_AUDIO),
            notificationsGranted = granted(ctx, Manifest.permission.POST_NOTIFICATIONS),
            recordingCount = count,
            batteryUnrestricted = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName),
            transcriberConfigured = graph.providers.transcriber(s)?.isConfigured() == true,
            analyzerConfigured = graph.providers.analyzer(s)?.isConfigured() == true,
        )
    }
}

private fun Context.open(intent: Intent) {
    try {
        startActivity(intent)
    } catch (_: Exception) {
        Toast.makeText(this, "This screen isn't available on your phone", Toast.LENGTH_SHORT).show()
    }
}

private fun Context.appDetails() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))

/**
 * First-run setup (and "Run setup again"). [onExit] is called when backing out of the first step (null = can't);
 * [onFinish] after the last step, once `onboardingDone` is saved.
 */
@Composable
fun OnboardingScreen(graph: AppGraph, start: SetupStep, onExit: (() -> Unit)?, onFinish: () -> Unit) {
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableStateOf(start) }
    val settings by graph.settings.flow.collectAsStateWithLifecycle(initialValue = null)
    val status = rememberSetupStatus(graph, refreshKey = step)
    val steps = SetupStep.entries
    val idx = step.ordinal

    fun next() {
        if (idx < steps.lastIndex) { step = steps[idx + 1]; return }
        scope.launch {
            graph.settings.update { it.copy(onboardingDone = true, setupBannerDismissed = false) }
            onFinish()
        }
    }
    fun back() {
        if (idx > 0) step = steps[idx - 1] else onExit?.invoke()
    }

    BackHandler(enabled = idx > 0 || onExit != null) { back() }

    Scaffold(
        topBar = {
            Column(Modifier.statusBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp)) {
                LinearProgressIndicator(progress = { (idx + 1f) / steps.size }, modifier = Modifier.fillMaxWidth())
                Text(
                    "Step ${idx + 1} of ${steps.size}",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
        bottomBar = {
            val st = status
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (idx > 0 || onExit != null) TextButton(onClick = { back() }) { Text("Back") }
                Spacer(Modifier.weight(1f))
                val done = st?.isDone(step) == true
                if (step.skippable && step != SetupStep.WELCOME && step != SetupStep.DONE && !done) {
                    TextButton(onClick = { next() }) { Text("Skip") }
                    Spacer(Modifier.width(8.dp))
                }
                Button(enabled = st?.canContinue(step) == true, onClick = { next() }) {
                    Text(
                        when (step) {
                            SetupStep.WELCOME -> "Get started"
                            SetupStep.DONE -> "Open my calls"
                            else -> "Next"
                        }
                    )
                }
            }
        },
    ) { pad ->
        val st = status
        val s = settings
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (st == null || s == null) return@Column
            Icon(step.icon(), null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            Text(step.heading(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            when (step) {
                SetupStep.WELCOME -> WelcomeStep()
                SetupStep.PERMISSIONS -> PermissionsStep(st)
                SetupStep.RECORDING -> RecordingStep(st)
                SetupStep.BACKGROUND -> BackgroundStep(st)
                SetupStep.TRANSCRIPTION -> {
                    Body("Transcripts are made by an ivrit.ai Hebrew model running on your own RunPod endpoint. You pay RunPod directly — about $0.003 for a 14-minute call. Setup takes ~5 minutes; see the README for step-by-step instructions.")
                    Body("You can skip this and set it up later in Settings → Providers. Until then CallScribe works as a recordings browser.")
                    StatusLine(st.transcriberConfigured, "Transcription is set up", "Not set up yet")
                    TranscriptionProviderFields(graph, s)
                }
                SetupStep.ANALYSIS -> {
                    Body("Optional: an AI model reads each transcript and writes a short summary plus a spam / not-spam verdict. Pick a provider and paste your own key. Test connection is free.")
                    StatusLine(st.analyzerConfigured, "AI summary is set up", "Not set up yet")
                    AnalyzerProviderFields(graph, s)
                }
                SetupStep.POLICY -> PolicyStep(graph, s.autoEnabled)
                SetupStep.DONE -> DoneStep(st)
            }
        }
    }
}

private fun SetupStep.icon(): ImageVector = when (this) {
    SetupStep.WELCOME -> Icons.Default.Lock
    SetupStep.PERMISSIONS -> Icons.Default.LibraryMusic
    SetupStep.RECORDING -> Icons.Default.Call
    SetupStep.BACKGROUND -> Icons.Default.BatteryFull
    SetupStep.TRANSCRIPTION -> Icons.Default.Subtitles
    SetupStep.ANALYSIS -> Icons.Default.AutoAwesome
    SetupStep.POLICY -> Icons.Default.Tune
    SetupStep.DONE -> Icons.Default.CheckCircle
}

private fun SetupStep.heading() = when (this) {
    SetupStep.WELCOME -> "Welcome to CallScribe"
    SetupStep.PERMISSIONS -> "Let CallScribe see your call recordings"
    SetupStep.RECORDING -> "Is call recording on?"
    SetupStep.BACKGROUND -> "Keep CallScribe awake"
    SetupStep.TRANSCRIPTION -> "Set up transcription"
    SetupStep.ANALYSIS -> "AI summary & spam check"
    SetupStep.POLICY -> "What should happen to new calls?"
    SetupStep.DONE -> "You're all set"
}

@Composable
private fun Body(text: String) = Text(text, style = MaterialTheme.typography.bodyLarge)

@Composable
private fun StatusLine(ok: Boolean, okText: String, notText: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            if (ok) Icons.Default.CheckCircle else Icons.Default.RemoveCircleOutline, null,
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        )
        Text(if (ok) okText else notText, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun WelcomeStep() {
    Body("CallScribe lists the calls your phone records, transcribes them in Hebrew and can tell you what a call was about — and whether it was spam.")
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Your privacy", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("• CallScribe has no servers and no analytics.")
            Text("• You bring your own services: audio goes only to your transcription endpoint, transcripts only to the AI provider you pick.")
            Text("• Nothing leaves your phone until you set one up.")
        }
    }
    Body("Setup takes a couple of minutes. Everything except the first permission can be skipped and done later.")
}

@Composable
private fun PermissionsStep(st: SetupStatus) {
    val ctx = LocalContext.current
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        asked = true
        if (granted(ctx, Manifest.permission.READ_MEDIA_AUDIO)) WorkScheduler.scanNow(ctx)
    }
    Body("CallScribe reads the recordings your phone saves in Recordings/Call. It needs access to audio files for that — it can't work without it.")
    Body("Notifications (optional) tell you when a call has been transcribed and whether it looked like spam.")
    StatusLine(st.audioGranted, "Audio files: allowed", "Audio files: not allowed yet")
    StatusLine(st.notificationsGranted, "Notifications: allowed", "Notifications: off")
    if (!st.audioGranted || !st.notificationsGranted) {
        Button(onClick = {
            launcher.launch(arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
        }) { Text("Allow access") }
    }
    // After a denial Android may stop asking; then only the settings page can grant it.
    if (asked && !st.audioGranted) {
        Text("If no dialog appears, allow \"Music and audio\" in the app's permissions.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { ctx.open(ctx.appDetails()) }) { Text("Open app settings") }
    } else if (asked && !st.notificationsGranted) {
        OutlinedButton(onClick = {
            ctx.open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
        }) { Text("Open notification settings") }
    }
}

@Composable
private fun RecordingStep(st: SetupStatus) {
    val ctx = LocalContext.current
    Body("CallScribe doesn't record calls itself — it uses the recordings Samsung's Phone app makes. To record every call automatically:")
    Card {
        Text(
            "Phone  →  ⋮  →  Settings  →  Record calls  →  Auto record calls",
            style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(16.dp),
        )
    }
    OutlinedButton(onClick = { ctx.open(Intent(TelecomManager.ACTION_SHOW_CALL_SETTINGS)) }) { Text("Open call settings") }
    StatusLine(
        st.recordingCount > 0,
        "${st.recordingCount} recording${if (st.recordingCount == 1) "" else "s"} found",
        "No recordings found yet",
    )
    if (st.recordingCount == 0) {
        Text("Screened calls and recorded calls appear here after the call ends.", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { WorkScheduler.scanNow(ctx) }) { Text("Look again") }
    }
}

@Composable
private fun BackgroundStep(st: SetupStatus) {
    val ctx = LocalContext.current
    Body("Samsung puts apps to sleep to save battery, which can delay automatic transcription of new calls. To keep CallScribe working in the background:")
    Card {
        Text(
            "Settings  →  Battery  →  Background usage limits  →  Never sleeping apps  →  add CallScribe",
            style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(16.dp),
        )
    }
    Body("Or open the app's info page and set Battery to Unrestricted.")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { ctx.open(ctx.appDetails()) }) { Text("App info") }
        OutlinedButton(onClick = { ctx.open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) { Text("Battery settings") }
    }
    StatusLine(st.batteryUnrestricted, "Battery: unrestricted", "Battery: optimized (may delay processing)")
    Text("Only matters for automatic processing. Calls you transcribe by hand work either way.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun PolicyStep(graph: AppGraph, autoEnabled: Boolean) {
    val scope = rememberCoroutineScope()
    val selected = StartingPolicy.current(autoEnabled)
    fun choose(c: StartingPolicy) = scope.launch {
        val dao = graph.db.policies()
        dao.ensureGroupDefaults()
        StartingPolicy.groupUpdates(c, dao.all()).forEach { dao.update(it) }
        graph.settings.update { it.copy(autoEnabled = StartingPolicy.autoEnabled(c)) }
    }
    Body("Pick a starting point. You can fine-tune it per caller type or per contact later in Settings → Policies.")
    StartingPolicy.entries.forEach { c ->
        val on = c == selected
        Card(
            Modifier.fillMaxWidth().clickable { choose(c) },
            border = if (on) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                RadioButton(on, { choose(c) })
                Column(Modifier.padding(top = 10.dp)) {
                    Text(c.title, style = MaterialTheme.typography.titleMedium)
                    Text(c.detail, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    Text("Automatic processing only runs once transcription is set up.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun DoneStep(st: SetupStatus) {
    Body("Here's where things stand. You can change any of this in Settings, or run this setup again from there.")
    HorizontalDivider()
    StatusLine(st.audioGranted, "Access to recordings", "No access to recordings")
    StatusLine(st.notificationsGranted, "Notifications on", "Notifications off")
    StatusLine(st.recordingCount > 0, "${st.recordingCount} recordings found", "No recordings found yet")
    StatusLine(st.batteryUnrestricted, "Runs in the background", "May be put to sleep by the battery saver")
    StatusLine(st.transcriberConfigured, "Transcription set up", "Transcription not set up")
    StatusLine(st.analyzerConfigured, "AI summary set up", "AI summary not set up (optional)")
}

/** Small call-list banner shown when a required setup item is missing. */
@Composable
fun SetupBanner(missing: List<String>, onFinish: () -> Unit, onDismiss: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text("Setup incomplete", style = MaterialTheme.typography.titleSmall)
                Text(missing.joinToString(" · ").replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onFinish) { Text("Finish setup") }
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Hide") }
        }
    }
}
