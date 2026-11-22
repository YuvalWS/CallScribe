package app.callscribe.data

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

    val exportedAt: Long? = null,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val totalCostUsd: Double get() = (transcriptionCostUsd ?: 0.0) + (analysisCostUsd ?: 0.0)
}

enum class RuleList { AUTO_PROCESS }
enum class RuleMode { INCLUDE, EXCLUDE }

/** A contact on an include / exclude list. [numbers] are normalized (see PhoneNumbers). */
@Entity(tableName = "contact_rules")
data class ContactRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val list: RuleList,
    val mode: RuleMode,
    val displayName: String,
    /** Comma-separated normalized numbers (last 9 digits). */
    val numbers: String,
)
