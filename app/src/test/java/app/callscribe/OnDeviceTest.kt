package app.callscribe

import app.callscribe.providers.Segment
import app.callscribe.providers.ondevice.OnDeviceModels
import app.callscribe.providers.ondevice.ProgressThrottle
import app.callscribe.providers.ondevice.SegmentAssembler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentAssemblerTest {
    @Test fun endpointsBecomeSegmentsAndEmptyUtterancesAreSkipped() {
        val a = SegmentAssembler()
        a.endpoint(" שלום  מה  שלומך ", 2.5)
        a.endpoint("   ", 4.0)            // silence only: no segment, but the next one starts here
        a.endpoint("הכל טוב", 6.0)
        val segs = a.finish("תודה", 7.25)
        assertEquals(
            listOf(Segment(0.0, 2.5, "שלום מה שלומך"), Segment(4.0, 6.0, "הכל טוב"), Segment(6.0, 7.25, "תודה")),
            segs,
        )
        assertEquals("שלום מה שלומך הכל טוב תודה", SegmentAssembler.join(segs))
    }

    @Test fun emptyAudioGivesNoSegments() {
        val segs = SegmentAssembler().finish("", 0.0)
        assertTrue(segs.isEmpty())
        assertEquals("", SegmentAssembler.join(segs))
    }
}

class ProgressThrottleTest {
    @Test fun emitsWholePercentStepsAndAlwaysTheEnd() {
        val out = mutableListOf<Int>()
        val t = ProgressThrottle(5) { out += it }
        for (done in 0L..1000L step 7) t.update(done, 1000)
        t.update(1000, 1000)
        assertEquals(0, out.first()); assertEquals(100, out.last())
        assertTrue(out.zipWithNext().all { (a, b) -> b - a >= 5 || b == 100 })
        t.update(1000, 1000)
        assertEquals(1, out.count { it == 100 })
    }

    @Test fun ignoresUnknownTotal() {
        val out = mutableListOf<Int>()
        ProgressThrottle(1) { out += it }.update(5, 0)
        assertTrue(out.isEmpty())
    }
}

class OnDeviceModelTest {
    @Test fun zipformerSpecIsPinnedAndComplete() {
        val m = OnDeviceModels.ZIPFORMER_HE
        assertEquals(72_316_960L, m.totalBytes)
        assertEquals(40, m.revision.length)
        assertTrue(m.files.all { it.sha256.matches(Regex("[0-9a-f]{64}")) })
        assertEquals(setOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"), m.files.map { it.localName }.toSet())
        assertEquals(
            "https://huggingface.co/ivrit-ai/zipformer2-66m-streaming-he/resolve/${m.revision}/sherpa-onnx/tokens.txt",
            m.url(m.file("tokens.txt")),
        )
    }
}
