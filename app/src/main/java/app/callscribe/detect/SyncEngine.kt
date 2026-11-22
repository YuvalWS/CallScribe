package app.callscribe.detect

import app.callscribe.data.RecordingEntity
import java.time.ZoneId

/** One audio file as reported by MediaStore. */
data class MediaFile(
    val mediaId: Long,
    val uri: String,
    val displayName: String,
    val relativePath: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val dateAddedSec: Long,
    val dateModifiedSec: Long,
)

/** The subset of the DAO the sync needs — lets tests use an in-memory fake. */
interface RecordingStore {
    suspend fun byMediaId(mediaId: Long): RecordingEntity?
    suspend fun byHash(hash: String): RecordingEntity?
    suspend fun insert(r: RecordingEntity): Long
    suspend fun update(r: RecordingEntity)
    suspend fun all(): List<RecordingEntity>
}

data class SyncResult(val newRows: List<RecordingEntity>, val relinked: Int, val missing: Int)

/**
 * Reconciles the files currently in MediaStore with the DB.
 * Identity is the content hash; the MediaStore id is only used to skip re-hashing unchanged files.
 */
class SyncEngine(
    private val store: RecordingStore,
    private val hasher: suspend (MediaFile) -> String,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    suspend fun sync(files: List<MediaFile>): SyncResult {
        val newRows = mutableListOf<RecordingEntity>()
        val seenIds = mutableSetOf<Long>()
        var relinked = 0

        for (f in files) {
            val byId = store.byMediaId(f.mediaId)
            if (byId != null && byId.sizeBytes == f.sizeBytes && byId.dateModifiedSec == f.dateModifiedSec) {
                seenIds += byId.id
                if (byId.fileMissing || byId.displayName != f.displayName) {
                    store.update(byId.copy(fileMissing = false, displayName = f.displayName, uri = f.uri, relativePath = f.relativePath))
                }
                continue
            }
            val hash = runCatching { hasher(f) }.getOrNull() ?: continue
            val byHash = store.byHash(hash)
            if (byHash != null) {
                // Same audio, new location / id: keep transcript and analysis.
                store.update(
                    byHash.copy(
                        mediaId = f.mediaId, uri = f.uri, displayName = f.displayName, relativePath = f.relativePath,
                        sizeBytes = f.sizeBytes, dateModifiedSec = f.dateModifiedSec, fileMissing = false,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                seenIds += byHash.id
                relinked++
                continue
            }
            val parsed = RecordingNameParser.parse(f.displayName)
            val callTime = parsed.timestamp?.atZone(zone)?.toEpochSecond() ?: f.dateAddedSec
            val row = RecordingEntity(
                contentHash = hash, mediaId = f.mediaId, uri = f.uri, displayName = f.displayName,
                relativePath = f.relativePath, sizeBytes = f.sizeBytes, durationMs = f.durationMs,
                dateAddedSec = f.dateAddedSec, dateModifiedSec = f.dateModifiedSec,
                counterparty = parsed.counterparty, samsungLabel = parsed.samsungLabel,
                labelKind = parsed.labelKind, callTimeSec = callTime,
            )
            val id = store.insert(row)
            if (id > 0) {
                newRows += row.copy(id = id)
                seenIds += id
            }
        }

        var missing = 0
        for (r in store.all()) {
            if (r.id !in seenIds && !r.fileMissing) {
                store.update(r.copy(fileMissing = true))
                missing++
            }
        }
        return SyncResult(newRows, relinked, missing)
    }
}
