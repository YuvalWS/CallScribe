package app.callscribe.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openId = intent.getLongExtra(Notifier.EXTRA_RECORDING_ID, -1)
        val graph = (application as CallScribeApp).graph
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    PermissionGate {
                        AppNav(graph, openId) { openId = -1 }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openId = intent.getLongExtra(Notifier.EXTRA_RECORDING_ID, -1)
    }
}

@Composable
private fun AppNav(graph: app.callscribe.AppGraph, openId: Long, consumed: () -> Unit) {
    val nav = rememberNavController()
    LaunchedEffect(openId) {
        if (openId > 0) { nav.navigate("detail/$openId"); consumed() }
    }
    NavHost(nav, startDestination = "list") {
        composable("list") {
            CallListScreen(graph, onOpen = { nav.navigate("detail/$it") }, onSettings = { nav.navigate("settings") })
        }
        composable("detail/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
            CallDetailScreen(graph, e.arguments!!.getLong("id"), onBack = { nav.popBackStack() }, onSetup = { nav.navigate("providers") })
        }
        composable("settings") {
            SettingsScreen(graph, onBack = { nav.popBackStack() }, onProviders = { nav.navigate("providers") }, onRules = { nav.navigate("rules") })
        }
        composable("providers") { ProvidersScreen(graph, onBack = { nav.popBackStack() }) }
        composable("rules") { RulesScreen(graph, onBack = { nav.popBackStack() }) }
    }
}

@Composable
private fun PermissionGate(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    fun granted() = ctx.checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
    var ok by remember { mutableStateOf(granted()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        ok = granted()
        if (ok) WorkScheduler.scanNow(ctx)
    }
    LaunchedEffect(Unit) {
        if (!ok) launcher.launch(arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
        else WorkScheduler.scanNow(ctx)
    }
    if (ok) content() else {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("CallScribe needs access to audio files", style = MaterialTheme.typography.headlineSmall)
            Text("It reads the call recordings your phone saves in Recordings/Call. Nothing is uploaded until you set up a transcription service.")
            Button(onClick = { launcher.launch(arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS)) }) {
                Text("Grant access")
            }
        }
    }
}
