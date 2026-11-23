package app.callscribe.pipeline

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.callscribe.R
import app.callscribe.data.RecordingEntity
import app.callscribe.data.Status
import app.callscribe.ui.MainActivity

class Notifier(private val context: Context) {
    companion object {
        const val CHANNEL = "calls"
        const val EXTRA_RECORDING_ID = "recording_id"
        const val EXTRA_OPEN_RETENTION = "open_retention"
    }

    fun createChannel() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Call results", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun notifyRetention(count: Int, bytes: Long) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_OPEN_RETENTION, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(context, -2, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("$count recordings past their retention period")
            .setContentText("%.1f MB can be moved to the trash. Tap to review.".format(bytes / 1e6))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(-2, n)
    }

    fun notifyResult(r: RecordingEntity) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val who = r.counterparty ?: r.samsungLabel ?: "Unknown caller"
        val (title, text) = when {
            r.status == Status.FAILED -> "⚠ $who" to (r.error ?: "Processing failed")
            r.verdict != null -> {
                val pct = ((r.spamConfidence ?: 0.0) * 100).toInt()
                val head = when (r.verdict) {
                    "spam" -> "🚫 ספאם ($pct%)"
                    "not_spam" -> "📞"
                    else -> "❔"
                }
                "$head · $who" to (r.callerIntent?.ifBlank { null } ?: r.summary ?: "")
            }
            r.transcriptText != null -> "📝 $who" to r.transcriptText.take(200)
            else -> return
        }
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_RECORDING_ID, r.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(context, r.id.toInt(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOfNotNull(text, r.summary?.takeIf { it != text }).joinToString("\n\n")))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(r.id.toInt(), n)
    }
}
