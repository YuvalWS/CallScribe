package app.callscribe.rules

import app.callscribe.data.LabelKind
import app.callscribe.data.PolicyEntity
import app.callscribe.data.RecordingEntity

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
 * Picks the policy for a call: a matching contact policy wins over the group policy of the caller type.
 * The same resolution drives automatic processing and retention.
 */
object PolicyResolver {

    fun matches(policy: PolicyEntity, counterparty: String?): Boolean {
        if (policy.isGroup || counterparty == null) return false
        if (policy.displayName.isNotBlank() && Names.normalize(policy.displayName) == Names.normalize(counterparty)) return true
        val n = PhoneNumbers.normalize(counterparty) ?: return false
        return policy.numbers.split(',').any { it.isNotBlank() && it == n }
    }

    fun resolve(kind: LabelKind, counterparty: String?, policies: List<PolicyEntity>): PolicyEntity =
        policies.firstOrNull { matches(it, counterparty) }
            ?: policies.firstOrNull { it.groupKind == kind }
            ?: PolicyEntity.defaultFor(kind)

    fun resolve(r: RecordingEntity, policies: List<PolicyEntity>) = resolve(r.labelKind, r.counterparty, policies)
}

data class RetentionPlan(val trashAudio: List<RecordingEntity>, val deleteText: List<RecordingEntity>) {
    val audioBytes get() = trashAudio.sumOf { it.sizeBytes }
    val isEmpty get() = trashAudio.isEmpty() && deleteText.isEmpty()
}

/** Which recordings are past their retention period. Pure — unit-tested. */
object Retention {
    private const val DAY = 86_400L

    fun plan(rows: List<RecordingEntity>, policies: List<PolicyEntity>, nowSec: Long): RetentionPlan {
        val audio = mutableListOf<RecordingEntity>()
        val text = mutableListOf<RecordingEntity>()
        for (r in rows) {
            val p = PolicyResolver.resolve(r, policies)
            val age = nowSec - r.callTimeSec
            if (p.audioRetentionDays >= 0 && !r.fileMissing && r.audioDeletedAt == null && age > p.audioRetentionDays * DAY) {
                audio += r
            }
            val hasText = r.transcriptText != null || r.summary != null
            if (p.textRetentionDays >= 0 && hasText && age > p.textRetentionDays * DAY) text += r
        }
        return RetentionPlan(audio, text)
    }
}
