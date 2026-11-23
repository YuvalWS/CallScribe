package app.callscribe.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Inclusive range of local calendar days. */
data class DayRange(val start: LocalDate, val end: LocalDate) {
    /** [callTimeSec] is epoch seconds; the day is taken in [zone] (the phone's local zone). */
    fun contains(callTimeSec: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val d = Instant.ofEpochSecond(callTimeSec).atZone(zone).toLocalDate()
        return !d.isBefore(start) && !d.isAfter(end)
    }
}

enum class DatePreset(val title: String) {
    TODAY("Today"),
    LAST_7("Last 7 days"),
    LAST_30("Last 30 days"),
    THIS_YEAR("This year"),
}

/** Pure date-range helpers for the call list filter. Unit-tested. */
object DateFilter {
    fun preset(p: DatePreset, today: LocalDate): DayRange = when (p) {
        DatePreset.TODAY -> DayRange(today, today)
        DatePreset.LAST_7 -> DayRange(today.minusDays(6), today)
        DatePreset.LAST_30 -> DayRange(today.minusDays(29), today)
        DatePreset.THIS_YEAR -> DayRange(today.withDayOfYear(1), today)
    }

    /**
     * Material3 DateRangePicker reports each selected day as UTC-midnight millis. Read them as UTC
     * dates (reading in the local zone would shift the day west of UTC) and treat them as local days.
     */
    fun custom(startUtcMillis: Long, endUtcMillis: Long): DayRange {
        val a = Instant.ofEpochMilli(startUtcMillis).atZone(ZoneOffset.UTC).toLocalDate()
        val b = Instant.ofEpochMilli(endUtcMillis).atZone(ZoneOffset.UTC).toLocalDate()
        return if (b.isBefore(a)) DayRange(b, a) else DayRange(a, b)
    }

    /** UTC-midnight millis for a local day, to pre-select the picker. */
    fun toPickerMillis(d: LocalDate): Long = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}

/**
 * Compact string form of the selected date filter, so it survives rotation via rememberSaveable.
 * "" = none, "P:LAST_7" = preset, "C:<startEpochDay>:<endEpochDay>" = custom range.
 */
object DateSelection {
    fun ofPreset(p: DatePreset) = "P:${p.name}"
    fun ofCustom(r: DayRange) = "C:${r.start.toEpochDay()}:${r.end.toEpochDay()}"

    fun resolve(code: String, today: LocalDate): DayRange? {
        val parts = code.split(':')
        return when (parts.firstOrNull()) {
            "P" -> DatePreset.entries.firstOrNull { it.name == parts.getOrNull(1) }?.let { DateFilter.preset(it, today) }
            "C" -> {
                val a = parts.getOrNull(1)?.toLongOrNull() ?: return null
                val b = parts.getOrNull(2)?.toLongOrNull() ?: return null
                DayRange(LocalDate.ofEpochDay(a), LocalDate.ofEpochDay(b))
            }
            else -> null
        }
    }

    /** Chip text: preset title, or "3 Nov - 9 Nov" (with year when not the current one). */
    fun label(code: String, today: LocalDate): String {
        if (code.startsWith("P:")) DatePreset.entries.firstOrNull { it.name == code.substring(2) }?.let { return it.title }
        val r = resolve(code, today) ?: return ""
        fun fmt(d: LocalDate): String {
            val m = d.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
            return if (d.year == today.year) "${d.dayOfMonth} $m" else "${d.dayOfMonth} $m ${d.year}"
        }
        return if (r.start == r.end) fmt(r.start) else "${fmt(r.start)} - ${fmt(r.end)}"
    }
}
