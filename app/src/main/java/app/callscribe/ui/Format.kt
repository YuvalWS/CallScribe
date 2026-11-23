package app.callscribe.ui

import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import app.callscribe.data.LabelKind
import app.callscribe.data.RecordingEntity
import app.callscribe.data.Status
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Fmt {
    private val dt = DateTimeFormatter.ofPattern("dd/MM/yy HH:mm")
    fun time(sec: Long): String = dt.format(Instant.ofEpochSecond(sec).atZone(ZoneId.systemDefault()))
    fun duration(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }
    fun usd(v: Double?): String = when {
        v == null -> "—"
        v == 0.0 -> "$0"
        v < 0.01 -> String.format(Locale.US, "$%.4f", v)
        else -> String.format(Locale.US, "$%.3f", v)
    }
    fun title(r: RecordingEntity) = r.counterparty ?: r.samsungLabel ?: when (r.labelKind) {
        LabelKind.PRIVATE -> "Private number"
        else -> "Unknown"
    }
    fun status(s: Status) = when (s) {
        Status.NOT_TRANSCRIBED -> "Not transcribed"
        Status.SKIPPED_BY_RULE -> "Skipped by rule"
        Status.QUEUED -> "Queued"
        Status.TRANSCRIBING -> "Transcribing…"
        Status.TRANSCRIBED -> "Transcribed"
        Status.ANALYZING -> "Analyzing…"
        Status.ANALYZED -> "Done"
        Status.FAILED -> "Failed"
    }
    fun label(k: LabelKind) = when (k) {
        LabelKind.CONTACT -> "Contact"
        LabelKind.UNKNOWN_NUMBER -> "Unknown number"
        LabelKind.PRIVATE -> "Private"
        LabelKind.SPAM -> "Spam"
        LabelKind.FRAUD -> "Fraud"
        LabelKind.OTHER_LABEL -> "Labelled"
    }
}

@Composable
fun SmallChip(text: String, color: Color? = null) {
    AssistChip(
        onClick = {},
        label = { Text(text) },
        // Light tinted chips need dark text in both themes.
        colors = if (color != null) AssistChipDefaults.assistChipColors(containerColor = color, labelColor = Color(0xFF1C1B1F))
        else AssistChipDefaults.assistChipColors(),
    )
}

fun verdictColor(v: String?): Color? = when (v) {
    "spam" -> Color(0xFFFFCDD2)
    "not_spam" -> Color(0xFFC8E6C9)
    "unsure" -> Color(0xFFFFF9C4)
    else -> null
}

fun labelColor(k: LabelKind): Color? = when (k) {
    LabelKind.SPAM, LabelKind.FRAUD -> Color(0xFFFFCDD2)
    LabelKind.OTHER_LABEL -> Color(0xFFFFE0B2)
    else -> null
}
