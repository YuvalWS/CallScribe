package app.callscribe.providers.ondevice

import app.callscribe.providers.Segment

/**
 * Builds transcript segments from a streaming recognizer: each endpoint (pause in speech) closes the current
 * utterance. Times are audio positions in seconds. Pure logic, unit-tested.
 */
class SegmentAssembler {
    private val segments = mutableListOf<Segment>()
    private var segStart = 0.0

    /** The recognizer reported an endpoint at audio position [now] with the utterance [text]; returns the new segment, if any. */
    fun endpoint(text: String, now: Double): Segment? {
        val t = text.trim().replace(Regex("\\s+"), " ")
        val seg = if (t.isNotEmpty()) Segment(segStart, maxOf(now, segStart), t).also { segments += it } else null
        segStart = now
        return seg
    }

    /** Input ended at [end]; [text] is whatever the recognizer still holds. */
    fun finish(text: String, end: Double): List<Segment> {
        endpoint(text, end)
        return segments.toList()
    }

    companion object {
        fun join(segments: List<Segment>) = segments.joinToString(" ") { it.text }
    }
}

/** Throttles progress callbacks to whole-percent steps of at least [stepPct]. */
class ProgressThrottle(private val stepPct: Int, private val emit: (Int) -> Unit) {
    private var last = -1
    fun update(done: Long, total: Long) {
        if (total <= 0) return
        val pct = (done * 100 / total).toInt().coerceIn(0, 100)
        if (last < 0 || pct - last >= stepPct || (pct == 100 && last != 100)) { last = pct; emit(pct) }
    }
}
