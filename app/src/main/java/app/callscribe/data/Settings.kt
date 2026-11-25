package app.callscribe.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.dataStore by preferencesDataStore(name = "settings")

const val DEFAULT_RUNPOD_PRICE = 0.00034
const val OLD_RUNPOD_PRICE = 0.00019

/** Non-secret settings only (API keys live in [SecretStore]); backed up to Drive as JSON. */
@Serializable
data class AppSettings(
    val pathPrefixes: List<String> = listOf("Recordings/Call"),
    val autoProcessFromSec: Long = 0,
    val webhookUrl: String = "",
    val autoExport: Boolean = false,
    val transcriberId: String = "ivrit-runpod",
    val analyzerId: String = "gemini",
    val runpodEndpointId: String = "",
    /** Only changed by debug builds (fake server tests). */
    val runpodBaseUrl: String = "https://api.runpod.ai/v2",
    val runpodModel: String = "ivrit-ai/whisper-large-v3-turbo-ct2",
    /**
     * USD per second of worker time, for the cost estimate. Depends on which GPU RunPod gives the job (the default
     * endpoint allows 16–48 GB cards, $0.00016–0.00034/s); starts at the dearest and is corrected from the account
     * balance after each bulk run. User-editable.
     */
    val runpodPricePerSec: Double = DEFAULT_RUNPOD_PRICE,
    val diarize: Boolean = false,
    val claudeModel: String = "claude-opus-5-5",
    val geminiModel: String = "gemini-3.5-flash-lite",
    /** Requests per minute allowed by the user's Gemini tier (free tier: 15). */
    val geminiRpm: Int = 15,
    /** USD per million tokens; 0 on the free tier. */
    val geminiPriceIn: Double = 0.0,
    val geminiPriceOut: Double = 0.0,
    val openrouterModel: String = "anthropic/claude-haiku-5.5",
    /** Master switch for automatic processing of new calls (per-group policies decide the rest). */
    val autoEnabled: Boolean = true,
    /** First-run setup finished (or skipped). See docs/plans/onboarding.md. */
    val onboardingDone: Boolean = false,
    /** The user closed the "setup incomplete" banner on the call list. */
    val setupBannerDismissed: Boolean = false,
    /** On-device transcription: CPU threads. 2 was fastest on a Galaxy S20 FE (1: 29 s, 2: 25 s, 4: 35 s per minute of audio). */
    val onDeviceThreads: Int = 2,
    /** On-device transcription of *automatic* jobs waits until the phone is charging (manual taps run at once). */
    val onDeviceOnlyWhileCharging: Boolean = true,
)

class SettingsRepository(private val context: Context) {
    private object K {
        val prefixes = stringPreferencesKey("path_prefixes")
        val mode = stringPreferencesKey("auto_mode")
        val spam = booleanPreferencesKey("rule_spam")
        val fraud = booleanPreferencesKey("rule_fraud")
        val other = booleanPreferencesKey("rule_other")
        val unknown = booleanPreferencesKey("rule_unknown")
        val private = booleanPreferencesKey("rule_private")
        val contacts = booleanPreferencesKey("rule_contacts")
        val autoAnalyze = booleanPreferencesKey("auto_analyze")
        val autoFrom = longPreferencesKey("auto_from_sec")
        val webhook = stringPreferencesKey("webhook_url")
        val autoExport = booleanPreferencesKey("auto_export")
        val transcriber = stringPreferencesKey("transcriber_id")
        val analyzer = stringPreferencesKey("analyzer_id")
        val rpEndpoint = stringPreferencesKey("runpod_endpoint")
        val rpBase = stringPreferencesKey("runpod_base_url")
        val rpModel = stringPreferencesKey("runpod_model")
        val rpPrice = doublePreferencesKey("runpod_price_per_sec")
        val diarize = booleanPreferencesKey("diarize")
        val claudeModel = stringPreferencesKey("claude_model")
        val gemModel = stringPreferencesKey("gemini_model")
        val gemRpm = intPreferencesKey("gemini_rpm")
        val gemIn = doublePreferencesKey("gemini_price_in")
        val gemOut = doublePreferencesKey("gemini_price_out")
        val orModel = stringPreferencesKey("openrouter_model")
        val autoEnabled = booleanPreferencesKey("auto_enabled")
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val bannerDismissed = booleanPreferencesKey("setup_banner_dismissed")
        val odThreads = intPreferencesKey("ondevice_threads")
        val odCharging = booleanPreferencesKey("ondevice_only_charging")
    }

    val flow: Flow<AppSettings> = context.dataStore.data.map { p -> p.toSettings() }

    suspend fun get(): AppSettings = flow.first()

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            pathPrefixes = this[K.prefixes]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: d.pathPrefixes,
            autoProcessFromSec = this[K.autoFrom] ?: d.autoProcessFromSec,
            webhookUrl = this[K.webhook] ?: d.webhookUrl,
            autoExport = this[K.autoExport] ?: d.autoExport,
            transcriberId = this[K.transcriber] ?: d.transcriberId,
            analyzerId = this[K.analyzer] ?: d.analyzerId,
            runpodEndpointId = this[K.rpEndpoint] ?: d.runpodEndpointId,
            runpodBaseUrl = this[K.rpBase] ?: d.runpodBaseUrl,
            runpodModel = this[K.rpModel] ?: d.runpodModel,
            // 0.00019 was the old default (the cheapest GPU only): treat it as unset.
            runpodPricePerSec = this[K.rpPrice]?.takeIf { it != OLD_RUNPOD_PRICE } ?: d.runpodPricePerSec,
            diarize = this[K.diarize] ?: d.diarize,
            claudeModel = this[K.claudeModel] ?: d.claudeModel,
            geminiModel = this[K.gemModel] ?: d.geminiModel,
            geminiRpm = this[K.gemRpm] ?: d.geminiRpm,
            geminiPriceIn = this[K.gemIn] ?: d.geminiPriceIn,
            geminiPriceOut = this[K.gemOut] ?: d.geminiPriceOut,
            openrouterModel = this[K.orModel] ?: d.openrouterModel,
            autoEnabled = this[K.autoEnabled] ?: d.autoEnabled,
            // Installs from before onboarding existed already have saved settings: don't force them through it.
            onboardingDone = this[K.onboardingDone] ?: (this[K.transcriber] != null),
            setupBannerDismissed = this[K.bannerDismissed] ?: d.setupBannerDismissed,
            onDeviceThreads = this[K.odThreads] ?: d.onDeviceThreads,
            onDeviceOnlyWhileCharging = this[K.odCharging] ?: d.onDeviceOnlyWhileCharging,
        )
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { p ->
            val s = transform(p.toSettings())
            p[K.prefixes] = s.pathPrefixes.joinToString(",")
            p[K.autoFrom] = s.autoProcessFromSec
            p[K.webhook] = s.webhookUrl
            p[K.autoExport] = s.autoExport
            p[K.transcriber] = s.transcriberId
            p[K.analyzer] = s.analyzerId
            p[K.rpEndpoint] = s.runpodEndpointId
            p[K.rpBase] = s.runpodBaseUrl
            p[K.rpModel] = s.runpodModel
            p[K.rpPrice] = s.runpodPricePerSec
            p[K.diarize] = s.diarize
            p[K.claudeModel] = s.claudeModel
            p[K.gemModel] = s.geminiModel
            p[K.gemRpm] = s.geminiRpm
            p[K.gemIn] = s.geminiPriceIn
            p[K.gemOut] = s.geminiPriceOut
            p[K.orModel] = s.openrouterModel
            p[K.autoEnabled] = s.autoEnabled
            p[K.onboardingDone] = s.onboardingDone
            p[K.bannerDismissed] = s.setupBannerDismissed
            p[K.odThreads] = s.onDeviceThreads
            p[K.odCharging] = s.onDeviceOnlyWhileCharging
        }
    }
}

/** API keys, encrypted with an Android Keystore AES-GCM key. Stored in `secrets.xml`, which is excluded from backup. */
class SecretStore(context: Context) {
    enum class Key { RUNPOD_API_KEY, ANTHROPIC_API_KEY, GEMINI_API_KEY, OPENROUTER_API_KEY, WEBHOOK_TOKEN }

    private val prefs: SharedPreferences = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)
    private val alias = "callscribe_secrets"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    fun get(k: Key): String? {
        val stored = prefs.getString(k.name, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
            String(c.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
        }.getOrNull()
    }

    fun set(k: Key, value: String?) {
        if (value.isNullOrBlank()) {
            prefs.edit().remove(k.name).apply(); return
        }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(value.trim().toByteArray(Charsets.UTF_8))
        prefs.edit().putString(k.name, Base64.encodeToString(out, Base64.NO_WRAP)).apply()
    }

    fun has(k: Key) = prefs.contains(k.name)

    /** For display: "sk-a…f3c9". */
    fun masked(k: Key): String? = get(k)?.let { if (it.length <= 8) "••••" else it.take(4) + "…" + it.takeLast(4) }
}
