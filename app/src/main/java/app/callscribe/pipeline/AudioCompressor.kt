package app.callscribe.pipeline

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.OutputStream
import java.nio.ByteOrder

/**
 * Re-encodes a recording to 16 kHz mono AAC at 32 kbps (~0.24 MB/min) so long calls fit the
 * provider's upload limit. Whisper resamples to 16 kHz internally, so no accuracy is lost.
 * Streams through a temp PCM file, so memory use is constant regardless of call length.
 */
class AudioCompressor(private val context: Context) {

    fun compress(uri: Uri, outRate: Int = 16_000, bitRate: Int = 32_000): ByteArray {
        val pcm = File.createTempFile("pcm", ".raw", context.cacheDir)
        val out = File.createTempFile("compressed", ".m4a", context.cacheDir)
        try {
            val samples = BufferedOutputStream(pcm.outputStream(), 1 shl 16).use { decodeToMono(uri, outRate, it) }
            encodeAac(pcm, samples, outRate, bitRate, out)
            return out.readBytes()
        } finally {
            pcm.delete(); out.delete()
        }
    }

    /** Linear resampler that keeps state across buffers. Writes 16-bit LE mono samples. */
    private class Resampler(private val out: OutputStream, private val outRate: Int) {
        var srcRate = 0
        private var pos = 0.0          // position of next output sample, in source-sample units
        private var prev: Int? = null  // last source sample of the previous buffer
        private var srcIndex = 0L      // index of the first sample of the current buffer
        var written = 0L

        private var bytes = ByteArray(16_384)

        fun push(chunk: ShortArray, n: Int) {
            val step = srcRate.toDouble() / outRate
            val need = ((n / step) + 4).toInt() * 2
            if (bytes.size < need) bytes = ByteArray(need)
            var bi = 0
            // Sample at absolute source index k: either prev (k == srcIndex-1) or chunk[k - srcIndex].
            fun at(k: Long): Int = if (k < srcIndex) (prev ?: chunk[0].toInt()) else chunk[(k - srcIndex).toInt()].toInt()
            val last = srcIndex + n - 1
            while (true) {
                val a = kotlin.math.floor(pos).toLong()
                val b = a + 1
                if (b > last) break // needs the next buffer (the final source sample is dropped)
                val frac = pos - a
                val v = (at(a) * (1 - frac) + at(b) * frac).toInt()
                bytes[bi++] = (v and 0xff).toByte(); bytes[bi++] = ((v shr 8) and 0xff).toByte()
                written++
                pos += step
            }
            out.write(bytes, 0, bi)
            prev = chunk[n - 1].toInt()
            srcIndex += n
        }
    }

    /** Decodes any recording to 16-bit LE mono PCM at [outRate]; returns the number of samples written. */
    fun decodeToMono(uri: Uri, outRate: Int, sink: OutputStream): Long {
        val ex = MediaExtractor()
        ex.setDataSource(context, uri, null)
        val track = (0 until ex.trackCount).first {
            ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        }
        ex.selectTrack(track)
        val inFmt = ex.getTrackFormat(track)
        val dec = MediaCodec.createDecoderByType(inFmt.getString(MediaFormat.KEY_MIME)!!)
        dec.configure(inFmt, null, null, 0)
        dec.start()

        val rs = Resampler(sink, outRate).apply { srcRate = inFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) }
        var channels = inFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var mono = ShortArray(8192)
        val info = MediaCodec.BufferInfo()
        var inDone = false
        var outDone = false
        try {
            while (!outDone) {
                if (!inDone) {
                    val i = dec.dequeueInputBuffer(2_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(dec.getInputBuffer(i)!!, 0)
                        if (n < 0) {
                            dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true
                        } else {
                            dec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance()
                        }
                    }
                }
                val o = dec.dequeueOutputBuffer(info, if (inDone) 10_000 else 0)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    rs.srcRate = dec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = dec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (o >= 0) {
                    val buf = dec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    val sb = buf.asShortBuffer()
                    val frames = sb.remaining() / channels
                    if (mono.size < frames) mono = ShortArray(frames)
                    for (f in 0 until frames) {
                        var sum = 0
                        repeat(channels) { sum += sb.get() }
                        mono[f] = (sum / channels).toShort()
                    }
                    if (frames > 0) rs.push(mono, frames)
                    dec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                }
            }
        } finally {
            dec.stop(); dec.release(); ex.release()
        }
        return rs.written
    }

    private fun encodeAac(pcm: File, totalSamples: Long, rate: Int, bitRate: Int, out: File) {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()
        val mux = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIdx = -1
        var muxStarted = false
        val info = MediaCodec.BufferInfo()
        val input = DataInputStream(BufferedInputStream(pcm.inputStream(), 1 shl 16))
        val chunk = ByteArray(4096 * 2)
        var pos = 0L
        var inDone = false
        var outDone = false
        try {
            while (!outDone) {
                if (!inDone) {
                    val i = enc.dequeueInputBuffer(2_000)
                    if (i >= 0) {
                        val buf = enc.getInputBuffer(i)!!
                        buf.clear()
                        val samples = minOf(buf.capacity() / 2L, totalSamples - pos, 4096L).toInt()
                        val ptsUs = pos * 1_000_000 / rate
                        if (samples <= 0) {
                            enc.queueInputBuffer(i, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true
                        } else {
                            try { input.readFully(chunk, 0, samples * 2) } catch (_: EOFException) {}
                            buf.put(chunk, 0, samples * 2)
                            enc.queueInputBuffer(i, 0, samples * 2, ptsUs, 0)
                            pos += samples
                        }
                    }
                }
                val o = enc.dequeueOutputBuffer(info, if (inDone) 10_000 else 0)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    trackIdx = mux.addTrack(enc.outputFormat); mux.start(); muxStarted = true
                } else if (o >= 0) {
                    val buf = enc.getOutputBuffer(o)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxStarted) {
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        mux.writeSampleData(trackIdx, buf, info)
                    }
                    enc.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                }
            }
        } finally {
            input.close()
            enc.stop(); enc.release()
            if (muxStarted) mux.stop()
            mux.release()
        }
    }
}
