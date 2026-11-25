package app.callscribe

import app.callscribe.data.LabelKind
import app.callscribe.data.RecordingEntity
import app.callscribe.data.Status
import app.callscribe.pipeline.BulkEstimator
import app.callscribe.pipeline.BulkPlan
import app.callscribe.pipeline.BulkProgress
import app.callscribe.pipeline.BulkRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun call(id: Long, min: Double = 2.0, text: String? = null, verdict: String? = null, status: Status = Status.NOT_TRANSCRIBED) =
    RecordingEntity(
        id = id, contentHash = "h$id", mediaId = id, uri = "u$id", displayName = "f$id", relativePath = "Recordings/Call/",
        sizeBytes = 1000, durationMs = (min * 60_000).toLong(), dateAddedSec = 0, dateModifiedSec = 0,
        counterparty = "0541234567", samsungLabel = null, labelKind = LabelKind.UNKNOWN_NUMBER, callTimeSec = 0,
        transcriptText = text, verdict = verdict, status = status,
    )

class BulkPlanTest {
    @Test fun splitsTranscribeAnalyzeAndSkipped() {
        val rows = listOf(
            call(1),                                   // needs everything
            call(2, text = "t"),                       // transcript, no summary
            call(3, text = "t", verdict = "spam"),     // done
            call(4, status = Status.QUEUED),           // busy
            call(5).copy(fileMissing = true),          // audio gone
        )
        val p = BulkPlan.of(rows, summarize = true)
        assertEquals(listOf(1L), p.transcribe.map { it.id })
        assertEquals(listOf(2L), p.analyzeOnly.map { it.id })
        assertEquals(3, p.skipped)
    }

    @Test fun transcribeOnlySkipsCallsThatHaveATranscript() {
        val p = BulkPlan.of(listOf(call(1), call(2, text = "t")), summarize = false)
        assertEquals(listOf(1L), p.all.map { it.id })
        assertEquals(1, p.skipped)
    }
}

class BulkEstimatorTest {
    private val ten = (1L..10).map { call(it, min = 3.0) } // 30 audio minutes

    @Test fun runPodUsesDefaultRateWithoutHistory() {
        val e = BulkEstimator.estimate(BulkPlan.of(ten, false), emptyList(), BulkEstimator.RUNPOD, null)
        val p = app.callscribe.data.DEFAULT_RUNPOD_PRICE
        assertEquals(p * (10 * (BulkEstimator.RUNPOD_EXEC_SEC_PER_CALL + BulkEstimator.RUNPOD_OVERHEAD_SEC) + 30 * BulkEstimator.RUNPOD_EXEC_SEC_PER_MIN), e.usd, 1e-9)
        assertEquals(10, e.calls)
        assertTrue(e.minutes > 0)
    }

    @Test fun runPodUsesOwnHistoryWhenThereIsEnough() {
        // Past calls took exactly 1 s + 2 s per audio minute to transcribe.
        val past = listOf(1.0, 4.0, 10.0).mapIndexed { i, m ->
            call(20L + i, min = m).copy(transcriberId = BulkEstimator.RUNPOD, transcriptionGpuMs = (1000 + 2000 * m).toLong(), transcriptText = "t")
        }
        val e = BulkEstimator.estimate(BulkPlan.of(ten, false), past, BulkEstimator.RUNPOD, null, runpodPricePerSec = 0.0003)
        assertEquals(0.0003 * (10 * (1.0 + BulkEstimator.RUNPOD_OVERHEAD_SEC) + 30 * 2.0), e.usd, 1e-6)
    }

    @Test fun fitFallsBackToAveragePerCallForSimilarLengths() {
        val (perCall, perMin) = BulkEstimator.fit(listOf(2.0 to 0.002, 2.2 to 0.004, 2.1 to 0.003))
        assertEquals(0.003, perCall, 1e-9)
        assertEquals(0.0, perMin, 0.0)
    }

    @Test fun onDeviceIsFreeButTakesAudioTimesRtf() {
        val e = BulkEstimator.estimate(BulkPlan.of(ten, false), emptyList(), BulkEstimator.ON_DEVICE, null)
        assertTrue(e.isFree)
        assertEquals(30 * BulkEstimator.ON_DEVICE_SEC_PER_MIN / 60, e.minutes, 1e-6)
    }

    @Test fun geminiIsFreeButRateLimited() {
        val many = (1L..45).map { call(it, text = "t") } // analyze only
        val e = BulkEstimator.estimate(BulkPlan.of(many, true), emptyList(), BulkEstimator.RUNPOD, "gemini")
        assertEquals(0.0, e.analyzeUsd, 0.0)
        assertTrue("45 summaries at 15/min take at least 3 min", e.minutes >= 3.0)
    }

    @Test fun openRouterAddsPerCallCost() {
        val e = BulkEstimator.estimate(BulkPlan.of(ten, true), emptyList(), BulkEstimator.RUNPOD, "openrouter")
        assertEquals(10 * 0.0003, e.analyzeUsd, 1e-9)
    }

    @Test fun capIsTwiceTheEstimateWithAFloor() {
        val e = BulkEstimator.estimate(BulkPlan.of(ten, false), emptyList(), BulkEstimator.RUNPOD, null)
        assertEquals(2 * e.usd, e.capUsd, 1e-9)
        val one = BulkEstimator.estimate(BulkPlan.of(ten.take(1), false), emptyList(), BulkEstimator.RUNPOD, null)
        assertEquals(0.05, one.capUsd, 1e-9) // 2 × ~$0.008 is under the 5-cent floor
    }
}

class BulkProgressTest {
    @Test fun countsDoneFailedAndSpentSinceStart() {
        val run = BulkRun(listOf(1, 2, 3), startUsd = 0.01, capUsd = 1.0)
        val rows = listOf(
            call(1, text = "t", status = Status.ANALYZED).copy(transcriptionCostUsd = 0.02),
            call(2, status = Status.FAILED),
            call(3, status = Status.QUEUED),
            call(4, status = Status.QUEUED), // not part of the run
        )
        val p = BulkProgress.of(run, rows)
        assertEquals(3, p.total)
        assertEquals(2, p.done)
        assertEquals(1, p.failed)
        assertEquals(0.01, p.spentUsd, 1e-9)
        assertTrue(p.running)
        assertFalse(BulkProgress.of(run, rows.take(2)).running)
    }
}

class ShortCallTest {
    @Test fun callsUnderFiveSecondsAreSkippedButUnknownLengthIsNot() {
        val p = BulkPlan.of(listOf(call(1, min = 2.0 / 60), call(2, min = 0.0), call(3, min = 1.0)), summarize = false)
        assertEquals(listOf(2L, 3L), p.transcribe.map { it.id })
        assertEquals(1, p.skipped)
    }

    @Test fun noSpeakerSeparationOnShortCalls() {
        val f = app.callscribe.providers.ivrit.IvritRunPodTranscriber.Companion
        assertFalse(f.diarizeFor(true, 2_000))
        assertTrue(f.diarizeFor(true, 60_000))
        assertFalse(f.diarizeFor(false, 60_000))
    }
}
