package app.callscribe.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

enum class LabelKind { CONTACT, UNKNOWN_NUMBER, PRIVATE, SPAM, FRAUD, OTHER_LABEL }

enum class Status {
    NOT_TRANSCRIBED, SKIPPED_BY_RULE, QUEUED, TRANSCRIBING, TRANSCRIBED, ANALYZING, ANALYZED, FAILED
}

@Serializable
@Entity(
    tableName = "recordings",
    indices = [Index(value = ["contentHash"], unique = true), Index("mediaId")],
)
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** SHA-256 of the file — the real identity. */
    val contentHash: String,
    /** MediaStore _ID — lookup hint only, may change after restore/rename. */
    val mediaId: Long,
    val uri: String,
    val displayName: String,
    val relativePath: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val dateAddedSec: Long,
    val dateModifiedSec: Long,
    val fileMissing: Boolean = false,

    val counterparty: String?,
    val samsungLabel: String?,
    val labelKind: LabelKind,
    /** Call start time parsed from the file name (epoch seconds), falls back to dateAdded. */
    val callTimeSec: Long,

    val status: Status = Status.NOT_TRANSCRIBED,
    val error: String? = null,

    val transcriptText: String? = null,
    /** Segments as JSON array of [app.callscribe.providers.Segment]. */
    val transcriptJson: String? = null,
    val transcriberId: String? = null,
    val providerJobId: String? = null,
    val transcriptionCostUsd: Double? = null,
    val transcriptionGpuMs: Long? = null,

    val analyzerId: String? = null,
    val analysisJson: String? = null,
    val summary: String? = null,
    val verdict: String? = null,
    val spamConfidence: Double? = null,
    val category: String? = null,
    val callerIntent: String? = null,
    val analysisCostUsd: Double? = null,
    val analysisInputTokens: Long? = null,
    val analysisOutputTokens: Long? = null,
    /** Who the caller is to the user (closed list); null = not tagged yet. Kept when text retention expires. */
    val relationship: Relationship? = null,
    /** True when the user picked [relationship]; re-analysis then leaves it alone. */
    val relationshipManual: Boolean = false,

    val exportedAt: Long? = null,
    /** Set when the retention policy moved the audio to the trash. */
    val audioDeletedAt: Long? = null,
    /** Set when the retention policy deleted transcript + analysis. */
    val textDeletedAt: Long? = null,
    /** User flag: retention never touches this call (audio or text). */
    val keepForever: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val totalCostUsd: Double get() = (transcriptionCostUsd ?: 0.0) + (analysisCostUsd ?: 0.0)
}

/** What a policy applies to. Precedence when several match a call: CONTACT > CONTACT_GROUP > TAG > CALLER_TYPE (> default). */
enum class PolicyTarget { CONTACT, CONTACT_GROUP, TAG, CALLER_TYPE }

/**
 * Caller types for policies: a simplification of Samsung's labels ([LabelKind], still shown in the list).
 * Spam and fraud are one type; Samsung's other labels (telemarketing, business, ...) count as unknown numbers.
 */
enum class CallerType {
    CONTACT, UNKNOWN_NUMBER, PRIVATE, SPAM_FRAUD;

    companion object {
        fun of(kind: LabelKind) = when (kind) {
            LabelKind.CONTACT -> CONTACT
            LabelKind.UNKNOWN_NUMBER, LabelKind.OTHER_LABEL -> UNKNOWN_NUMBER
            LabelKind.PRIVATE -> PRIVATE
            LabelKind.SPAM, LabelKind.FRAUD -> SPAM_FRAUD
        }
    }
}

/** Tag keys usable as policy targets — the same closed list as [Relationship] (single source of truth). */
object RelationshipTags {
    val ALL: List<String> get() = Relationship.entries.map { it.key }
    fun label(tag: String) = Relationship.entries.firstOrNull { it.key == tag }?.en ?: tag
}

/**
 * What to do with calls of one target: a caller type, a contact group, an AI relationship tag, or one contact.
 * Exactly one target field is set. The most specific matching policy wins as a whole (see docs/plans/policies.md).
 * Retention values are days; [FOREVER] = keep forever; cloud may also be [SAME_AS_LOCAL].
 */
@Serializable
@Entity(tableName = "policies")
data class PolicyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Caller-type target. (Column kept from v2, when it held a [LabelKind].) */
    @ColumnInfo(name = "groupKind") val callerType: CallerType? = null,
    /** Contact target: name and/or comma-separated normalized numbers (last 9 digits). */
    val displayName: String = "",
    val numbers: String = "",
    val transcribe: Boolean,
    val summarize: Boolean,
    val audioRetentionDays: Int = FOREVER,
    val textRetentionDays: Int = FOREVER,
    /** Tag target, one of [RelationshipTags.ALL]. */
    val tag: String? = null,
    /** Retention of the Drive backup copy (audio and text). Used once Drive backup exists. */
    @ColumnInfo(defaultValue = "-2") val cloudRetentionDays: Int = SAME_AS_LOCAL,
    /** Contact-group target: the group's title in the phone's contacts (any account). */
    val contactGroup: String? = null,
    /** Order among contact-group policies: lower wins when a contact is in several groups. */
    @ColumnInfo(defaultValue = "0") val priority: Int = 0,
    /**
     * Retention applies only to calls from this time on (epoch seconds; 0 = all calls). Set when the user picks
     * "Only calls from now on" so a stricter policy doesn't delete calls that were already on the phone.
     */
    @ColumnInfo(defaultValue = "0") val appliesFromSec: Long = 0,
) {
    val target: PolicyTarget get() = when {
        callerType != null -> PolicyTarget.CALLER_TYPE
        contactGroup != null -> PolicyTarget.CONTACT_GROUP
        tag != null -> PolicyTarget.TAG
        else -> PolicyTarget.CONTACT
    }

    companion object {
        const val FOREVER = -1
        const val SAME_AS_LOCAL = -2

        fun defaultFor(type: CallerType) = PolicyEntity(
            callerType = type,
            transcribe = type != CallerType.CONTACT,
            summarize = type != CallerType.CONTACT,
        )
    }
}
