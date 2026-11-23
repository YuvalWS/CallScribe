package app.callscribe.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class LabelKind { CONTACT, UNKNOWN_NUMBER, PRIVATE, SPAM, FRAUD, OTHER_LABEL }

enum class Status {
    NOT_TRANSCRIBED, SKIPPED_BY_RULE, QUEUED, TRANSCRIBING, TRANSCRIBED, ANALYZING, ANALYZED, FAILED
}

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

/** What a policy applies to. Precedence when several match a call: CONTACT > TAG > CALLER_TYPE (> built-in default). */
enum class PolicyTarget { CONTACT, TAG, CALLER_TYPE }

/** Tag keys usable as policy targets — the same closed list as [Relationship] (single source of truth). */
object RelationshipTags {
    val ALL: List<String> get() = Relationship.entries.map { it.key }
    fun label(tag: String) = Relationship.entries.firstOrNull { it.key == tag }?.en ?: tag
}

/**
 * What to do with calls of one target: a caller type, an AI relationship tag, or one contact.
 * Exactly one target field is set. The most specific matching policy wins as a whole (see docs/plans/policies.md).
 * Retention values are days; [FOREVER] = keep forever; cloud may also be [SAME_AS_LOCAL].
 */
@Entity(tableName = "policies")
data class PolicyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Caller-type target. */
    val groupKind: LabelKind? = null,
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
) {
    val target: PolicyTarget get() = when {
        groupKind != null -> PolicyTarget.CALLER_TYPE
        tag != null -> PolicyTarget.TAG
        else -> PolicyTarget.CONTACT
    }
    val isGroup get() = groupKind != null

    companion object {
        const val FOREVER = -1
        const val SAME_AS_LOCAL = -2

        fun defaultFor(kind: LabelKind) = PolicyEntity(
            groupKind = kind,
            transcribe = kind != LabelKind.CONTACT,
            summarize = kind != LabelKind.CONTACT,
        )
    }
}
