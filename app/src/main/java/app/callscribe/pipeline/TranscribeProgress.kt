package app.callscribe.pipeline

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo
import app.callscribe.R
import app.callscribe.data.RecordingEntity

/** Ongoing "Transcribing on this phone… 42 %" notification for on-device transcription. */
class TranscribeProgress(private val context: Context, private val row: RecordingEntity) {
    companion object {
        const val CHANNEL = "progress"
        private const val ID_BASE = 1_000_000
    }

    private val id = ID_BASE + (row.id % 1_000_000).toInt()

    init {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Transcription progress", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun build(pct: Int) = NotificationCompat.Builder(context, CHANNEL)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle("Transcribing on this phone")
        .setContentText((row.counterparty ?: row.samsungLabel ?: row.displayName) + " · $pct%")
        .setProgress(100, pct, false)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .build()

    fun foregroundInfo(pct: Int): ForegroundInfo {
        val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        return ForegroundInfo(id, build(pct), type)
    }

    fun update(pct: Int) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        NotificationManagerCompat.from(context).notify(id, build(pct))
    }

    fun cancel() = NotificationManagerCompat.from(context).cancel(id)
}
