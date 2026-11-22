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
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.callscribe.rules.AutoMode
import app.callscribe.rules.AutoRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.dataStore by preferencesDataStore(name = "settings")

data class AppSettings(
    val pathPrefixes: List<String> = listOf("Recordings/Call"),
    val autoRules: AutoRules = AutoRules(),
    val autoAnalyze: Boolean = true,
    val autoProcessFromSec: Long = 0,
    val webhookUrl: String = "",
    val autoExport: Boolean = false,
    val transcriberId: String = "ivrit-runpod",
    val analyzerId: String = "claude",
    val runpodEndpointId: String = "",
    val runpodModel: String = "ivrit-ai/whisper-large-v3-turbo-ct2",
    /** USD per second of GPU execution, used for the cost estimate. User-editable; depends on the GPU chosen on RunPod. */
    val runpodPricePerSec: Double = 0.00019,
    val diarize: Boolean = false,
    val claudeModel: String = "claude-opus-5-5",
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
        val rpModel = stringPreferencesKey("runpod_model")
        val rpPrice = doublePreferencesKey("runpod_price_per_sec")
        val diarize = booleanPreferencesKey("diarize")
        val claudeModel = stringPreferencesKey("claude_model")
    }

    val flow: Flow<AppSettings> = context.dataStore.data.map { p -> p.toSettings() }

    suspend fun get(): AppSettings = flow.first()

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        val r = d.autoRules
        return AppSettings(
            pathPrefixes = this[K.prefixes]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: d.pathPrefixes,
            autoRules = AutoRules(
                mode = this[K.mode]?.let { runCatching { AutoMode.valueOf(it) }.getOrNull() } ?: r.mode,
                spam = this[K.spam] ?: r.spam,
                fraud = this[K.fraud] ?: r.fraud,
                otherLabels = this[K.other] ?: r.otherLabels,
                unknownNumbers = this[K.unknown] ?: r.unknownNumbers,
                privateNumbers = this[K.private] ?: r.privateNumbers,
                contacts = this[K.contacts] ?: r.contacts,
            ),
            autoAnalyze = this[K.autoAnalyze] ?: d.autoAnalyze,
            autoProcessFromSec = this[K.autoFrom] ?: d.autoProcessFromSec,
            webhookUrl = this[K.webhook] ?: d.webhookUrl,
            autoExport = this[K.autoExport] ?: d.autoExport,
            transcriberId = this[K.transcriber] ?: d.transcriberId,
            analyzerId = this[K.analyzer] ?: d.analyzerId,
            runpodEndpointId = this[K.rpEndpoint] ?: d.runpodEndpointId,
            runpodModel = this[K.rpModel] ?: d.runpodModel,
            runpodPricePerSec = this[K.rpPrice] ?: d.runpodPricePerSec,
            diarize = this[K.diarize] ?: d.diarize,
            claudeModel = this[K.claudeModel] ?: d.claudeModel,
        )
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { p ->
            val s = transform(p.toSettings())
            p[K.prefixes] = s.pathPrefixes.joinToString(",")
            p[K.mode] = s.autoRules.mode.name
            p[K.spam] = s.autoRules.spam
            p[K.fraud] = s.autoRules.fraud
            p[K.other] = s.autoRules.otherLabels
            p[K.unknown] = s.autoRules.unknownNumbers
            p[K.private] = s.autoRules.privateNumbers
            p[K.contacts] = s.autoRules.contacts
            p[K.autoAnalyze] = s.autoAnalyze
            p[K.autoFrom] = s.autoProcessFromSec
            p[K.webhook] = s.webhookUrl
            p[K.autoExport] = s.autoExport
            p[K.transcriber] = s.transcriberId
            p[K.analyzer] = s.analyzerId
            p[K.rpEndpoint] = s.runpodEndpointId
            p[K.rpModel] = s.runpodModel
            p[K.rpPrice] = s.runpodPricePerSec
            p[K.diarize] = s.diarize
            p[K.claudeModel] = s.claudeModel
        }
    }
}

/** API keys, encrypted with an Android Keystore AES-GCM key. Stored in `secrets.xml`, which is excluded from backup. */
class SecretStore(context: Context) {
    enum class Key { RUNPOD_API_KEY, ANTHROPIC_API_KEY, WEBHOOK_TOKEN }

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
