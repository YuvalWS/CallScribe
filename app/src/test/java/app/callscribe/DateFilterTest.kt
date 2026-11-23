package app.callscribe

import app.callscribe.ui.DateFilter
import app.callscribe.ui.DatePreset
import app.callscribe.ui.DayRange
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DateFilterTest {
    private val il = ZoneId.of("Asia/Jerusalem")
    private val la = ZoneId.of("America/Los_Angeles")
    private val today = LocalDate.of(2026, 11, 23)

    private fun sec(y: Int, m: Int, d: Int, h: Int, min: Int, s: Int, zone: ZoneId) =
        LocalDateTime.of(y, m, d, h, min, s).atZone(zone).toEpochSecond()

    private fun utcMillis(y: Int, m: Int, d: Int) = DateFilter.toPickerMillis(LocalDate.of(y, m, d))

    @Test fun presets() {
        assertEquals(DayRange(today, today), DateFilter.preset(DatePreset.TODAY, today))
        assertEquals(DayRange(LocalDate.of(2026, 11, 17), today), DateFilter.preset(DatePreset.LAST_7, today))
        assertEquals(DayRange(LocalDate.of(2026, 10, 25), today), DateFilter.preset(DatePreset.LAST_30, today))
        assertEquals(DayRange(LocalDate.of(2026, 1, 1), today), DateFilter.preset(DatePreset.THIS_YEAR, today))
    }

    @Test fun last7CrossesYearBoundary() {
        val r = DateFilter.preset(DatePreset.LAST_7, LocalDate.of(2026, 1, 3))
        assertEquals(LocalDate.of(2025, 12, 28), r.start)
    }

    @Test fun inclusiveWholeDaysInLocalZone() {
        val r = DayRange(today, today)
        assertTrue(r.contains(sec(2026, 11, 23, 0, 0, 0, il), il))
        assertTrue(r.contains(sec(2026, 11, 23, 23, 59, 59, il), il))
        assertFalse(r.contains(sec(2026, 11, 22, 23, 59, 59, il), il))
        assertFalse(r.contains(sec(2026, 11, 24, 0, 0, 0, il), il))
    }

    @Test fun customRangeUsesChosenDaysRegardlessOfZone() {
        // Picker returns UTC midnight for 3 Nov and 9 Nov.
        val r = DateFilter.custom(utcMillis(2026, 11, 3), utcMillis(2026, 11, 9))
        assertEquals(DayRange(LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 9)), r)
        for (z in listOf(il, la)) {
            assertTrue(r.contains(sec(2026, 11, 3, 0, 0, 0, z), z))
            assertTrue(r.contains(sec(2026, 11, 9, 23, 59, 59, z), z))
            assertFalse(r.contains(sec(2026, 11, 2, 23, 59, 59, z), z))
            assertFalse(r.contains(sec(2026, 11, 10, 0, 0, 0, z), z))
        }
    }

    @Test fun singleDayAndReversedCustomRange() {
        val one = DateFilter.custom(utcMillis(2026, 5, 5), utcMillis(2026, 5, 5))
        assertEquals(one.start, one.end)
        val rev = DateFilter.custom(utcMillis(2026, 5, 9), utcMillis(2026, 5, 5))
        assertEquals(LocalDate.of(2026, 5, 5), rev.start)
    }

    @Test fun pickerMillisRoundTrip() {
        val d = LocalDate.of(2026, 2, 28)
        assertEquals(DayRange(d, d), DateFilter.custom(DateFilter.toPickerMillis(d), DateFilter.toPickerMillis(d)))
    }
}
