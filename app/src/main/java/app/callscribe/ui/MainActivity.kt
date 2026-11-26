package app.callscribe.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.callscribe.CallScribeApp
import app.callscribe.detect.WorkScheduler
import app.callscribe.pipeline.Notifier

class MainActivity : ComponentActivity() {
    private var openId by mutableStateOf(-1L)
    private var openRetention by mutableStateOf(false)
    private var openBackup by mutableStateOf(false)
    private var openProviders by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openId = intent.getLongExtra(Notifier.EXTRA_RECORDING_ID, -1)
        openRetention = intent.getBooleanExtra(Notifier.EXTRA_OPEN_RETENTION, false)
        openBackup = intent.getBooleanExtra(Notifier.EXTRA_OPEN_BACKUP, false)
        openProviders = intent.getBooleanExtra(Notifier.EXTRA_OPEN_PROVIDERS, false)
        val graph = (application as CallScribeApp).graph
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    SetupGate(graph) {
                        AppNav(graph, openId, openRetention, openBackup, openProviders) { openId = -1; openRetention = false; openBackup = false; openProviders = false }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openId = intent.getLongExtra(Notifier.EXTRA_RECORDING_ID, -1)
        openRetention = intent.getBooleanExtra(Notifier.EXTRA_OPEN_RETENTION, false)
        openBackup = intent.getBooleanExtra(Notifier.EXTRA_OPEN_BACKUP, false)
        openProviders = intent.getBooleanExtra(Notifier.EXTRA_OPEN_PROVIDERS, false)
    }
}

@Composable
private fun AppNav(graph: app.callscribe.AppGraph, openId: Long, openRetention: Boolean, openBackup: Boolean, openProviders: Boolean, consumed: () -> Unit) {
    val nav = rememberNavController()
    LaunchedEffect(openId, openRetention, openBackup, openProviders) {
        if (openId > 0) { nav.navigate("detail/$openId"); consumed() }
        if (openRetention) { nav.navigate("retention"); consumed() }
        if (openBackup) { nav.navigate("backup"); consumed() }
        if (openProviders) { nav.navigate("providers"); consumed() }
    }
    NavHost(nav, startDestination = "list") {
        composable("list") {
            CallListScreen(
                graph, onOpen = { nav.navigate("detail/$it") }, onSettings = { nav.navigate("settings") },
                onSetup = { nav.navigate("providers") }, onFinishSetup = { step -> nav.navigate("setup?start=${step.name}") },
            )
        }
        composable("detail/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
            CallDetailScreen(
                graph, e.arguments!!.getLong("id"), onBack = { nav.popBackStack() },
                onSetup = { nav.navigate("providers") }, onPolicies = { nav.navigate("policies") },
            )
        }
        composable("settings") {
            SettingsScreen(
                graph, onBack = { nav.popBackStack() }, onProviders = { nav.navigate("providers") },
                onRules = { nav.navigate("policies") }, onRetention = { nav.navigate("retention") },
                onRunSetup = { nav.navigate("setup?start=${SetupStep.WELCOME.name}") },
                onBackup = { nav.navigate("backup") }, onLog = { nav.navigate("log") },
            )
        }
        composable("log") { LogScreen(onBack = { nav.popBackStack() }) }
        composable("setup?start={start}", arguments = listOf(navArgument("start") { type = NavType.StringType; defaultValue = SetupStep.WELCOME.name })) { e ->
            val start = SetupStep.valueOf(e.arguments?.getString("start") ?: SetupStep.WELCOME.name)
            OnboardingScreen(graph, start, onExit = { nav.popBackStack() }, onFinish = { nav.popBackStack() })
        }
        composable("providers") { ProvidersScreen(graph, onBack = { nav.popBackStack() }) }
        composable("policies") { PoliciesScreen(graph, onBack = { nav.popBackStack() }, onRetention = { nav.navigate("retention") }) }
        composable("retention") { RetentionScreen(graph, onBack = { nav.popBackStack() }) }
        composable("backup") { BackupScreen(graph, onBack = { nav.popBackStack() }, onPolicies = { nav.navigate("policies") }) }
    }
}

/**
 * First run (or audio access lost) → onboarding; otherwise the app. Replaces the old bare permission gate.
 * See docs/plans/onboarding.md.
 */
@Composable
private fun SetupGate(graph: app.callscribe.AppGraph, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val settings by graph.settings.flow.collectAsStateWithLifecycle(initialValue = null)
    val status = rememberSetupStatus(graph)
    val audio = status?.audioGranted == true
    LaunchedEffect(audio) { if (audio) WorkScheduler.scanNow(ctx) }
    val s = settings ?: return
    if (status == null) return
    if (!s.onboardingDone || !audio) {
        OnboardingScreen(
            graph,
            start = when {
                s.onboardingDone || !audio -> if (s.onboardingDone || OnboardingProgress.saved(ctx) != null) SetupStep.PERMISSIONS else SetupStep.WELCOME
                else -> OnboardingProgress.saved(ctx) ?: SetupStep.WELCOME
            },
            onExit = null, onFinish = {},
        )
    } else content()
}
