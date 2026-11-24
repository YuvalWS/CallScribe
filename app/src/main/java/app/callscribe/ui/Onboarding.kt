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
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.History
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
import app.callscribe.data.AppSettings
import app.callscribe.data.RecordingEntity
import app.callscribe.data.Status
import app.callscribe.detect.WorkScheduler
import app.callscribe.pipeline.BulkProgress
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private fun granted(ctx: Context, perm: String) = ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

/** The step first-run setup was last on, kept across the app being closed or killed. */
object OnboardingProgress {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("onboarding", Context.MODE_PRIVATE)
    fun save(ctx: Context, step: SetupStep) = prefs(ctx).edit().putString("step", step.name).apply()
    fun clear(ctx: Context) = prefs(ctx).edit().clear().apply()
    fun saved(ctx: Context): SetupStep? = prefs(ctx).getString("step", null)?.let { n -> SetupStep.entries.firstOrNull { it.name == n } }
}

/**
 * Live [SetupStatus], refreshed when settings or recordings change, when the app resumes (the user may come
 * back from system settings) and when [refreshKey] changes. Null until settings have loaded.
 */
@Composable
fun rememberSetupStatus(graph: AppGraph, refreshKey: Any? = null): SetupStatus? {
    val ctx = LocalContext.current
    val settings by graph.settings.flow.collectAsStateWithLifecycle(initialValue = null)
    val count by remember { graph.db.recordings().observeAll().map { it.size } }.collectAsStateWithLifecycle(initialValue = 0)
    val drive by graph.drive.settings.flow.collectAsStateWithLifecycle(initialValue = null)
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose { }
    }
    return remember(settings, count, drive, resumes, refreshKey) {
        val s = settings ?: return@remember null
        SetupStatus(
            audioGranted = granted(ctx, Manifest.permission.READ_MEDIA_AUDIO),
            notificationsGranted = granted(ctx, Manifest.permission.POST_NOTIFICATIONS),
            recordingCount = count,
            batteryUnrestricted = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName),
            transcriberConfigured = graph.providers.transcriber(s)?.isConfigured() == true,
            analyzerConfigured = graph.providers.analyzer(s)?.isConfigured() == true,
            driveBackupOn = drive?.let { it.enabled && !it.needsSignIn } == true,
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
    val ctx = LocalContext.current
    var step by rememberSaveable { mutableStateOf(start) }
    val settings by graph.settings.flow.collectAsStateWithLifecycle(initialValue = null)
    val status = rememberSetupStatus(graph, refreshKey = step)
    val steps = SetupStep.entries
    val idx = step.ordinal
    // Remember the step, so reopening the app after it was closed mid-setup continues here instead of at Welcome.
    androidx.compose.runtime.LaunchedEffect(step) { OnboardingProgress.save(ctx, step) }

    // What a step does when the user taps Next (e.g. the existing-calls step starts the chosen run). Set by the step.
    val onNext = remember { androidx.compose.runtime.mutableStateMapOf<SetupStep, suspend () -> Unit>() }

    fun next() {
        val action = onNext.remove(step) // runs once
        if (idx < steps.lastIndex) {
            val from = step
            scope.launch { action?.invoke(); if (step == from) step = steps[idx + 1] }
            return
        }
        scope.launch {
            action?.invoke()
            graph.settings.update { it.copy(onboardingDone = true, setupBannerDismissed = false) }
            OnboardingProgress.clear(ctx)
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
            val ui = step.ui()
            Icon(ui.icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            Text(step.heading, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            val cur = step
            ui.body(StepContext(graph, st, s) { a -> if (a == null) onNext.remove(cur) else onNext[cur] = a })
        }
    }
}

private class StepContext(
    val graph: AppGraph, val status: SetupStatus, val settings: AppSettings,
    /** Registers what Next should do on this step (null = just move on). */
    val setOnNext: ((suspend () -> Unit)?) -> Unit,
)
private class StepUi(val icon: ImageVector, val body: @Composable (StepContext) -> Unit)

/** Icon + body of each step. Exhaustive on purpose: adding a [SetupStep] fails to compile until it has a UI here. */
private fun SetupStep.ui(): StepUi = when (this) {
    SetupStep.WELCOME -> StepUi(Icons.Default.Lock) { WelcomeStep() }
    SetupStep.PERMISSIONS -> StepUi(Icons.Default.LibraryMusic) { PermissionsStep(it.status) }
    SetupStep.RECORDING -> StepUi(Icons.Default.Call) { RecordingStep(it.status) }
    SetupStep.BACKGROUND -> StepUi(Icons.Default.BatteryFull) { BackgroundStep(it.status) }
    SetupStep.TRANSCRIPTION -> StepUi(Icons.Default.Subtitles) { c ->
        Body("Transcripts are made by an ivrit.ai Hebrew model running on your own RunPod endpoint. You pay RunPod directly — about $0.003 for a 14-minute call. Paste a RunPod API key and tap Set up automatically — CallScribe finds or creates the endpoint for you.")
        Body("You can skip this and set it up later in Settings → Providers. Until then CallScribe works as a recordings browser.")
        StatusLine(c.status.transcriberConfigured, "Transcription is set up", "Not set up yet")
        TranscriptionProviderFields(c.graph, c.settings)
    }
    SetupStep.ANALYSIS -> StepUi(Icons.Default.AutoAwesome) { c ->
        Body("Optional: an AI model reads each transcript and writes a short summary plus a spam / not-spam verdict. Pick a provider and paste your own key. Test connection is free.")
        StatusLine(c.status.analyzerConfigured, "AI summary is set up", "Not set up yet")
        AnalyzerProviderFields(c.graph, c.settings)
    }
    SetupStep.POLICY -> StepUi(Icons.Default.Tune) { PolicyStep(it.graph, it.settings.autoEnabled) }
    SetupStep.EXISTING -> StepUi(Icons.Default.History) { ExistingStep(it.graph, it.status, it.settings, it.setOnNext) }
    SetupStep.BACKUP -> StepUi(Icons.Default.CloudUpload) { BackupStep(it.graph) }
    SetupStep.DONE -> StepUi(Icons.Default.CheckCircle) { DoneStep(it.status) }
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
    Text("Automatic processing only runs once transcription is set up. Calls shorter than 5 seconds are skipped; you can still transcribe them by hand.", style = MaterialTheme.typography.bodySmall)
}

/** Which existing calls to process during onboarding. */
private enum class ExistingChoice(val title: String) { NONE("None for now"), RECENT("The last 30 days"), ALL("All of them") }

@Composable
private fun ExistingStep(graph: AppGraph, st: SetupStatus, s: AppSettings, setOnNext: ((suspend () -> Unit)?) -> Unit) {
    val ctx = LocalContext.current
    val rows by graph.db.recordings().observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val run by graph.bulk.run.collectAsStateWithLifecycle()
    var choice by rememberSaveable { mutableStateOf(ExistingChoice.NONE) }
    Body("New calls follow the choice you just made. Calls already on the phone (${st.recordingCount}) are only processed if you say so.")
    if (!st.transcriberConfigured) {
        StatusLine(false, "", "Set up transcription first")
        Text("You can do it any time later: on the call list, tap the select button, pick calls (or All) and tap Process.", style = MaterialTheme.typography.bodySmall)
        return
    }

    TryLatestCall(graph, rows)

    // A run already going (started here, or before the app was closed): show it instead of the choices.
    val r = run
    val progress = remember(r, rows) { r?.let { BulkProgress.of(it, rows) } }
    if (r != null && progress != null && progress.running && r.stoppedReason == null) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Processing ${progress.done} of ${progress.total} calls", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                LinearProgressIndicator(progress = { progress.done.toFloat() / progress.total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                Text("This runs in the background. Tap Next to finish setup, or close the app — progress stays in a notification and on the call list, where you can also cancel it.")
            }
        }
        return
    }

    val monthAgo = System.currentTimeMillis() / 1000 - 30L * 86_400
    ExistingChoice.entries.forEach { c ->
        val on = c == choice
        val subset = when (c) { ExistingChoice.NONE -> emptyList(); ExistingChoice.RECENT -> rows.filter { it.callTimeSec >= monthAgo }; ExistingChoice.ALL -> rows }
        val est = remember(subset, s) { if (c == ExistingChoice.NONE) null else estimateFor(graph, s, subset, rows, summarize = true) }
        Card(
            Modifier.fillMaxWidth().clickable { choice = c },
            border = if (on) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                RadioButton(on, { choice = c })
                Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(c.title, style = MaterialTheme.typography.titleMedium)
                    if (est == null) Text("You can process any call later from the call list.", style = MaterialTheme.typography.bodySmall)
                    else if (est.first.isEmpty) Text(
                        if (subset.isEmpty()) "No calls in this period." else "Nothing left to process here.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    else if (on) EstimateLines(est.first, est.second)
                    else Text(
                        "${est.second.calls} calls · " + (if (est.second.isFree) "free" else "about ${Fmt.usd(est.second.usd)}"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
    val pick = remember(choice, rows, s) {
        when (choice) {
            ExistingChoice.NONE -> null
            ExistingChoice.RECENT -> estimateFor(graph, s, rows.filter { it.callTimeSec >= monthAgo }, rows, summarize = true)
            ExistingChoice.ALL -> estimateFor(graph, s, rows, rows, summarize = true)
        }
    }
    // Next starts the chosen run; the app says it's working in the background and setup carries on.
    val start = pick?.takeIf { !it.first.isEmpty }
    androidx.compose.runtime.DisposableEffect(start) {
        setOnNext(start?.let { (plan, est) ->
            suspend {
                graph.bulk.start(plan, est.capUsd)
                Toast.makeText(
                    ctx, "Processing ${est.calls} calls in the background — progress is in the notification and on the call list.",
                    Toast.LENGTH_LONG,
                ).show()
            }
        })
        onDispose { setOnNext(null) }
    }
    if (start != null) Text(
        "Tap Next to start. It runs in the background — you can finish setup or close the app.",
        style = MaterialTheme.typography.bodySmall,
    )
}

/**
 * "See it work first": processes the newest call on its own and shows the result right here, so the user knows
 * what they get (and that the provider works) before starting a big run.
 */
@Composable
private fun TryLatestCall(graph: AppGraph, rows: List<RecordingEntity>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var triedId by rememberSaveable { mutableStateOf<Long?>(null) }
    val latest = rows.filter { !it.fileMissing }.maxByOrNull { it.callTimeSec } ?: return
    val shown = triedId?.let { id -> rows.firstOrNull { it.id == id } }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Not sure? Try it on your latest call", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("${Fmt.title(latest)} · ${Fmt.time(latest.callTimeSec)}", style = MaterialTheme.typography.bodySmall)
            when {
                shown == null && latest.transcriptText != null -> OutlinedButton(onClick = { triedId = latest.id }) { Text("Show its result") }
                shown == null -> OutlinedButton(onClick = {
                    triedId = latest.id
                    scope.launch { processNow(ctx, graph, latest) }
                }) { Text("Process this call") }
                shown.status in setOf(Status.QUEUED, Status.TRANSCRIBING, Status.ANALYZING) -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        when (shown.status) {
                            Status.ANALYZING -> "Writing the summary…"
                            Status.TRANSCRIBING -> "Transcribing… the first call can take a minute or two while the server starts."
                            else -> "Starting…"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                shown.status == Status.FAILED -> Text(
                    "It didn't work: ${shown.error ?: "unknown error"}. Check the provider settings on the previous steps.",
                    color = MaterialTheme.colorScheme.error,
                )
                else -> {
                    shown.summary?.let { Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold) }
                    shown.transcriptText?.let { Text(it.take(400) + if (it.length > 400) "…" else "", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@Composable
private fun BackupStep(graph: AppGraph) {
    val st by graph.drive.settings.flow.collectAsStateWithLifecycle(initialValue = null)
    Body(
        "Optional: keep your transcripts, summaries and settings — and, if you like, the recordings — in your own " +
            "Google Drive, in a folder for this phone. API keys never leave the phone. You can restore everything on a new phone."
    )
    st?.let { DriveBackupToggles(graph, it) }
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
    StatusLine(st.driveBackupOn, "Backing up to Google Drive", "No Drive backup (optional)")
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
