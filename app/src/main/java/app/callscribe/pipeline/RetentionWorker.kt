package app.callscribe.pipeline

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.callscribe.AppGraph
import app.callscribe.CallScribeApp
import app.callscribe.data.Status
import app.callscribe.rules.Retention
import app.callscribe.rules.RetentionPlan

/**
 * Daily: deletes transcripts/summaries past their text-retention period (the app's own data, opt-in per policy),
 * and notifies about recordings past their audio-retention period. Audio is only moved to the system trash
 * after the user confirms in the app (Android requires consent to delete another app's files).
 */
class RetentionWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as CallScribeApp).graph
        val plan = currentPlan(graph)
        val cleared = deleteText(graph, plan)
        Log.i("RetentionWorker", "text cleared: $cleared, audio due: ${plan.trashAudio.size}")
        if (plan.trashAudio.isNotEmpty()) graph.notifier.notifyRetention(plan.trashAudio.size, plan.audioBytes)
        return Result.success()
    }

    companion object {
        suspend fun currentPlan(graph: AppGraph): RetentionPlan {
            graph.db.policies().ensureGroupDefaults()
            return Retention.plan(graph.db.recordings().all(), graph.db.policies().all(), System.currentTimeMillis() / 1000)
        }

        suspend fun deleteText(graph: AppGraph, plan: RetentionPlan): Int {
            val dao = graph.db.recordings()
            val now = System.currentTimeMillis()
            for (r in plan.deleteText) {
                dao.update(
                    r.copy(
                        transcriptText = null, transcriptJson = null, analysisJson = null, summary = null,
                        verdict = null, spamConfidence = null, category = null, callerIntent = null,
                        status = if (r.status == Status.FAILED) r.status else Status.NOT_TRANSCRIBED,
                        textDeletedAt = now, updatedAt = now,
                    )
                )
            }
            return plan.deleteText.size
        }
    }
}
