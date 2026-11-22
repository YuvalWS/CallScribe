package app.callscribe.rules

import app.callscribe.data.ContactRuleEntity
import app.callscribe.data.LabelKind
import app.callscribe.data.RuleMode
import app.callscribe.detect.ParsedName

enum class AutoMode { OFF, ALL, CUSTOM }

data class AutoRules(
    val mode: AutoMode = AutoMode.CUSTOM,
    val spam: Boolean = true,
    val fraud: Boolean = true,
    val otherLabels: Boolean = true,
    val unknownNumbers: Boolean = true,
    val privateNumbers: Boolean = true,
    val contacts: Boolean = false,
)

enum class Decision { AUTO, SKIP }

object PhoneNumbers {
    /** Digits only, last 9 digits, so "+972 54-123 4567" and "054-1234567" compare equal. Null if < 7 digits. */
    fun normalize(raw: String): String? {
        val digits = raw.filter { it.isDigit() }
        if (digits.length < 7) return null
        return digits.takeLast(9)
    }
}

object Names {
    fun normalize(raw: String): String =
        raw.replace('_', ' ').replace(Regex("""\s+"""), " ").trim().lowercase()
}

/**
 * Decides whether a recording is processed automatically. Exclude lists win over everything,
 * include lists win over the mode/flags. The same matcher is meant to be reused for retention rules.
 */
object RuleEngine {

    fun matches(rule: ContactRuleEntity, parsed: ParsedName): Boolean {
        val cp = parsed.counterparty ?: return false
        if (Names.normalize(rule.displayName) == Names.normalize(cp)) return true
        val n = PhoneNumbers.normalize(cp) ?: return false
        return rule.numbers.split(',').any { it.isNotBlank() && it == n }
    }

    fun decide(parsed: ParsedName, rules: AutoRules, contactRules: List<ContactRuleEntity>): Decision {
        val matched = contactRules.filter { matches(it, parsed) }
        if (matched.any { it.mode == RuleMode.EXCLUDE }) return Decision.SKIP
        if (rules.mode == AutoMode.OFF) return Decision.SKIP
        if (matched.any { it.mode == RuleMode.INCLUDE }) return Decision.AUTO
        if (rules.mode == AutoMode.ALL) return Decision.AUTO
        val on = when (parsed.labelKind) {
            LabelKind.SPAM -> rules.spam
            LabelKind.FRAUD -> rules.fraud
            LabelKind.OTHER_LABEL -> rules.otherLabels
            LabelKind.UNKNOWN_NUMBER -> rules.unknownNumbers
            LabelKind.PRIVATE -> rules.privateNumbers
            LabelKind.CONTACT -> rules.contacts
        }
        return if (on) Decision.AUTO else Decision.SKIP
    }
}
