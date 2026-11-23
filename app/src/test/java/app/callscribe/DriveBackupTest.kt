package app.callscribe

import app.callscribe.backup.BackupDevice
import app.callscribe.backup.BackupFormat
import app.callscribe.backup.BackupSource
import app.callscribe.backup.DriveApi
import app.callscribe.backup.DriveAuthRequiredException
import app.callscribe.backup.DriveBackupEngine
import app.callscribe.backup.DriveFile
import app.callscribe.backup.RestoreSink
import app.callscribe.backup.UploadContent
import app.callscribe.data.AppSettings
import app.callscribe.data.CallerType
import app.callscribe.data.LabelKind
import app.callscribe.data.PolicyEntity
import app.callscribe.data.RecordingEntity
import app.callscribe.data.Status
import app.callscribe.detect.MediaFile
import app.callscribe.detect.RecordingStore
import app.callscribe.detect.SyncEngine
import app.callscribe.rules.CloudRetention
import app.callscribe.rules.Retention
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.ZoneOffset

/** In-memory Drive: folders and files with appProperties, trash, and call counters. */
class FakeDriveApi : DriveApi {
    data class Entry(val file: DriveFile, val parent: String?, val folder: Boolean, var bytes: ByteArray = ByteArray(0), var trashed: Boolean = false)

    val entries = linkedMapOf<String, Entry>()
    var uploads = 0
    var failUploadsNamed: String? = null
    var authRequired = false
    private var next = 0

    private fun check() { if (authRequired) throw DriveAuthRequiredException() }
    fun live() = entries.values.filter { !it.trashed }
    fun filesIn(kind: String) = live().filter { !it.folder && it.file.appProperties[BackupFormat.KIND] == kind }

    override suspend fun findFolders(propKey: String, propValue: String, parentId: String?) = run {
        check()
        live().filter { it.folder && it.file.appProperties[propKey] == propValue && (parentId == null || it.parent == parentId) }.map { it.file }
    }

    override suspend fun createFolder(name: String, parentId: String?, appProperties: Map<String, String>): DriveFile {
        check()
        val f = DriveFile("id${next++}", name, appProperties)
        entries[f.id] = Entry(f, parentId, folder = true)
        return f
    }

    override suspend fun list(folderId: String) = run { check(); live().filter { it.parent == folderId }.map { it.file } }

    override suspend fun upload(parentId: String, name: String, appProperties: Map<String, String>, content: UploadContent, existingId: String?): DriveFile {
        check()
        if (failUploadsNamed != null && name.contains(failUploadsNamed!!)) throw java.io.IOException("boom")
        uploads++
        val bytes = when (content) {
            is UploadContent.Bytes -> content.bytes
            is UploadContent.Stream -> content.open().use { it.readBytes() }
        }
        val id = existingId ?: "id${next++}"
        val parent = existingId?.let { entries[it]!!.parent } ?: parentId
        val f = DriveFile(id, name, appProperties, bytes.size.toLong())
        entries[id] = Entry(f, parent, folder = false, bytes = bytes)
        return f
    }

    override suspend fun download(fileId: String) = run { check(); entries[fileId]!!.bytes }
    override suspend fun downloadTo(fileId: String, sink: (InputStream) -> Unit) = sink(ByteArrayInputStream(download(fileId)))
    override suspend fun trash(fileId: String) { check(); entries[fileId]!!.trashed = true }
}

class DriveBackupTest {
    private val now = 1_800_000_000L
    private val dev = BackupDevice("device-1", "Galaxy S20 FE")
    private val day = 86_400L

    private fun rec(n: Int, ageDays: Long = 1, text: String? = "שלום", kind: LabelKind = LabelKind.UNKNOWN_NUMBER) = RecordingEntity(
        id = n.toLong(), contentHash = "hash$n".padEnd(64, '0'), mediaId = 100L + n, uri = "content://media/$n",
        displayName = "Call recording 05012345${n}_251110_140300.m4a", relativePath = "Recordings/Call/",
        sizeBytes = 3, durationMs = 1000, dateAddedSec = now - ageDays * day, dateModifiedSec = 0,
        counterparty = "05012345$n", samsungLabel = null, labelKind = kind, callTimeSec = now - ageDays * day,
        transcriptText = text, status = if (text != null) Status.TRANSCRIBED else Status.NOT_TRANSCRIBED,
    )

    private class Source(var rows: List<RecordingEntity>, var policies: List<PolicyEntity> = CallerType.entries.map { PolicyEntity.defaultFor(it) }) : BackupSource {
        var settings = AppSettings(runpodEndpointId = "ep1", geminiModel = "g")
        val opened = mutableListOf<String>()
        override suspend fun recordings() = rows
        override suspend fun policies() = policies
        override suspend fun settings() = settings
        override fun openAudio(r: RecordingEntity) = UploadContent.Stream(3, "audio/mp4") {
            opened += r.contentHash; ByteArrayInputStream(byteArrayOf(1, 2, 3))
        }
    }

    /** Planner with a fixed cloud period for every policy (the policies branch stores it per policy). */
    private fun cloudDays(days: Int) = { r: List<RecordingEntity>, p: List<PolicyEntity>, n: Long -> Retention.plan(r, p, n, cloudDays = { days }) }

    @Test fun firstBackupUploadsEverythingThenNothing() = runBlocking {
        val drive = FakeDriveApi()
        val engine = DriveBackupEngine(drive, ZoneOffset.UTC)
        val src = Source(listOf(rec(1), rec(2)))
        val r1 = engine.backup(dev, src, includeAudio = true, nowSec = now)
        assertEquals(2, r1.callsUploaded)
        assertEquals(2, r1.audioUploaded)
        assertEquals(2, drive.filesIn(BackupFormat.KIND_CALL).size)
        assertEquals(1, drive.filesIn(BackupFormat.KIND_SETTINGS).size)
        assertEquals(1, drive.filesIn(BackupFormat.KIND_POLICIES).size)
        assertEquals(1, drive.filesIn(BackupFormat.KIND_MANIFEST).size)
        // One root, Calls and Recordings folder, found again by property — not duplicated.
        val before = drive.uploads
        val r2 = engine.backup(dev, src, includeAudio = true, nowSec = now + 60)
        assertEquals(0, r2.callsUploaded)
        assertEquals(0, r2.audioUploaded)
        assertEquals(before + 1, drive.uploads) // the manifest only
        assertEquals(4, drive.live().count { it.folder }) // top, this phone, Calls, Recordings
    }

    @Test fun changedRowIsUpdatedInPlace() = runBlocking {
        val drive = FakeDriveApi()
        val engine = DriveBackupEngine(drive, ZoneOffset.UTC)
        val src = Source(listOf(rec(1), rec(2)))
        engine.backup(dev, src, includeAudio = false, nowSec = now)
        // Device-only changes (new MediaStore id after a rename, updatedAt) don't count as changes.
        src.rows = listOf(rec(1).copy(mediaId = 999, uri = "content://x", updatedAt = 5), rec(2).copy(summary = "Loan offer"))
        val r = engine.backup(dev, src, includeAudio = false, nowSec = now)
        assertEquals(1, r.callsUploaded)
        val calls = drive.filesIn(BackupFormat.KIND_CALL)
        assertEquals(2, calls.size)
        val c2 = BackupFormat.decodeCall(calls.first { it.file.appProperties[BackupFormat.HASH] == rec(2).contentHash }.bytes)
        assertEquals("Loan offer", c2.summary)
        assertEquals(0L, c2.mediaId) // normalised
    }

    @Test fun audioIsOptInAndSkipsMissingFiles() = runBlocking {
        val drive = FakeDriveApi()
        val engine = DriveBackupEngine(drive, ZoneOffset.UTC)
        val src = Source(listOf(rec(1), rec(2).copy(fileMissing = true), rec(3).copy(audioDeletedAt = 1)))
        assertEquals(0, engine.backup(dev, src, includeAudio = false, nowSec = now).audioUploaded)
        assertTrue(src.opened.isEmpty())
        assertEquals(1, engine.backup(dev, src, includeAudio = true, nowSec = now).audioUploaded)
        assertEquals(listOf(rec(1).contentHash), src.opened)
        // A recording the user deleted from Drive is uploaded again.
        drive.filesIn(BackupFormat.KIND_RECORDING).forEach { it.trashed = true }
        assertEquals(1, engine.backup(dev, src, includeAudio = true, nowSec = now).audioUploaded)
    }

    @Test fun cloudRetentionNDaysTrashesAudioAndStripsText() = runBlocking {
        val drive = FakeDriveApi()
        val src = Source(listOf(rec(1, ageDays = 2), rec(2, ageDays = 40)))
        // Back everything up while nothing is expired...
        DriveBackupEngine(drive, ZoneOffset.UTC, cloudDays(CloudRetention.FOREVER)).backup(dev, src, true, now)
        assertEquals(2, drive.filesIn(BackupFormat.KIND_RECORDING).size)
        // ...then a 30-day cloud period applies.
        val r = DriveBackupEngine(drive, ZoneOffset.UTC, cloudDays(30)).backup(dev, src, true, now)
        assertEquals(1, r.audioTrashed)
        assertEquals(0, r.audioUploaded) // the expired one is not uploaded again
        val left = drive.filesIn(BackupFormat.KIND_RECORDING).map { it.file.appProperties[BackupFormat.HASH] }
        assertEquals(listOf(rec(1).contentHash), left)
        val old = drive.filesIn(BackupFormat.KIND_CALL).first { it.file.appProperties[BackupFormat.HASH] == rec(2).contentHash }
        assertNull(BackupFormat.decodeCall(old.bytes).transcriptText)
        // 0 days = this group's audio never goes to Drive.
        val drive2 = FakeDriveApi()
        assertEquals(0, DriveBackupEngine(drive2, ZoneOffset.UTC, cloudDays(0)).backup(dev, src, true, now).audioUploaded)
    }

    @Test fun sameAsLocalFollowsActualLocalDeletion() {
        val due = rec(1, ageDays = 40)
        val deleted = rec(2, ageDays = 40).copy(audioDeletedAt = 123)
        val textGone = rec(3, ageDays = 40, text = null).copy(textDeletedAt = 456)
        val policies = listOf(PolicyEntity.defaultFor(CallerType.UNKNOWN_NUMBER).copy(audioRetentionDays = 30))
        val plan = Retention.plan(listOf(due, deleted, textGone), policies, now)
        // Due locally but not yet deleted (awaiting confirmation): cloud copy stays.
        assertEquals(listOf(rec(1).contentHash, rec(3).contentHash), plan.trashAudio.map { it.contentHash })
        assertEquals(listOf(deleted.contentHash), plan.cloudAudio.map { it.contentHash })
        assertEquals(listOf(textGone.contentHash), plan.cloudText.map { it.contentHash })
    }

    @Test fun keepForeverKeepsCloudCopy() {
        val r = rec(1, ageDays = 400).copy(audioDeletedAt = 1)
        val plan = Retention.plan(listOf(r), listOf(), now, keptForever = { true }, cloudDays = { 30 })
        assertTrue(plan.cloudAudio.isEmpty())
        assertTrue(plan.cloudText.isEmpty())
        val plan2 = Retention.plan(listOf(r), listOf(), now, keptForever = { false }, cloudDays = { 30 })
        assertEquals(1, plan2.cloudAudio.size)
    }

    @Test fun failuresAreCountedAndOthersContinue() = runBlocking {
        val drive = FakeDriveApi()
        drive.failUploadsNamed = "050123451"
        val r = DriveBackupEngine(drive, ZoneOffset.UTC).backup(dev, Source(listOf(rec(1), rec(2))), false, now)
        assertEquals(1, r.callsUploaded)
        assertEquals(1, r.failures)
        // Next run retries only the failed one.
        drive.failUploadsNamed = null
        assertEquals(1, DriveBackupEngine(drive, ZoneOffset.UTC).backup(dev, Source(listOf(rec(1), rec(2))), false, now).callsUploaded)
    }

    @Test(expected = DriveAuthRequiredException::class)
    fun authRequiredStopsTheRun(): Unit = runBlocking {
        val drive = FakeDriveApi().apply { authRequired = true }
        DriveBackupEngine(drive).backup(dev, Source(listOf(rec(1))), false, now)
    }

    @Test fun timeBudgetStopsAudioAndResumes() = runBlocking {
        val drive = FakeDriveApi()
        val src = Source((1..5).map { rec(it) })
        var checks = 0 // checked once per call (5), then once per recording
        val r = DriveBackupEngine(drive, ZoneOffset.UTC).backup(dev, src, true, now) { ++checks > 7 }
        assertTrue(r.incomplete)
        assertEquals(2, r.audioUploaded)
        assertEquals(5, r.callsUploaded)
        val r2 = DriveBackupEngine(drive, ZoneOffset.UTC).backup(dev, src, true, now)
        assertFalse(r2.incomplete)
        assertEquals(3, r2.audioUploaded)
    }

    @Test fun settingsBackupHasNoSecrets() {
        val names = AppSettings.serializer().descriptor.let { d -> (0 until d.elementsCount).map { d.getElementName(it).lowercase() } }
        val bad = names.filter { n -> listOf("key", "token", "secret", "password").any { it in n } }
        assertTrue("AppSettings must not hold secrets (use SecretStore): $bad", bad.isEmpty())
        val restored = DriveBackupEngine.restoredSettings(
            AppSettings(autoProcessFromSec = 42, runpodEndpointId = "mine"),
            AppSettings(autoProcessFromSec = 1, runpodEndpointId = "cloud", analyzerId = "openrouter"),
        )
        assertEquals(42L, restored.autoProcessFromSec) // this phone's "process from" point is kept
        assertEquals("cloud", restored.runpodEndpointId)
        assertEquals("openrouter", restored.analyzerId)
    }

    @Test fun restoreOnNewPhoneRelinksByHash() = runBlocking {
        val drive = FakeDriveApi()
        val custom = PolicyEntity(displayName = "Mom", numbers = "501234567", transcribe = false, summarize = false)
        val old = Source(listOf(rec(1).copy(summary = "Loan offer", verdict = "spam"), rec(2)), CallerType.entries.map { PolicyEntity.defaultFor(it) } + custom)
        DriveBackupEngine(drive, ZoneOffset.UTC).backup(dev, old, true, now)

        // New phone: call 2 already scanned (no transcript), call 1 not on this phone.
        val db = MemStore()
        db.insert(rec(2, text = null).copy(id = 0, mediaId = 7, uri = "content://new/7"))
        val sink = Sink(db)
        val backup = DriveBackupEngine(drive, ZoneOffset.UTC).backups().single()
        assertEquals("Galaxy S20 FE", backup.name)
        val rep = DriveBackupEngine(drive, ZoneOffset.UTC).restore(sink, backup, nowMs = 1)
        assertEquals(1, rep.inserted)
        assertEquals(1, rep.merged)
        assertTrue(rep.settings)
        assertEquals("ep1", sink.settings?.runpodEndpointId)
        assertTrue(sink.policies.any { it.displayName == "Mom" && it.id == 0L })

        val r1 = db.byHash(rec(1).contentHash)!!
        assertTrue(r1.fileMissing)
        assertEquals("Loan offer", r1.summary)
        val r2 = db.byHash(rec(2).contentHash)!!
        assertEquals("שלום", r2.transcriptText)
        assertEquals(7L, r2.mediaId) // local identity kept

        // Audio for call 1 is offered for download.
        val missing = DriveBackupEngine(drive, ZoneOffset.UTC).missingAudio(db.all(), backup)
        assertEquals(listOf(rec(1).contentHash), missing.map { it.hash })

        // Once the file shows up on the phone (copied over, or downloaded from Drive), a scan re-links it by hash.
        val sync = SyncEngine(db, { rec(1).contentHash }, ZoneOffset.UTC)
        val res = sync.sync(listOf(MediaFile(55, "content://new/55", "x.m4a", "Recordings/Call/", 3, 1000, now, now)))
        assertEquals(1, res.relinked)
        assertTrue(res.newRows.isEmpty())
        assertFalse(db.byHash(rec(1).contentHash)!!.fileMissing)
        assertEquals("Loan offer", db.byHash(rec(1).contentHash)!!.summary)
    }

    @Test fun restoreNeverOverwritesLocalResults() {
        val local = rec(1).copy(transcriptText = "local", summary = "local summary")
        assertNull(BackupFormat.mergeRestored(local, rec(1).copy(transcriptText = "cloud", summary = "cloud"), 1))
        val merged = BackupFormat.mergeRestored(rec(1, text = null), rec(1).copy(summary = "s", verdict = "spam"), 1)!!
        assertEquals(Status.ANALYZED, merged.status)
        assertEquals("spam", merged.verdict)
    }

    @Test fun noBackupsInEmptyDrive() = runBlocking {
        assertTrue(DriveBackupEngine(FakeDriveApi()).backups().isEmpty())
    }

    @Test fun eachPhoneHasItsOwnFolder() = runBlocking {
        val drive = FakeDriveApi()
        val engine = DriveBackupEngine(drive, ZoneOffset.UTC)
        val daily = BackupDevice("device-2", "Pixel 9")
        val a = Source(listOf(rec(1), rec(2))).apply { settings = AppSettings(runpodEndpointId = "test-phone") }
        val b = Source(listOf(rec(2), rec(3))).apply { settings = AppSettings(runpodEndpointId = "daily-phone") }
        engine.backup(dev, a, includeAudio = true, nowSec = now)
        engine.backup(daily, b, includeAudio = true, nowSec = now + 10)
        // One top folder, two phone folders, each with its own Calls + Recordings.
        assertEquals(1, drive.live().count { it.folder && it.file.appProperties[BackupFormat.KIND] == BackupFormat.KIND_ROOT })
        assertEquals(listOf("Galaxy S20 FE", "Pixel 9"), drive.live().filter { it.file.appProperties[BackupFormat.KIND] == BackupFormat.KIND_DEVICE }.map { it.file.name })
        assertEquals(4, drive.filesIn(BackupFormat.KIND_CALL).size) // call 2 is in both
        assertEquals(2, drive.filesIn(BackupFormat.KIND_SETTINGS).size) // neither overwrites the other
        // Backing up again doesn't touch the other phone's files.
        assertEquals(0, engine.backup(dev, a, includeAudio = true, nowSec = now + 20).callsUploaded)

        val list = engine.backups()
        assertEquals(listOf("Galaxy S20 FE", "Pixel 9"), list.map { it.name }) // most recent first
        assertEquals(listOf(2, 2), list.map { it.calls })
        val sink = Sink(MemStore())
        val rep = engine.restore(sink, list.first { it.deviceId == "device-2" }, 1)
        assertEquals(2, rep.inserted)
        assertEquals("daily-phone", sink.settings?.runpodEndpointId)
        assertEquals(setOf(rec(2).contentHash, rec(3).contentHash), sink.db.all().map { it.contentHash }.toSet())
    }

    @Test fun fileNamesAreReadable() {
        val name = BackupFormat.callFileName(rec(1).copy(callTimeSec = 1_762_783_380, counterparty = "Dana/Cohen"), ZoneOffset.UTC)
        assertEquals("2025-11-10 14-03 Dana_Cohen hash1000.json", name)
        assertEquals("audio/mp4", BackupFormat.audioMime("x.M4A"))
    }

    private class MemStore : RecordingStore {
        val rows = linkedMapOf<Long, RecordingEntity>()
        private var next = 1L
        override suspend fun byMediaId(mediaId: Long) = rows.values.firstOrNull { it.mediaId == mediaId }
        override suspend fun byHash(hash: String) = rows.values.firstOrNull { it.contentHash == hash }
        override suspend fun insert(r: RecordingEntity): Long { val id = next++; rows[id] = r.copy(id = id); return id }
        override suspend fun update(r: RecordingEntity) { rows[r.id] = r }
        override suspend fun all() = rows.values.toList()
    }

    private class Sink(val db: MemStore) : RestoreSink {
        var settings: AppSettings? = null
        var policies: List<PolicyEntity> = emptyList()
        override suspend fun byHash(hash: String) = db.byHash(hash)
        override suspend fun insert(r: RecordingEntity) { db.insert(r) }
        override suspend fun update(r: RecordingEntity) = db.update(r)
        override suspend fun replacePolicies(policies: List<PolicyEntity>) { this.policies = policies }
        override suspend fun applySettings(cloud: AppSettings) { settings = DriveBackupEngine.restoredSettings(AppSettings(), cloud) }
    }
}
