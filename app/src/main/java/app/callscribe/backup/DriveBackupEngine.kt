package app.callscribe.backup

import app.callscribe.backup.BackupFormat.HASH
import app.callscribe.backup.BackupFormat.KIND
import app.callscribe.backup.BackupFormat.SUM
import app.callscribe.data.AppSettings
import app.callscribe.data.PolicyEntity
import app.callscribe.data.RecordingEntity
import app.callscribe.rules.Retention
import app.callscribe.rules.RetentionPlan
import java.io.InputStream
import java.time.ZoneId

/** What the backup reads from the app. */
interface BackupSource {
    suspend fun recordings(): List<RecordingEntity>
    suspend fun policies(): List<PolicyEntity>
    suspend fun settings(): AppSettings
    /** The local audio file, or null if it can't be read (deleted, no permission). */
    fun openAudio(r: RecordingEntity): UploadContent.Stream?
}

/** What a restore writes into the app. */
interface RestoreSink {
    suspend fun byHash(hash: String): RecordingEntity?
    suspend fun insert(r: RecordingEntity)
    suspend fun update(r: RecordingEntity)
    suspend fun replacePolicies(policies: List<PolicyEntity>)
    suspend fun applySettings(cloud: AppSettings)
}

data class BackupReport(
    val callsUploaded: Int = 0,
    val audioUploaded: Int = 0,
    val audioBytes: Long = 0,
    val audioTrashed: Int = 0,
    val callsOnDrive: Int = 0,
    val audioOnDrive: Int = 0,
    val failures: Int = 0,
    val lastError: String? = null,
    /** Stopped early (time budget); the rest goes in a follow-up run. */
    val incomplete: Boolean = false,
) {
    fun line() = buildString {
        append("$callsOnDrive calls, $audioOnDrive recordings on Drive")
        val changes = listOfNotNull(
            "$callsUploaded calls".takeIf { callsUploaded > 0 },
            "$audioUploaded recordings (%.1f MB)".format(audioBytes / 1e6).takeIf { audioUploaded > 0 },
        )
        if (changes.isNotEmpty()) append(" · uploaded ").append(changes.joinToString(", "))
        if (audioTrashed > 0) append(" · $audioTrashed expired recordings removed")
        if (failures > 0) append(" · $failures failed: $lastError")
        if (incomplete) append(" · continuing…")
    }
}

/** This phone, as its backup folder knows it. [id] is random per install, [name] e.g. "Galaxy S20 FE". */
data class BackupDevice(val id: String, val name: String)

/** One phone's backup in the user's Drive. */
data class BackupInfo(val folderId: String, val deviceId: String, val name: String, val lastBackupSec: Long?, val calls: Int?, val recordings: Int?)

data class RestoreReport(val inserted: Int, val merged: Int, val policies: Int, val settings: Boolean, val audioAvailable: Int)

/** A recording on Drive whose audio isn't on this phone. */
data class CloudAudio(val fileId: String, val name: String, val hash: String, val size: Long?)

/**
 * Incremental, stateless backup to a visible "CallScribe Backup/<device name>" folder — one folder per phone, so two
 * phones on one Google account never mix. The Drive listing is compared with the DB on every run (files are matched by
 * their cs_hash / cs_sum app properties). Pure Kotlin — tested with a fake [DriveApi].
 */
class DriveBackupEngine(
    private val api: DriveApi,
    private val zone: ZoneId = ZoneId.systemDefault(),
    /** The pure retention planner; tests inject cloud periods through it. */
    private val planner: (List<RecordingEntity>, List<PolicyEntity>, Long) -> RetentionPlan = { r, p, now -> Retention.plan(r, p, now) },
) {
    /** [root] is this phone's folder (settings, policies, manifest); calls and recordings are its subfolders. */
    data class Folders(val root: String, val calls: String, val audio: String)

    private suspend fun topFolder(create: Boolean): String? =
        api.findFolders(KIND, BackupFormat.KIND_ROOT).firstOrNull()?.id
            ?: if (create) api.createFolder(BackupFormat.ROOT_NAME, null, mapOf(KIND to BackupFormat.KIND_ROOT)).id else null

    private suspend fun subFolders(deviceFolder: String, create: Boolean): Folders? {
        suspend fun sub(kind: String, name: String) = api.findFolders(KIND, kind, deviceFolder).firstOrNull()?.id
            ?: if (create) api.createFolder(name, deviceFolder, mapOf(KIND to kind)).id else null
        return Folders(
            deviceFolder,
            sub(BackupFormat.KIND_CALLS, BackupFormat.CALLS_NAME) ?: return null,
            sub(BackupFormat.KIND_AUDIO, BackupFormat.AUDIO_NAME) ?: return null,
        )
    }

    /** This phone's backup folders, created on first use. */
    suspend fun folders(device: BackupDevice): Folders {
        val top = topFolder(create = true)!!
        val dev = api.findFolders(KIND, BackupFormat.KIND_DEVICE, top).firstOrNull { it.appProperties[BackupFormat.DEVICE] == device.id }?.id
            ?: api.createFolder(
                BackupFormat.deviceFolderName(device.name), top,
                mapOf(KIND to BackupFormat.KIND_DEVICE, BackupFormat.DEVICE to device.id),
            ).id
        return subFolders(dev, create = true)!!
    }

    /** All phones' backups in this Google account, most recent first. */
    suspend fun backups(): List<BackupInfo> {
        val top = topFolder(create = false) ?: return emptyList()
        return api.findFolders(KIND, BackupFormat.KIND_DEVICE, top).map { dev ->
            val manifest = api.list(dev.id).firstOrNull { it.appProperties[KIND] == BackupFormat.KIND_MANIFEST }
                ?.let { runCatching { BackupFormat.decodeManifest(api.download(it.id)) }.getOrNull() }
            BackupInfo(dev.id, dev.appProperties[BackupFormat.DEVICE].orEmpty(), manifest?.deviceName ?: dev.name,
                manifest?.backedUpAtSec, manifest?.calls, manifest?.recordings)
        }.sortedByDescending { it.lastBackupSec ?: 0 }
    }

    /** [outOfTime] is checked between recordings; a long first audio backup continues in the next run. */
    suspend fun backup(
        device: BackupDevice, source: BackupSource, includeAudio: Boolean, nowSec: Long, outOfTime: () -> Boolean = { false },
    ): BackupReport {
        val f = folders(device)
        val rows = source.recordings()
        val policies = source.policies()
        val plan = planner(rows, policies, nowSec)
        val cloudAudioDue = plan.cloudAudio.mapTo(HashSet()) { it.contentHash }
        val cloudTextDue = plan.cloudText.mapTo(HashSet()) { it.contentHash }

        var failures = 0
        var lastError: String? = null
        suspend fun attempt(block: suspend () -> Unit): Boolean = try {
            block(); true
        } catch (e: DriveAuthRequiredException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            failures++; lastError = e.message ?: e.javaClass.simpleName; false
        }

        // Root files: settings, policies (only when changed), manifest.
        val rootFiles = api.list(f.root)
        suspend fun putSmall(kind: String, name: String, bytes: ByteArray) {
            val sum = BackupFormat.sum(bytes)
            val existing = rootFiles.firstOrNull { it.appProperties[KIND] == kind }
            if (existing?.appProperties?.get(SUM) == sum) return
            attempt { api.upload(f.root, name, mapOf(KIND to kind, SUM to sum), UploadContent.Bytes(bytes), existing?.id) }
        }
        putSmall(BackupFormat.KIND_SETTINGS, "settings.json", BackupFormat.encodeSettings(source.settings()))
        putSmall(BackupFormat.KIND_POLICIES, "policies.json", BackupFormat.encodePolicies(policies))

        // Calls.
        val callFiles = api.list(f.calls).filter { it.appProperties[KIND] == BackupFormat.KIND_CALL }
            .groupBy { it.appProperties[HASH] }.mapValues { it.value.first() }
        var callsUploaded = 0
        for (r in rows) {
            val copy = if (r.contentHash in cloudTextDue) BackupFormat.withoutText(r) else r
            val bytes = BackupFormat.encodeCall(copy)
            val sum = BackupFormat.sum(bytes)
            val existing = callFiles[r.contentHash]
            if (existing?.appProperties?.get(SUM) == sum) continue
            val props = mapOf(KIND to BackupFormat.KIND_CALL, HASH to r.contentHash, SUM to sum)
            if (attempt { api.upload(f.calls, BackupFormat.callFileName(r, zone), props, UploadContent.Bytes(bytes), existing?.id) }) {
                callsUploaded++
            }
        }
        val callsOnDrive = (callFiles.keys + rows.map { it.contentHash }).size

        // Recordings: trash the expired ones, then upload new ones (opt-in).
        val audioFiles = api.list(f.audio).filter { it.appProperties[KIND] == BackupFormat.KIND_RECORDING }
            .groupBy { it.appProperties[HASH] }.toMutableMap()
        var trashed = 0
        for (hash in cloudAudioDue) {
            val files = audioFiles[hash] ?: continue
            var all = true
            for (file in files) if (!attempt { api.trash(file.id) }) all = false
            if (all) { trashed++; audioFiles.remove(hash) }
        }
        var audioUploaded = 0
        var audioBytes = 0L
        var incomplete = false
        if (includeAudio) {
            for (r in rows) {
                if (outOfTime()) { incomplete = true; break }
                if (r.contentHash in cloudAudioDue || r.contentHash in audioFiles) continue
                if (r.fileMissing || r.audioDeletedAt != null) continue
                val content = source.openAudio(r) ?: continue
                val name = BackupFormat.audioFileName(r)
                val props = mapOf(KIND to BackupFormat.KIND_RECORDING, HASH to r.contentHash)
                if (attempt { api.upload(f.audio, name, props, content) }) {
                    audioUploaded++; audioBytes += content.length
                    audioFiles[r.contentHash] = listOf(DriveFile("", name, props))
                }
            }
        }

        val report = BackupReport(callsUploaded, audioUploaded, audioBytes, trashed, callsOnDrive, audioFiles.size, failures, lastError)
        val manifest = BackupFormat.Manifest(deviceName = device.name, backedUpAtSec = nowSec, calls = callsOnDrive, recordings = audioFiles.size)
        val existing = rootFiles.firstOrNull { it.appProperties[KIND] == BackupFormat.KIND_MANIFEST }
        attempt {
            api.upload(f.root, "manifest.json", mapOf(KIND to BackupFormat.KIND_MANIFEST), UploadContent.Bytes(BackupFormat.encodeManifest(manifest)), existing?.id)
        }
        return report.copy(failures = failures, lastError = lastError, incomplete = incomplete)
    }

    /** Restores settings, policies and calls; audio is listed in [missingAudio] for an optional download. */
    suspend fun restore(sink: RestoreSink, backup: BackupInfo, nowMs: Long): RestoreReport {
        val f = subFolders(backup.folderId, create = false) ?: throw IllegalStateException("The backup of ${backup.name} is incomplete")
        val rootFiles = api.list(f.root)
        var settings = false
        rootFiles.firstOrNull { it.appProperties[KIND] == BackupFormat.KIND_SETTINGS }?.let {
            sink.applySettings(BackupFormat.decodeSettings(api.download(it.id))); settings = true
        }
        var policyCount = 0
        rootFiles.firstOrNull { it.appProperties[KIND] == BackupFormat.KIND_POLICIES }?.let {
            val p = BackupFormat.decodePolicies(api.download(it.id)).map { p -> p.copy(id = 0) }
            if (p.isNotEmpty()) { sink.replacePolicies(p); policyCount = p.size }
        }
        var inserted = 0
        var merged = 0
        for (file in api.list(f.calls).filter { it.appProperties[KIND] == BackupFormat.KIND_CALL }) {
            val cloud = runCatching { BackupFormat.decodeCall(api.download(file.id)) }.getOrNull() ?: continue
            val local = sink.byHash(cloud.contentHash)
            val m = BackupFormat.mergeRestored(local, cloud, nowMs) ?: continue
            if (local == null) { sink.insert(m); inserted++ } else { sink.update(m); merged++ }
        }
        val audio = api.list(f.audio).count { it.appProperties[KIND] == BackupFormat.KIND_RECORDING }
        return RestoreReport(inserted, merged, policyCount, settings, audio)
    }

    /** Recordings on Drive for calls whose audio isn't on this phone. */
    suspend fun missingAudio(rows: List<RecordingEntity>, backup: BackupInfo): List<CloudAudio> {
        val f = subFolders(backup.folderId, create = false) ?: return emptyList()
        val missing = rows.filter { it.fileMissing }.mapTo(HashSet()) { it.contentHash }
        return api.list(f.audio)
            .filter { it.appProperties[KIND] == BackupFormat.KIND_RECORDING && it.appProperties[HASH] in missing }
            .distinctBy { it.appProperties[HASH] }
            .map { CloudAudio(it.id, it.name, it.appProperties[HASH]!!, it.size) }
    }

    suspend fun download(a: CloudAudio, sink: (InputStream) -> Unit) = api.downloadTo(a.fileId, sink)

    companion object {
        /** Settings from the cloud, keeping what belongs to this phone. */
        fun restoredSettings(local: AppSettings, cloud: AppSettings) =
            cloud.copy(autoProcessFromSec = local.autoProcessFromSec, runpodBaseUrl = local.runpodBaseUrl)
    }
}
