package app.callscribe

import app.callscribe.data.RecordingEntity
import app.callscribe.detect.MediaFile
import app.callscribe.detect.RecordingStore
import app.callscribe.detect.SyncEngine
import app.callscribe.providers.TranscriptionState
import app.callscribe.providers.ivrit.IvritRunPodTranscriber
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

private class FakeStore : RecordingStore {
    val rows = linkedMapOf<Long, RecordingEntity>()
    private var next = 1L
    override suspend fun byMediaId(mediaId: Long) = rows.values.firstOrNull { it.mediaId == mediaId }
    override suspend fun byHash(hash: String) = rows.values.firstOrNull { it.contentHash == hash }
    override suspend fun insert(r: RecordingEntity): Long {
        if (rows.values.any { it.contentHash == r.contentHash }) return -1
        val id = next++; rows[id] = r.copy(id = id); return id
    }
    override suspend fun update(r: RecordingEntity) { rows[r.id] = r }
    override suspend fun all() = rows.values.toList()
}

class SyncEngineTest {
    private fun file(id: Long, name: String, size: Long = 100, mod: Long = 1) =
        MediaFile(id, "content://media/$id", name, "Recordings/Call/", size, 1000, 1_000, mod)

    private var hashCalls = 0
    private val hashes = mutableMapOf<Long, String>()
    private fun engine(store: FakeStore) = SyncEngine(store, { f -> hashCalls++; hashes.getValue(f.mediaId) }, ZoneOffset.UTC)

    @Test fun newFilesInsertedOnceAndUnchangedFilesNotRehashed() = runBlocking {
        val store = FakeStore()
        hashes[1] = "h1"; hashes[2] = "h2"
        val files = listOf(file(1, "Call recording 0541234567_250101_101010.m4a"), file(2, "Call recording Mom_250101_111111.m4a"))
        assertEquals(2, engine(store).sync(files).newRows.size)
        hashCalls = 0
        val again = engine(store).sync(files)
        assertEquals(0, again.newRows.size)
        assertEquals(0, hashCalls)
        assertEquals(2, store.rows.size)
    }

    @Test fun mediaIdChangeRelinksAndKeepsTranscript() = runBlocking {
        val store = FakeStore()
        hashes[1] = "h1"
        engine(store).sync(listOf(file(1, "a_250101_101010.m4a")))
        store.rows[1] = store.rows[1]!!.copy(transcriptText = "שלום")
        hashes[99] = "h1" // same audio, new MediaStore id + new name (restore / rename)
        val r = engine(store).sync(listOf(file(99, "renamed_250101_101010.m4a")))
        assertEquals(0, r.newRows.size)
        assertEquals(1, r.relinked)
        assertEquals(1, store.rows.size)
        val row = store.rows[1]!!
        assertEquals(99L, row.mediaId)
        assertEquals("שלום", row.transcriptText)
        assertEquals("renamed_250101_101010.m4a", row.displayName)
    }

    @Test fun deletedFileMarkedMissingNotDeleted() = runBlocking {
        val store = FakeStore()
        hashes[1] = "h1"
        engine(store).sync(listOf(file(1, "a.m4a")))
        val r = engine(store).sync(emptyList())
        assertEquals(1, r.missing)
        assertTrue(store.rows[1]!!.fileMissing)
    }

    @Test fun callTimeComesFromFileName() = runBlocking {
        val store = FakeStore()
        hashes[1] = "h1"
        val row = engine(store).sync(listOf(file(1, "Call recording Mom_250527_120438.m4a"))).newRows.single()
        assertEquals(java.time.LocalDateTime.of(2025, 5, 27, 12, 4, 38).toEpochSecond(ZoneOffset.UTC), row.callTimeSec)
    }
}

class RunPodParseTest {
    // Shape produced by ivrit-ai/runpod-serverless with streaming=false, aggregated by RunPod.
    private val completed = """
        {"id":"abc","status":"COMPLETED","executionTime":12500,"delayTime":800,
         "output":[{"result":[[{"type":"progress","data":{"p":0.5}}],
                              [{"type":"segments","data":[{"start":3.0,"end":5.0,"text":" מה שלומך?","speakers":["SPEAKER_01"]}]}],
                              [{"type":"segments","data":[{"start":0.0,"end":2.5,"text":" שלום"}]}]]}]}
    """.trimIndent()

    @Test fun parsesSegmentsInOrderWithCost() {
        val st = IvritRunPodTranscriber.parseStatus(completed, pricePerSec = 0.0002)
        assertTrue(st is TranscriptionState.Done)
        st as TranscriptionState.Done
        assertEquals("שלום מה שלומך?", st.transcript.text)
        assertEquals(2, st.transcript.segments.size)
        assertEquals("SPEAKER_01", st.transcript.segments[1].speaker)
        assertEquals(0.0025, st.cost!!.usd, 1e-9)
        assertEquals(12500L, st.cost!!.gpuMs)
    }

    /** Real response from the ivrit-ai RunPod template (2026-11), texts replaced. */
    @Test fun realResponseFixture() {
        val text = javaClass.classLoader!!.getResource("runpod_completed.json")!!.readText()
        val st = IvritRunPodTranscriber.parseStatus(text, pricePerSec = 0.0002)
        st as TranscriptionState.Done
        assertEquals(5, st.transcript.segments.size)
        assertEquals("קטע 1 קטע 2 קטע 3 קטע 4 קטע 5", st.transcript.text)
        assertEquals(2968L, st.cost!!.gpuMs)
    }

    @Test fun pendingAndFailed() {
        assertTrue(IvritRunPodTranscriber.parseStatus("""{"status":"IN_QUEUE"}""", 0.0) is TranscriptionState.Pending)
        assertTrue(IvritRunPodTranscriber.parseStatus("""{"status":"FAILED","error":"boom"}""", 0.0) is TranscriptionState.Failed)
        val errOut = """{"status":"COMPLETED","output":[{"result":[[{"type":"error","data":"bad audio"}]]}]}"""
        val st = IvritRunPodTranscriber.parseStatus(errOut, 0.0)
        assertTrue(st is TranscriptionState.Failed)
    }

    @Test fun handlerValidationErrorObject() {
        val st = IvritRunPodTranscriber.parseStatus("""{"status":"COMPLETED","output":[{"error":"Model not provided."}]}""", 0.0)
        assertTrue(st is TranscriptionState.Failed)
    }
}

class RunPodBodyTest {
    @Test fun streamedBodyIsValidJsonWithExactLength() {
        for (size in listOf(0, 1, 2, 3, 100_000, 3 * 16_384 + 1)) {
            val audio = ByteArray(size) { (it * 31).toByte() }
            val body = IvritRunPodTranscriber.RunBody("ivrit-ai/model\"x", true, audio)
            val buf = okio.Buffer()
            body.writeTo(buf)
            val text = buf.readUtf8()
            assertEquals(body.contentLength(), text.length.toLong())
            val o = kotlinx.serialization.json.Json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
            val input = o["input"] as kotlinx.serialization.json.JsonObject
            val args = input["transcribe_args"] as kotlinx.serialization.json.JsonObject
            val blob = (args["blob"] as kotlinx.serialization.json.JsonPrimitive).content
            assertTrue(java.util.Base64.getDecoder().decode(blob).contentEquals(audio))
            assertEquals("ivrit-ai/model\"x", (input["model"] as kotlinx.serialization.json.JsonPrimitive).content)
        }
    }
}

class RunPodSetupTest {
    private val ep = app.callscribe.providers.ivrit.RunPodSetup.Companion
    private fun e(id: String, name: String, image: String) = app.callscribe.providers.ivrit.RunPodSetup.Endpoint(id, name, image)

    @Test fun reusesAnIvritEndpointPreferringOurs() {
        val other = e("a", "my-llm", "vllm/vllm:latest")
        val theirs = e("b", "autogenerated-endpoint-transcribe-ivrit-ai", "yairlifshitz/whisper-runpod-serverless:ivrit-2026.05.31")
        val ours = e("c", "callscribe-ivrit", "yairlifshitz/whisper-runpod-serverless:latest")
        assertEquals(null, ep.pick(listOf(other)))
        assertEquals("b", ep.pick(listOf(other, theirs))?.id)
        assertEquals("c", ep.pick(listOf(other, theirs, ours))?.id)
    }

    @Test fun parsesListResponse() {
        val text = """{"endpoints":[{"id":"x1","name":"n","image":"img:1","workers":{"min":0}}],"pagination":{"hasNextPage":false}}"""
        assertEquals(listOf(e("x1", "n", "img:1")), ep.parseList(text))
    }

    @Test fun createBodyCostsNothingWhenIdle() {
        val o = kotlinx.serialization.json.Json.parseToJsonElement(ep.createBody()) as kotlinx.serialization.json.JsonObject
        val workers = o["workers"] as kotlinx.serialization.json.JsonObject
        assertEquals("0", workers["min"].toString())
        assertEquals("\"QUEUE\"", o["type"].toString())
        assertTrue(o["image"].toString().contains(app.callscribe.providers.ivrit.RunPodSetup.IMAGE_REPO))
        assertTrue((o["gpu"] as kotlinx.serialization.json.JsonObject)["pools"] is kotlinx.serialization.json.JsonArray)
    }

    @Test fun runRequestCarriesServerSideLimits() {
        val (head, _) = IvritRunPodTranscriber.envelope("m", false)
        assertTrue(head.contains("\"executionTimeout\":900000"))
        assertTrue(head.contains("\"ttl\":7200000"))
    }
}
