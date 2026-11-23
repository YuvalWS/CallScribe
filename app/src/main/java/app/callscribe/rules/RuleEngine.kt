package app.callscribe.rules

import app.callscribe.data.CallerType
import app.callscribe.data.LabelKind
import app.callscribe.data.PolicyEntity
import app.callscribe.data.PolicyTarget
import app.callscribe.data.RecordingEntity
import app.callscribe.data.RelationshipTags

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
 * Hooks for fields other branches add to [RecordingEntity]. Change these one-liners when they land:
 * - keep-forever branch: `keepForever` column → return it here.
 * - AI relationship tags branch: tag column → return it here.
 */
fun RecordingEntity.keptForever(): Boolean = keepForever
fun RecordingEntity.aiTag(): String? = relationship?.key

/**
 * Which contact groups each contact is in, keyed by normalized name and by normalized number.
 * Built from the phone's contacts by [app.callscribe.detect.ContactGroupsReader]; empty without the permission.
 */
class ContactGroupIndex(
    private val byName: Map<String, Set<String>> = emptyMap(),
    private val byNumber: Map<String, Set<String>> = emptyMap(),
) {
    /** Samsung puts the contact's name in the file name, or the number when it isn't a contact. */
    fun groupsOf(counterparty: String?): Set<String> {
        if (counterparty == null) return emptySet()
        byName[Names.normalize(counterparty)]?.let { return it }
        val n = PhoneNumbers.normalize(counterparty) ?: return emptySet()
        return byNumber[n].orEmpty()
    }

    val isEmpty get() = byName.isEmpty() && byNumber.isEmpty()

    companion object {
        val EMPTY = ContactGroupIndex()

        /** One entry per contact: display name, its numbers and its group titles. */
        fun of(contacts: List<Triple<String, List<String>, Set<String>>>): ContactGroupIndex {
            val byName = mutableMapOf<String, MutableSet<String>>()
            val byNumber = mutableMapOf<String, MutableSet<String>>()
            for ((name, numbers, groups) in contacts) {
                if (groups.isEmpty()) continue
                if (name.isNotBlank()) byName.getOrPut(Names.normalize(name)) { mutableSetOf() } += groups
                numbers.mapNotNull(PhoneNumbers::normalize).forEach { byNumber.getOrPut(it) { mutableSetOf() } += groups }
            }
            return ContactGroupIndex(byName, byNumber)
        }
    }
}

/** Which policy governs a call, and why. [alsoIn] = other matching contact groups with lower priority. */
data class Resolution(val policy: PolicyEntity, val level: Level, val alsoIn: List<String> = emptyList()) {
    enum class Level { CONTACT, CONTACT_GROUP, TAG, CALLER_TYPE, DEFAULT }

    /** Plain-language reason, shown on the call page. */
    fun reason(typeLabel: (CallerType) -> String = { it.name }): String = when (level) {
        Level.CONTACT -> "Contact policy \"${policy.displayName.ifBlank { "…" + policy.numbers }}\" — a contact's own policy beats everything else"
        Level.CONTACT_GROUP -> "Contact group \"${policy.contactGroup}\"" +
            (if (alsoIn.isEmpty()) "" else " (also in ${alsoIn.joinToString()}, lower priority)") +
            " — a group beats tag and caller type"
        Level.TAG -> "Tag policy \"${RelationshipTags.label(policy.tag.orEmpty())}\" — a tag beats the caller type"
        Level.CALLER_TYPE -> "Caller type \"${policy.callerType?.let(typeLabel)}\" — no contact, group or tag policy matches this call"
        Level.DEFAULT -> "Built-in default — no policy matches this call"
    }
}

/**
 * Picks the policy for a call. Fixed precedence: contact > contact group > tag > caller type > built-in default.
 * The single most specific matching policy wins as a whole — fields are never merged across policies.
 * Among contact groups, the lowest [PolicyEntity.priority] wins.
 * The same resolution drives automatic processing and retention.
 */
object PolicyResolver {

    fun matches(policy: PolicyEntity, counterparty: String?): Boolean {
        if (policy.target != PolicyTarget.CONTACT || counterparty == null) return false
        if (policy.displayName.isNotBlank() && Names.normalize(policy.displayName) == Names.normalize(counterparty)) return true
        val n = PhoneNumbers.normalize(counterparty) ?: return false
        return policy.numbers.split(',').any { it.isNotBlank() && it == n }
    }

    fun explain(
        kind: LabelKind, counterparty: String?, tag: String?, policies: List<PolicyEntity>, groups: Set<String> = emptySet(),
    ): Resolution {
        policies.firstOrNull { matches(it, counterparty) }?.let { return Resolution(it, Resolution.Level.CONTACT) }
        val groupHits = policies.filter { it.target == PolicyTarget.CONTACT_GROUP && it.contactGroup in groups }
            .sortedWith(compareBy({ it.priority }, { it.id }))
        if (groupHits.isNotEmpty()) {
            return Resolution(groupHits[0], Resolution.Level.CONTACT_GROUP, groupHits.drop(1).mapNotNull { it.contactGroup })
        }
        if (tag != null) policies.firstOrNull { it.target == PolicyTarget.TAG && it.tag == tag }
            ?.let { return Resolution(it, Resolution.Level.TAG) }
        val type = CallerType.of(kind)
        policies.firstOrNull { it.callerType == type }?.let { return Resolution(it, Resolution.Level.CALLER_TYPE) }
        return Resolution(PolicyEntity.defaultFor(type), Resolution.Level.DEFAULT)
    }

    fun resolve(
        kind: LabelKind, counterparty: String?, policies: List<PolicyEntity>, tag: String? = null, groups: Set<String> = emptySet(),
    ): PolicyEntity = explain(kind, counterparty, tag, policies, groups).policy

    /** For retention and display: uses the AI tag when the call has one. */
    fun explain(r: RecordingEntity, policies: List<PolicyEntity>, groups: ContactGroupIndex, tag: String? = r.aiTag()) =
        explain(r.labelKind, r.counterparty, tag, policies, groups.groupsOf(r.counterparty))

    /** For auto-processing a new call: the tag is not known before analysis, so it is ignored. */
    fun forProcessing(r: RecordingEntity, policies: List<PolicyEntity>, groups: ContactGroupIndex) =
        explain(r, policies, groups, tag = null).policy
}

/** Cloud (Drive backup) retention. "Same as local" = the cloud copy goes once the phone copy has actually been deleted. */
object CloudRetention {
    const val FOREVER = PolicyEntity.FOREVER
    const val SAME_AS_LOCAL = PolicyEntity.SAME_AS_LOCAL
    /** Approximate cloud period in days (for editor warnings): "same as local" uses the local period. */
    fun audioDays(p: PolicyEntity) = if (p.cloudRetentionDays == SAME_AS_LOCAL) p.audioRetentionDays else p.cloudRetentionDays
    fun textDays(p: PolicyEntity) = if (p.cloudRetentionDays == SAME_AS_LOCAL) p.textRetentionDays else p.cloudRetentionDays
}

data class RetentionPlan(
    val trashAudio: List<RecordingEntity>,
    val deleteText: List<RecordingEntity>,
    /** Drive backup audio past its cloud period: trash it on Drive and never upload it again. */
    val cloudAudio: List<RecordingEntity> = emptyList(),
    /** Drive backup text past its cloud period: the backed-up call keeps its metadata only. */
    val cloudText: List<RecordingEntity> = emptyList(),
) {
    val audioBytes get() = trashAudio.sumOf { it.sizeBytes }
    /** Local work only: the cloud lists are acted on by the Drive backup. */
    val isEmpty get() = trashAudio.isEmpty() && deleteText.isEmpty()
}

/** Which recordings are past their retention period. Pure — unit-tested. */
object Retention {
    private const val DAY = 86_400L

    private fun due(days: Int, ageSec: Long) = days >= 0 && ageSec > days * DAY

    fun plan(
        rows: List<RecordingEntity>,
        policies: List<PolicyEntity>,
        nowSec: Long,
        keptForever: (RecordingEntity) -> Boolean = { it.keptForever() },
        groups: ContactGroupIndex = ContactGroupIndex.EMPTY,
        tagOf: (RecordingEntity) -> String? = { it.aiTag() },
        cloudDays: (PolicyEntity) -> Int = { it.cloudRetentionDays },
    ): RetentionPlan {
        val audio = mutableListOf<RecordingEntity>()
        val text = mutableListOf<RecordingEntity>()
        val cloudAudio = mutableListOf<RecordingEntity>()
        val cloudText = mutableListOf<RecordingEntity>()
        for (r in rows) {
            if (keptForever(r)) continue
            val p = PolicyResolver.explain(r, policies, groups, tagOf(r)).policy
            val age = nowSec - r.callTimeSec
            if (due(p.audioRetentionDays, age) && !r.fileMissing && r.audioDeletedAt == null) audio += r
            val hasText = r.transcriptText != null || r.summary != null
            if (due(p.textRetentionDays, age) && hasText) text += r
            when (val c = cloudDays(p)) {
                // "Same as local": the cloud copy goes once the phone copy has actually been deleted by retention.
                CloudRetention.SAME_AS_LOCAL -> {
                    if (r.audioDeletedAt != null) cloudAudio += r
                    if (r.textDeletedAt != null) cloudText += r
                }
                else -> if (due(c, age)) { cloudAudio += r; cloudText += r }
            }
        }
        return RetentionPlan(audio, text, cloudAudio, cloudText)
    }
}

/** Soft warnings for odd but allowed combinations. The editor shows them; nothing is blocked. */
object PolicyChecks {
    fun warnings(p: PolicyEntity): List<String> = buildList {
        val tagPolicy = p.target == PolicyTarget.TAG
        if (!tagPolicy && !p.transcribe && p.audioRetentionDays >= 0) {
            add("New calls aren't transcribed, so recordings will be deleted without a transcript.")
        }
        if (p.textRetentionDays >= 0 && (p.audioRetentionDays < 0 || p.textRetentionDays < p.audioRetentionDays)) {
            add("The transcript is deleted before the recording.")
        }
        val cloudAudio = CloudRetention.audioDays(p)
        if (cloudAudio >= 0 && (p.audioRetentionDays < 0 || cloudAudio < p.audioRetentionDays)) {
            add("The cloud copy expires before the phone copy, so the backup won't cover older recordings.")
        }
    }
}
