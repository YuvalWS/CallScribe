package app.callscribe.backup

import app.callscribe.data.AppSettings
import app.callscribe.data.PolicyEntity
import app.callscribe.data.RecordingEntity
import app.callscribe.data.Status
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** On-Drive format of the backup. Pure — unit-tested. */
object BackupFormat {
    const val VERSION = 1

    // appProperties keys / values. Short: Drive limits key+value to 124 bytes.
    const val KIND = "cs_kind"
    const val HASH = "cs_hash"
    const val SUM = "cs_sum"
    const val KIND_ROOT = "root"
    const val KIND_CALLS = "calls"
    const val KIND_AUDIO = "audio"
    const val KIND_CALL = "call"
    const val KIND_RECORDING = "recording"
    const val KIND_POLICIES = "policies"
    const val KIND_SETTINGS = "settings"
    const val KIND_MANIFEST = "manifest"

    const val ROOT_NAME = "CallScribe Backup"
    const val CALLS_NAME = "Calls"
    const val AUDIO_NAME = "Recordings"

    val json = Json { encodeDefaults = true; ignoreUnknownKeys = true; explicitNulls = false }

    @Serializable
    data class Manifest(val format: Int = VERSION, val app: String = "CallScribe", val backedUpAtSec: Long, val calls: Int, val recordings: Int)

    /** Device-specific fields are blanked so the same call backs up identically from any phone. */
    fun normalize(r: RecordingEntity) = r.copy(id = 0, mediaId = 0, uri = "", fileMissing = false, updatedAt = 0)

    /** The cloud copy when its text is past cloud retention: metadata only. */
    fun withoutText(r: RecordingEntity) = r.copy(
        transcriptText = null, transcriptJson = null, analysisJson = null, summary = null, verdict = null,
        spamConfidence = null, category = null, callerIntent = null,
        status = if (r.status == Status.FAILED) r.status else Status.NOT_TRANSCRIBED,
    )

    fun encodeCall(r: RecordingEntity): ByteArray = json.encodeToString(RecordingEntity.serializer(), normalize(r)).toByteArray()
    fun decodeCall(bytes: ByteArray): RecordingEntity = json.decodeFromString(RecordingEntity.serializer(), bytes.decodeToString())

    fun encodePolicies(p: List<PolicyEntity>): ByteArray =
        json.encodeToString(ListSerializer(PolicyEntity.serializer()), p.sortedBy { it.id }).toByteArray()
    fun decodePolicies(bytes: ByteArray): List<PolicyEntity> =
        json.decodeFromString(ListSerializer(PolicyEntity.serializer()), bytes.decodeToString())

    fun encodeSettings(s: AppSettings): ByteArray = json.encodeToString(AppSettings.serializer(), s).toByteArray()
    fun decodeSettings(bytes: ByteArray): AppSettings = json.decodeFromString(AppSettings.serializer(), bytes.decodeToString())

    fun encodeManifest(m: Manifest): ByteArray = json.encodeToString(Manifest.serializer(), m).toByteArray()

    /** Content fingerprint stored in appProperties: upload only when it changes. */
    fun sum(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }.take(32)

    private val nameTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm")

    /** Human-friendly: "2026-11-10 14-03 Dana 1a2b3c4d.json". Identity is the cs_hash property, not the name. */
    fun callFileName(r: RecordingEntity, zone: ZoneId): String {
        val time = nameTime.format(Instant.ofEpochSecond(r.callTimeSec).atZone(zone))
        val who = safe(r.counterparty ?: r.samsungLabel ?: "Unknown")
        return "$time $who ${r.contentHash.take(8)}.json"
    }

    fun audioFileName(r: RecordingEntity) = safe(r.displayName).ifBlank { r.contentHash.take(16) + ".m4a" }

    fun audioMime(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
        "m4a", "mp4", "aac" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "amr" -> "audio/amr"
        "3gp" -> "audio/3gpp"
        "wav" -> "audio/wav"
        "ogg", "opus" -> "audio/ogg"
        else -> "application/octet-stream"
    }

    private fun safe(s: String) = s.replace(Regex("""[\\/:*?"<>|\n\r\t]"""), "_").trim().take(80)

    /**
     * Restore merge by content hash. Unknown call → inserted as "audio not on this phone" until a scan re-links it.
     * Known call → only fills in transcript / analysis the phone doesn't have; never overwrites local results.
     * Returns null when nothing changes.
     */
    fun mergeRestored(local: RecordingEntity?, cloud: RecordingEntity, nowMs: Long): RecordingEntity? {
        if (local == null) {
            return cloud.copy(id = 0, mediaId = -1, uri = "", fileMissing = true, updatedAt = nowMs)
        }
        var m = local
        val takeText = local.transcriptText == null && cloud.transcriptText != null
        val takeAnalysis = local.summary == null && local.analysisJson == null && cloud.summary != null
        if (takeText) m = m.copy(
            transcriptText = cloud.transcriptText, transcriptJson = cloud.transcriptJson, transcriberId = cloud.transcriberId,
            transcriptionCostUsd = cloud.transcriptionCostUsd, transcriptionGpuMs = cloud.transcriptionGpuMs,
            textDeletedAt = null,
        )
        if (takeAnalysis) m = m.copy(
            analyzerId = cloud.analyzerId, analysisJson = cloud.analysisJson, summary = cloud.summary, verdict = cloud.verdict,
            spamConfidence = cloud.spamConfidence, category = cloud.category, callerIntent = cloud.callerIntent,
            analysisCostUsd = cloud.analysisCostUsd, analysisInputTokens = cloud.analysisInputTokens,
            analysisOutputTokens = cloud.analysisOutputTokens,
        )
        if (!takeText && !takeAnalysis) return null
        val status = when {
            m.summary != null -> Status.ANALYZED
            m.transcriptText != null -> Status.TRANSCRIBED
            else -> m.status
        }
        return m.copy(status = status, error = null, updatedAt = nowMs)
    }
}
