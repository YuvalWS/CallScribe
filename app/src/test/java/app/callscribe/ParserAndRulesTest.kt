package app.callscribe

import app.callscribe.data.ContactRuleEntity
import app.callscribe.data.LabelKind
import app.callscribe.data.RuleList
import app.callscribe.data.RuleMode
import app.callscribe.detect.RecordingNameParser
import app.callscribe.rules.AutoMode
import app.callscribe.rules.AutoRules
import app.callscribe.rules.Decision
import app.callscribe.rules.PhoneNumbers
import app.callscribe.rules.RuleEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

class RulesTest {
    private fun parsed(name: String) = RecordingNameParser.parse("Call recording ${name}_250101_101010.m4a")
    private fun rule(name: String, mode: RuleMode, number: String = "") =
        ContactRuleEntity(list = RuleList.AUTO_PROCESS, mode = mode, displayName = name, numbers = PhoneNumbers.normalize(number).orEmpty())

    @Test fun normalizeNumbers() {
        assertEquals(PhoneNumbers.normalize("+972 54-123-4567"), PhoneNumbers.normalize("054-1234567"))
        assertNull(PhoneNumbers.normalize("123"))
    }

    @Test fun customModeSkipsContactsByDefault() {
        assertEquals(Decision.SKIP, RuleEngine.decide(parsed("Mom"), AutoRules(), emptyList()))
        assertEquals(Decision.AUTO, RuleEngine.decide(parsed("0541234567"), AutoRules(), emptyList()))
    }

    @Test fun offModeSkipsEverythingButIncludeDoesNotOverrideOff() {
        val off = AutoRules(mode = AutoMode.OFF)
        assertEquals(Decision.SKIP, RuleEngine.decide(parsed("0541234567"), off, emptyList()))
        assertEquals(Decision.SKIP, RuleEngine.decide(parsed("Mom"), off, listOf(rule("Mom", RuleMode.INCLUDE))))
    }

    @Test fun excludeWinsEvenInAllMode() {
        val all = AutoRules(mode = AutoMode.ALL)
        assertEquals(Decision.AUTO, RuleEngine.decide(parsed("Mom"), all, emptyList()))
        assertEquals(Decision.SKIP, RuleEngine.decide(parsed("Mom"), all, listOf(rule("mom", RuleMode.EXCLUDE))))
    }

    @Test fun includeContactInCustomMode() {
        assertEquals(Decision.AUTO, RuleEngine.decide(parsed("Mom"), AutoRules(), listOf(rule("Mom", RuleMode.INCLUDE))))
    }

    @Test fun numberMatchesAcrossFormats() {
        val r = rule("Plumber", RuleMode.EXCLUDE, "+972 54-123-4567")
        assertEquals(Decision.SKIP, RuleEngine.decide(parsed("0541234567"), AutoRules(mode = AutoMode.ALL), listOf(r)))
    }

    @Test fun excludeBeatsInclude() {
        val rules = listOf(rule("Mom", RuleMode.INCLUDE), rule("Mom", RuleMode.EXCLUDE))
        assertEquals(Decision.SKIP, RuleEngine.decide(parsed("Mom"), AutoRules(mode = AutoMode.ALL), rules))
    }
}
