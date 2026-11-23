package app.callscribe

import app.callscribe.data.LabelKind
import app.callscribe.data.PolicyEntity
import app.callscribe.data.RecordingEntity
import app.callscribe.detect.RecordingNameParser
import app.callscribe.rules.PhoneNumbers
import app.callscribe.rules.PolicyResolver
import app.callscribe.rules.Retention
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class ParserTest {
    // Shapes copied from a real One UI 5 phone, with names/numbers replaced.
    @Test fun internationalNumber() {
        val p = RecordingNameParser.parse("Call recording +972501234567_250527_120438.m4a")
        assertEquals(LabelKind.UNKNOWN_NUMBER, p.labelKind)
        assertEquals("+972501234567", p.counterparty)
        assertEquals(LocalDateTime.of(2025, 5, 27, 12, 4, 38), p.timestamp)
    }

    @Test fun localNumber() {
        val p = RecordingNameParser.parse("Call recording 031234567_211230_083040.m4a")
        assertEquals(LabelKind.UNKNOWN_NUMBER, p.labelKind)
        assertEquals("031234567", p.counterparty)
    }

    @Test fun hebrewContact() {
        val p = RecordingNameParser.parse("Call recording ישראל ישראלי_250805_195553.m4a")
        assertEquals(LabelKind.CONTACT, p.labelKind)
        assertEquals("ישראל ישראלי", p.counterparty)
    }

    @Test fun contactWithCommaAndDigits() {
        val p = RecordingNameParser.parse("Call recording מוקד מרכז, 1234_200811_210259.m4a")
        assertEquals(LabelKind.CONTACT, p.labelKind)
        assertEquals("מוקד מרכז, 1234", p.counterparty)
    }

    @Test fun mixedLanguageContactWithDigit() {
        val p = RecordingNameParser.parse("Call recording דנה Dana 3_250805_184624.m4a")
        assertEquals(LabelKind.CONTACT, p.labelKind)
        assertEquals("דנה Dana 3", p.counterparty)
    }

    @Test fun privateNumber() {
        val p = RecordingNameParser.parse("Call recording  _201120_101801.m4a")
        assertEquals(LabelKind.PRIVATE, p.labelKind)
        assertNull(p.counterparty)
    }

    @Test fun oneUi9SuspectedSpam() {
        val p = RecordingNameParser.parse("Call_Suspected_Spam_251110_143012.m4a")
        assertEquals(LabelKind.SPAM, p.labelKind)
        assertEquals("Suspected Spam", p.samsungLabel)
        assertNull(p.counterparty)
    }

    @Test fun oneUi9PotentialFraudWithNumber() {
        val p = RecordingNameParser.parse("Call_Potential_fraud_+972501234567_251110_143012.m4a")
        assertEquals(LabelKind.FRAUD, p.labelKind)
        assertEquals("+972501234567", p.counterparty)
    }

    @Test fun contactNamedCallum() {
        val p = RecordingNameParser.parse("Call recording Callum_250101_101010.m4a")
        assertEquals("Callum", p.counterparty)
    }
}

class PolicyTest {
    private fun cp(name: String) = RecordingNameParser.parse("Call recording ${name}_250101_101010.m4a")
    private val groups = LabelKind.entries.map { PolicyEntity.defaultFor(it) }
    private fun contact(name: String, process: Boolean, number: String = "") = PolicyEntity(
        displayName = name, numbers = PhoneNumbers.normalize(number).orEmpty(), transcribe = process, summarize = process,
    )
    private fun resolve(name: String, policies: List<PolicyEntity>) = cp(name).let { PolicyResolver.resolve(it.labelKind, it.counterparty, policies) }

    @Test fun normalizeNumbers() {
        assertEquals(PhoneNumbers.normalize("+972 54-123-4567"), PhoneNumbers.normalize("054-1234567"))
        assertNull(PhoneNumbers.normalize("123"))
    }

    @Test fun defaultsSkipContactsProcessOthers() {
        assertEquals(false, resolve("Mom", groups).transcribe)
        assertEquals(true, resolve("0541234567", groups).transcribe)
        assertEquals(true, resolve("Suspected spam", groups).summarize)
    }

    @Test fun contactPolicyOverridesGroup() {
        assertEquals(true, resolve("Mom", groups + contact("mom", true)).transcribe)
        assertEquals(false, resolve("0541234567", groups + contact("Plumber", false, "+972 54-123-4567")).transcribe)
    }

    @Test fun missingGroupFallsBackToDefault() {
        assertEquals(true, resolve("0541234567", emptyList()).transcribe)
    }
}

class RetentionTest {
    private val day = 86_400L
    private val now = 1_000 * day
    private fun rec(id: Long, kind: LabelKind, ageDays: Long, text: String? = null, cp: String? = "0541234567") = RecordingEntity(
        id = id, contentHash = "h$id", mediaId = id, uri = "u$id", displayName = "f$id", relativePath = "Recordings/Call/",
        sizeBytes = 1000, durationMs = 1000, dateAddedSec = 0, dateModifiedSec = 0,
        counterparty = cp, samsungLabel = null, labelKind = kind, callTimeSec = now - ageDays * day, transcriptText = text,
    )

    @Test fun keepForeverByDefault() {
        val plan = Retention.plan(listOf(rec(1, LabelKind.SPAM, 999, "x")), LabelKind.entries.map { PolicyEntity.defaultFor(it) }, now)
        assertTrue(plan.isEmpty)
    }

    @Test fun audioAndTextRetentionAreSeparate() {
        val spam = PolicyEntity.defaultFor(LabelKind.SPAM).copy(audioRetentionDays = 7, textRetentionDays = 30)
        val unknown = PolicyEntity.defaultFor(LabelKind.UNKNOWN_NUMBER).copy(audioRetentionDays = 7) // text kept forever
        val rows = listOf(
            rec(1, LabelKind.SPAM, 10, "t"), rec(2, LabelKind.SPAM, 40, "t"), rec(3, LabelKind.SPAM, 3, "t"),
            rec(4, LabelKind.UNKNOWN_NUMBER, 400, "t"),
        )
        val plan = Retention.plan(rows, listOf(spam, unknown), now)
        assertEquals(setOf(1L, 2L, 4L), plan.trashAudio.map { it.id }.toSet())
        assertEquals(setOf(2L), plan.deleteText.map { it.id }.toSet())
    }

    @Test fun contactOverrideAndAlreadyDeletedAudio() {
        val group = PolicyEntity.defaultFor(LabelKind.CONTACT).copy(audioRetentionDays = 30)
        val keepMom = PolicyEntity(displayName = "Mom", transcribe = false, summarize = false)
        val rows = listOf(
            rec(1, LabelKind.CONTACT, 60, cp = "Mom"),
            rec(2, LabelKind.CONTACT, 60, cp = "Dan"),
            rec(3, LabelKind.CONTACT, 60, cp = "Dan").copy(fileMissing = true, audioDeletedAt = 1),
        )
        assertEquals(listOf(2L), Retention.plan(rows, listOf(group, keepMom), now).trashAudio.map { it.id })
    }
}

class SearchTest {
    private fun rec(cp: String?, text: String? = null, summary: String? = null) = RecordingEntity(
        contentHash = "h", mediaId = 1, uri = "u", displayName = "Call recording x.m4a", relativePath = "",
        sizeBytes = 1, durationMs = 1, dateAddedSec = 0, dateModifiedSec = 0, counterparty = cp, samsungLabel = null,
        labelKind = LabelKind.CONTACT, callTimeSec = 0, transcriptText = text, summary = summary,
    )

    @Test fun byNameNumberAndText() {
        assertTrue(app.callscribe.ui.CallSearch.matches(rec("ישראל ישראלי"), "ישראל"))
        assertTrue(app.callscribe.ui.CallSearch.matches(rec("+972541234567"), "054-1234567"))
        assertTrue(app.callscribe.ui.CallSearch.matches(rec("x", text = "שלום, רציתי להציע הלוואה"), "הלוואה"))
        assertTrue(app.callscribe.ui.CallSearch.matches(rec("x", summary = "Loan offer"), "loan"))
        assertTrue(!app.callscribe.ui.CallSearch.matches(rec("Dana"), "הלוואה"))
        assertTrue(app.callscribe.ui.CallSearch.matches(rec("Dana"), "  "))
    }
}
