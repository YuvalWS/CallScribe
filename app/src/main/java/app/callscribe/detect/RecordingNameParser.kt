package app.callscribe.detect

import app.callscribe.data.LabelKind
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class ParsedName(
    /** Contact name or phone number shown by Samsung, if any. */
    val counterparty: String?,
    /** Raw caller-ID label text from the file name, e.g. "Suspected spam". */
    val samsungLabel: String?,
    val labelKind: LabelKind,
    val timestamp: LocalDateTime?,
)

/**
 * Parses Samsung call-recording file names.
 *
 * Seen formats:
 *  - `Call recording <name or number>_yyMMdd_HHmmss.m4a` (One UI 5–8)
 *  - `Call recording  _yyMMdd_HHmmss.m4a` (hidden / private number)
 *  - `Call_Suspected_Spam..._yyMMdd_HHmmss.m4a` (One UI 9 screened calls; exact tail not yet confirmed)
 */
object RecordingNameParser {

    /** Samsung caller-ID labels → kind. Keys are lower-case with single spaces. Add new labels here. */
    val LABELS: Map<String, LabelKind> = linkedMapOf(
        "suspected spam" to LabelKind.SPAM,
        "spam" to LabelKind.SPAM,
        "potential fraud" to LabelKind.FRAUD,
        "suspected fraud" to LabelKind.FRAUD,
        "fraud" to LabelKind.FRAUD,
        "scam" to LabelKind.FRAUD,
        "telemarketing" to LabelKind.OTHER_LABEL,
        "telemarketer" to LabelKind.OTHER_LABEL,
        "robocall" to LabelKind.OTHER_LABEL,
        "survey" to LabelKind.OTHER_LABEL,
        "business" to LabelKind.OTHER_LABEL,
    )

    private val PREFIXES = listOf("call recording", "הקלטת שיחה", "call")
    private val TIMESTAMP = Regex("""[_ ](\d{6})[_ ](\d{6})$""")
    private val TS_FORMAT = DateTimeFormatter.ofPattern("yyMMddHHmmss")
    private val NUMBER = Regex("""^\+?[\d\s\-()*#]{3,}$""")
    private val PRIVATE_WORDS = setOf("", "private", "private number", "unknown", "hidden", "חסוי", "מספר חסוי")

    fun parse(displayName: String): ParsedName {
        var s = displayName.substringBeforeLast('.', displayName).trim()

        var timestamp: LocalDateTime? = null
        TIMESTAMP.find(s)?.let { m ->
            timestamp = runCatching {
                LocalDateTime.parse(m.groupValues[1] + m.groupValues[2], TS_FORMAT)
            }.getOrNull()
            s = s.substring(0, m.range.first)
        }

        // Strip the "Call recording" / "Call_" prefix (case-insensitive), then separators.
        val lower = s.lowercase()
        for (p in PREFIXES) {
            if (lower.startsWith(p)) {
                val rest = s.substring(p.length)
                // Only accept "call" when followed by a separator, so a contact called "Callum" survives.
                if (p == "call" && rest.isNotEmpty() && rest[0] != '_' && rest[0] != ' ') continue
                s = rest
                break
            }
        }
        val body = s.replace('_', ' ').replace(Regex("""\s+"""), " ").trim()
        val norm = body.lowercase()

        // Label, possibly followed by a number / name ("Suspected spam +97250...").
        for ((label, kind) in LABELS) {
            if (norm == label || norm.startsWith("$label ")) {
                val rest = body.substring(label.length).trim().ifEmpty { null }
                return ParsedName(rest, body.substring(0, label.length), kind, timestamp)
            }
        }

        if (norm in PRIVATE_WORDS) return ParsedName(null, null, LabelKind.PRIVATE, timestamp)
        if (NUMBER.matches(body) && body.count { it.isDigit() } >= 3) {
            return ParsedName(body, null, LabelKind.UNKNOWN_NUMBER, timestamp)
        }
        return ParsedName(body, null, LabelKind.CONTACT, timestamp)
    }
}
