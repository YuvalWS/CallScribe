package app.callscribe.pipeline

import app.callscribe.data.RecordingEntity
import app.callscribe.data.Status
import kotlin.math.ceil
import kotlin.math.max

/** What a bulk run will do with a set of calls. Pure — unit-tested. */
data class BulkPlan(
    /** Need a transcript (and a summary, if [summarize]). */
    val transcribe: List<RecordingEntity>,
    /** Already transcribed, only missing the summary. */
    val analyzeOnly: List<RecordingEntity>,
    /** Already done, busy, shorter than [MIN_CALL_MS], or audio deleted. */
    val skipped: Int,
    val summarize: Boolean,
) {
    val all get() = transcribe + analyzeOnly
    val isEmpty get() = all.isEmpty()

    companion object {
        private val BUSY = setOf(Status.QUEUED, Status.TRANSCRIBING, Status.ANALYZING)

        /**
         * Calls shorter than this are left out of automatic and bulk processing: there's nothing to transcribe
         * (ring-outs, hang-ups) but each one still costs a server start. They can still be transcribed by hand.
         */
        const val MIN_CALL_MS = 5_000L

        fun tooShort(r: RecordingEntity) = r.durationMs in 1 until MIN_CALL_MS

        fun of(rows: List<RecordingEntity>, summarize: Boolean): BulkPlan {
            val tr = mutableListOf<RecordingEntity>()
            val an = mutableListOf<RecordingEntity>()
            for (r in rows) when {
                r.status in BUSY || tooShort(r) -> {}
                r.transcriptText == null && !r.fileMissing -> tr += r
                summarize && r.transcriptText != null && r.verdict == null -> an += r
            }
            return BulkPlan(tr, an, rows.size - tr.size - an.size, summarize)
        }
    }
}

/** Cost and time estimate for a [BulkPlan]. [usd] is a point estimate; [usdHigh] the cap we stop at. */
data class BulkEstimate(
    val calls: Int,
    val audioMinutes: Double,
    val transcribeUsd: Double,
    val analyzeUsd: Double,
    val minutes: Double,
    /** Short notes shown under the numbers (what the estimate is based on, free tiers, charging). */
    val notes: List<String>,
) {
    val usd get() = transcribeUsd + analyzeUsd
    val isFree get() = usd == 0.0
    /** The run stops by itself if it spends more than this: twice the estimate, at least 5 cents. */
    val capUsd get() = max(usd * 2, 0.05)
}

/**
 * Estimates cost and duration from the user's own history when there is enough of it, else from measured defaults
 * (README / docs/plans/on-device-transcription.md).
 */
object BulkEstimator {
    const val RUNPOD = "ivrit-runpod"
    const val ON_DEVICE = "ondevice-zipformer-he"

    /**
     * RunPod: each call costs a fixed part (worker start, model load, upload) plus GPU time per audio minute.
     * Measured on a real endpoint: 0:15 → $0.0011, 3:51 → $0.0022, 14 min → $0.003.
     */
    const val RUNPOD_USD_PER_CALL = 0.0008
    const val RUNPOD_USD_PER_MIN = 0.0002
    /** Measured on a Galaxy S20 FE: ~25 s per audio minute (RTF 0.41). */
    const val ON_DEVICE_SEC_PER_MIN = 25.0
    /** Calls processed in parallel against a cloud transcriber. */
    const val CLOUD_LANES = 3
    private const val CLOUD_SEC_PER_CALL = 20.0
    private const val CLOUD_SEC_PER_MIN = 1.5
    private const val COLD_START_SEC = 60.0
    private const val GEMINI_PER_MIN = 15.0
    private const val MIN_HISTORY = 3

    private val ANALYSIS_USD_PER_CALL = mapOf("gemini" to 0.0, "openrouter" to 0.0003, "claude" to 0.002)

    /**
     * Least-squares fit of cost = perCall + perMin × minutes over past calls. Falls back to the average cost per call
     * when the calls are all about the same length, and never returns negative parts. Pure — unit-tested.
     */
    fun fit(points: List<Pair<Double, Double>>): Pair<Double, Double> {
        val n = points.size.toDouble()
        val mx = points.sumOf { it.first } / n
        val my = points.sumOf { it.second } / n
        val sxx = points.sumOf { (it.first - mx) * (it.first - mx) }
        if (sxx < 1.0) return my to 0.0 // lengths too similar to separate the two parts
        val b = points.sumOf { (it.first - mx) * (it.second - my) } / sxx
        return when {
            b < 0 -> my to 0.0
            my - b * mx < 0 -> 0.0 to my / mx
            else -> (my - b * mx) to b
        }
    }

    fun estimate(plan: BulkPlan, history: List<RecordingEntity>, transcriberId: String?, analyzerId: String?): BulkEstimate {
        val notes = mutableListOf<String>()
        val audioMin = plan.transcribe.sumOf { it.durationMs } / 60_000.0

        // Transcription cost.
        val trUsd = when (transcriberId) {
            ON_DEVICE -> 0.0
            RUNPOD -> {
                val past = history.filter { it.transcriberId == RUNPOD && (it.transcriptionCostUsd ?: 0.0) > 0 && it.durationMs > 0 }
                val (perCall, perMin) = if (past.size >= MIN_HISTORY) {
                    notes += "Based on your ${past.size} past RunPod transcriptions."
                    fit(past.map { it.durationMs / 60_000.0 to it.transcriptionCostUsd!! })
                } else {
                    notes += "Based on typical RunPod prices; it gets more accurate as you transcribe."
                    RUNPOD_USD_PER_CALL to RUNPOD_USD_PER_MIN
                }
                plan.transcribe.size * perCall + audioMin * perMin
            }
            else -> 0.0
        }

        // Analysis cost.
        val analyzeCount = if (plan.summarize && analyzerId != null) plan.all.size else 0
        val anUsd = if (analyzeCount == 0) 0.0 else {
            val past = history.filter { it.analyzerId == analyzerId && it.analysisCostUsd != null }
            val perCall = if (past.size >= MIN_HISTORY) past.sumOf { it.analysisCostUsd!! } / past.size
            else ANALYSIS_USD_PER_CALL[analyzerId] ?: 0.002
            if (analyzerId == "gemini") notes += "Gemini's free tier: no cost, but at most 15 summaries a minute."
            analyzeCount * perCall
        }

        // Duration.
        var sec = when {
            plan.transcribe.isEmpty() || transcriberId == null -> 0.0
            transcriberId == ON_DEVICE -> {
                notes += "Runs on this phone: free, but uses battery — best while charging."
                audioMin * ON_DEVICE_SEC_PER_MIN // one call at a time
            }
            else -> COLD_START_SEC + (plan.transcribe.size * CLOUD_SEC_PER_CALL + audioMin * CLOUD_SEC_PER_MIN) / CLOUD_LANES
        }
        if (analyzeCount > 0) {
            val analyzeSec = if (analyzerId == "gemini") ceil(analyzeCount / GEMINI_PER_MIN) * 60 else analyzeCount * 5.0 / CLOUD_LANES
            sec = max(sec, analyzeSec) + (if (plan.transcribe.isEmpty()) 0.0 else 10.0)
        }
        return BulkEstimate(plan.all.size, audioMin, trUsd, anUsd, sec / 60, notes)
    }
}
