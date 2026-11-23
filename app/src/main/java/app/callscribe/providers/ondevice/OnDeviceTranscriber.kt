package app.callscribe.providers.ondevice

import android.net.Uri
import android.os.SystemClock
import app.callscribe.pipeline.AudioCompressor
import app.callscribe.providers.AudioInput
import app.callscribe.providers.Cost
import app.callscribe.providers.ProviderError
import app.callscribe.providers.Segment
import app.callscribe.providers.Transcriber
import app.callscribe.providers.Transcript
import app.callscribe.providers.TranscriptionState
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File

/** A transcriber that runs in-process on the phone: no job handle, it just transcribes (and reports progress). */
interface LocalTranscriber {
    /**
     * [onProgress] gets 0..100 (decoding counts as the first 5 %); [onSegment] gets each finished segment as it is
     * recognized, so a caller that is stopped half-way can keep what was done. Blocking work runs on a background dispatcher.
     */
    suspend fun transcribeLocal(uri: Uri, onProgress: (Int) -> Unit, onSegment: (Segment) -> Unit = {}): TranscriptionState
}

/** Hebrew transcription on the phone with sherpa-onnx + ivrit.ai's streaming Zipformer. Free, nothing leaves the phone. */
class OnDeviceTranscriber(
    private val store: ModelStore,
    private val threads: Int,
    private val model: OnDeviceModel = OnDeviceModels.ZIPFORMER_HE,
) : Transcriber, LocalTranscriber {
    companion object {
        const val ID = "ondevice-zipformer-he"
        private const val RATE = 16_000
        private const val CHUNK = RATE / 2 // feed 0.5 s at a time
    }

    override val id = ID
    override val displayName = "On this phone (ivrit.ai Zipformer)"
    override val maxUploadBytes = Long.MAX_VALUE

    override fun isConfigured() = store.isInstalled(model)

    private fun recognizer(): OnlineRecognizer {
        if (!isConfigured()) throw ProviderError("On-device model is not downloaded (Settings → Providers)", false)
        val cfg = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = store.path(model, "encoder.int8.onnx"),
                    decoder = store.path(model, "decoder.int8.onnx"),
                    joiner = store.path(model, "joiner.int8.onnx"),
                ),
                tokens = store.path(model, "tokens.txt"),
                numThreads = threads.coerceIn(1, 4),
                modelType = "zipformer2",
            ),
            // Cut an utterance after 0.8 s of silence following speech, or at 20 s.
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 2.4f, 0f),
                rule2 = EndpointRule(true, 0.8f, 0f),
                rule3 = EndpointRule(false, 0f, 20f),
            ),
            enableEndpoint = true,
            decodingMethod = "greedy_search",
        )
        return try {
            OnlineRecognizer(config = cfg)
        } catch (e: Throwable) { // UnsatisfiedLinkError on an unsupported CPU, or a broken model file
            throw ProviderError("Could not load the on-device model: ${e.message ?: e.javaClass.simpleName}", false)
        }
    }

    override suspend fun test(): String = withContext(Dispatchers.IO) {
        val t0 = SystemClock.elapsedRealtime()
        val rec = recognizer()
        try {
            val s = rec.createStream()
            s.acceptWaveform(FloatArray(RATE), RATE)
            s.inputFinished()
            while (rec.isReady(s)) rec.decode(s)
            s.release()
        } finally { rec.release() }
        "Model OK — loaded and ran in ${SystemClock.elapsedRealtime() - t0} ms, ${"%.0f".format(store.usedBytes(model) / 1e6)} MB on the phone"
    }

    override suspend fun submit(audio: AudioInput): String =
        throw ProviderError("On-device transcription runs locally", false)

    override suspend fun poll(handle: String): TranscriptionState =
        TranscriptionState.Failed("On-device transcription has no jobs", false)

    override suspend fun transcribeLocal(uri: Uri, onProgress: (Int) -> Unit, onSegment: (Segment) -> Unit): TranscriptionState = withContext(Dispatchers.IO) {
        val ctx = store.context
        val t0 = SystemClock.elapsedRealtime()
        val pcm = File.createTempFile("pcm", ".raw", ctx.cacheDir)
        try {
            onProgress(0)
            val total = BufferedOutputStream(pcm.outputStream(), 1 shl 16).use { AudioCompressor(ctx).decodeToMono(uri, RATE, it) }
            val decodeMs = SystemClock.elapsedRealtime() - t0
            onProgress(5)
            val rec = recognizer()
            val asm = SegmentAssembler()
            val progress = ProgressThrottle(1) { p -> onProgress(5 + p * 95 / 100) }
            val segments = try {
                val stream = rec.createStream()
                try {
                    DataInputStream(BufferedInputStream(pcm.inputStream(), 1 shl 16)).use { input ->
                        val bytes = ByteArray(CHUNK * 2)
                        val floats = FloatArray(CHUNK)
                        var fed = 0L
                        while (fed < total) {
                            coroutineContext.ensureActive()
                            val n = minOf(CHUNK.toLong(), total - fed).toInt()
                            try { input.readFully(bytes, 0, n * 2) } catch (_: EOFException) { break }
                            for (i in 0 until n) {
                                val v = (bytes[2 * i].toInt() and 0xff) or (bytes[2 * i + 1].toInt() shl 8)
                                floats[i] = v / 32768f
                            }
                            stream.acceptWaveform(if (n == CHUNK) floats else floats.copyOf(n), RATE)
                            fed += n
                            while (rec.isReady(stream)) rec.decode(stream)
                            if (rec.isEndpoint(stream)) {
                                asm.endpoint(rec.getResult(stream).text, fed.toDouble() / RATE)?.let(onSegment)
                                rec.reset(stream)
                            }
                            progress.update(fed, total)
                        }
                        // Pad with silence so the last words are flushed out of the streaming encoder.
                        stream.acceptWaveform(FloatArray(RATE * 2 / 3), RATE)
                        stream.inputFinished()
                        while (rec.isReady(stream)) rec.decode(stream)
                        asm.finish(rec.getResult(stream).text, fed.toDouble() / RATE)
                    }
                } finally { stream.release() }
            } finally { rec.release() }

            val elapsedMs = SystemClock.elapsedRealtime() - t0
            val audioSec = total.toDouble() / RATE
            val raw = buildJsonObject {
                put("engine", "sherpa-onnx")
                put("model", model.repo + "@" + model.revision.take(8))
                put("threads", threads)
                put("audio_sec", audioSec)
                put("decode_ms", decodeMs)
                put("elapsed_ms", elapsedMs)
                put("rtf", if (audioSec > 0) elapsedMs / 1000.0 / audioSec else 0.0)
            }.toString()
            val detail = "On this phone: %.1f s for %.0f s of audio".format(elapsedMs / 1000.0, audioSec)
            TranscriptionState.Done(Transcript(SegmentAssembler.join(segments), segments, "he", raw), Cost(0.0, detail))
        } finally {
            pcm.delete()
        }
    }
}
