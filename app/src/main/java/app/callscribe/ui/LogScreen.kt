package app.callscribe.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.callscribe.Logx
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The app's log on screen, newest at the bottom: read it, select a part, copy it (to paste into a chat), save it to
 * Downloads (reachable from a computer over USB) or share it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var reload by remember { mutableIntStateOf(0) }
    var problemsOnly by remember { mutableStateOf(false) }
    val entries = remember(reload) { LogView.entries(Logx.text()) }
    val shown = if (problemsOnly) entries.filter { it.problem } else entries
    val list = rememberLazyListState()
    LaunchedEffect(shown.size, problemsOnly) { if (shown.isNotEmpty()) list.scrollToItem(shown.size - 1) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Log") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = { IconButton(onClick = { reload++ }) { Icon(Icons.Default.Refresh, "Reload") } },
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(problemsOnly, { problemsOnly = !problemsOnly }, label = { Text("Errors & warnings") })
                OutlinedButton(onClick = { LogView.copy(ctx, shown) }) { Text("Copy") }
                OutlinedButton(onClick = { LogView.saveToDownloads(ctx) }) { Text("Save to Downloads") }
                OutlinedButton(onClick = { LogView.share(ctx) }) { Text("Share") }
            }
            Text(
                if (entries.isEmpty()) "The log is empty." else "${shown.size} entries · long-press to select text",
                style = MaterialTheme.typography.bodySmall,
            )
            SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(shown) { e ->
                        val cs = MaterialTheme.colorScheme
                        Text(
                            e.text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp,
                            color = when (e.level) { 'E' -> cs.error; 'W' -> cs.tertiary; else -> cs.onSurface },
                        )
                    }
                }
            }
        }
    }
}

/** Parsing and the copy / save / share actions. */
object LogView {
    data class Entry(val level: Char, val text: String) { val problem get() = level == 'E' || level == 'W' }

    private val LINE = Regex("""^\d\d-\d\d \d\d:\d\d:\d\d\.\d{3} ([DIWE]) """)

    /** Splits the log into entries; a stack trace stays with the line it belongs to. Pure — unit-tested. */
    fun entries(text: String): List<Entry> {
        val out = mutableListOf<Entry>()
        for (line in text.lines()) {
            if (line.isEmpty()) continue
            val m = LINE.find(line)
            if (m != null || out.isEmpty()) out += Entry(m?.groupValues?.get(1)?.single() ?: 'I', line)
            else out[out.size - 1] = out.last().copy(text = out.last().text + "\n" + line)
        }
        return out
    }

    fun copy(ctx: Context, entries: List<Entry>) {
        // The clipboard goes through Android's 1 MB transaction limit: keep the newest part.
        val text = entries.joinToString("\n") { it.text }.takeLast(200_000)
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("CallScribe log", text))
        Toast.makeText(ctx, "Copied ${entries.size} entries", Toast.LENGTH_SHORT).show()
    }

    /** Writes the whole log to Downloads (no permission needed): on a computer it's in the phone's Download folder. */
    fun saveToDownloads(ctx: Context) {
        val name = "callscribe-log-${DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())}.txt"
        val ok = runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
            ctx.contentResolver.openOutputStream(uri)!!.use { it.write(Logx.text().toByteArray()) }
        }.onFailure { Logx.w("Log", "saving the log to Downloads failed", it) }.isSuccess
        Toast.makeText(ctx, if (ok) "Saved to Downloads/$name" else "Couldn't save the log", Toast.LENGTH_LONG).show()
    }

    fun share(ctx: Context) {
        val text = Logx.text().takeLast(200_000).ifEmpty { "(empty)" }
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "CallScribe log").putExtra(Intent.EXTRA_TEXT, text)
        runCatching { ctx.startActivity(Intent.createChooser(send, "Share log")) }
    }
}
