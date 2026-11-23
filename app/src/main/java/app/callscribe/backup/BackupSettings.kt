package app.callscribe.backup

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.backupStore by preferencesDataStore(name = "drive_backup")

/** Drive backup switches and status. Kept apart from [app.callscribe.data.AppSettings]: never backed up itself. */
data class DriveBackupSettings(
    val enabled: Boolean = false,
    val includeAudio: Boolean = false,
    val wifiOnly: Boolean = true,
    val chargingOnly: Boolean = false,
    val lastSuccessMs: Long = 0,
    val lastAttemptMs: Long = 0,
    val lastResult: String = "",
    /** Google needs the user (access revoked / expired): background runs stop until they reconnect. */
    val needsSignIn: Boolean = false,
)

class BackupSettingsRepository(private val context: Context) {
    private object K {
        val enabled = booleanPreferencesKey("enabled")
        val audio = booleanPreferencesKey("include_audio")
        val wifi = booleanPreferencesKey("wifi_only")
        val charging = booleanPreferencesKey("charging_only")
        val success = longPreferencesKey("last_success_ms")
        val attempt = longPreferencesKey("last_attempt_ms")
        val result = stringPreferencesKey("last_result")
        val signIn = booleanPreferencesKey("needs_sign_in")
    }

    val flow: Flow<DriveBackupSettings> = context.backupStore.data.map { p ->
        val d = DriveBackupSettings()
        DriveBackupSettings(
            enabled = p[K.enabled] ?: d.enabled,
            includeAudio = p[K.audio] ?: d.includeAudio,
            wifiOnly = p[K.wifi] ?: d.wifiOnly,
            chargingOnly = p[K.charging] ?: d.chargingOnly,
            lastSuccessMs = p[K.success] ?: d.lastSuccessMs,
            lastAttemptMs = p[K.attempt] ?: d.lastAttemptMs,
            lastResult = p[K.result] ?: d.lastResult,
            needsSignIn = p[K.signIn] ?: d.needsSignIn,
        )
    }

    suspend fun get() = flow.first()

    suspend fun update(transform: (DriveBackupSettings) -> DriveBackupSettings) {
        context.backupStore.edit { p ->
            val cur = DriveBackupSettings(
                p[K.enabled] ?: false, p[K.audio] ?: false, p[K.wifi] ?: true, p[K.charging] ?: false,
                p[K.success] ?: 0, p[K.attempt] ?: 0, p[K.result] ?: "", p[K.signIn] ?: false,
            )
            val s = transform(cur)
            p[K.enabled] = s.enabled
            p[K.audio] = s.includeAudio
            p[K.wifi] = s.wifiOnly
            p[K.charging] = s.chargingOnly
            p[K.success] = s.lastSuccessMs
            p[K.attempt] = s.lastAttemptMs
            p[K.result] = s.lastResult
            p[K.signIn] = s.needsSignIn
        }
    }
}
