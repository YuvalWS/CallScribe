package app.callscribe

import android.content.Context
import android.util.Log
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The app's logger. Every line goes to logcat as `CallScribe/<area>` and to a small rotating file that
 * Settings → Troubleshooting can share, so problems can be debugged without a cable.
 *
 * Levels: [e] errors, [w] something went wrong but the app recovered, [i] important events (always on),
 * [d] step-by-step detail — off by default, switched on at runtime, never by a code change:
 *  - in the app: Settings → Troubleshooting → Detailed logs, or
 *  - over USB: `adb shell setprop log.tag.CallScribe DEBUG` (back to normal: `... INFO`; resets on reboot).
 *
 * Privacy: log ids, counts, sizes, statuses and error messages — never phone numbers, contact names, transcripts,
 * summaries or keys.
 */
object Logx {
    const val MASTER = "CallScribe"
    private const val MAX_BYTES = 512 * 1024
    private val TS = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")

    @Volatile private var detailedPref = false
    @Volatile private var file: File? = null
    private var prefsFile: android.content.SharedPreferences? = null

    fun init(ctx: Context) {
        val p = ctx.getSharedPreferences("logging", Context.MODE_PRIVATE)
        prefsFile = p
        detailedPref = p.getBoolean("detailed", false)
        file = File(ctx.filesDir, "logs/app.log").apply { parentFile?.mkdirs() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, ex ->
            runCatching { e("Crash", "uncaught on ${t.name}", ex) }
            previous?.uncaughtException(t, ex)
        }
        i("App", "started, version ${runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()}, detailed logs ${if (detailed) "on" else "off"}")
    }

    /** Detailed (debug) logging: the in-app switch, or `setprop log.tag.CallScribe DEBUG`. */
    val detailed: Boolean get() = detailedPref || Log.isLoggable(MASTER, Log.DEBUG)

    fun setDetailed(on: Boolean) {
        detailedPref = on
        prefsFile?.edit()?.putBoolean("detailed", on)?.apply()
        i("App", "detailed logs ${if (on) "on" else "off"}")
    }

    fun d(area: String, msg: String) {
        if (!detailed) return
        Log.d("$MASTER/$area", msg); write('D', area, msg, null)
    }
    fun i(area: String, msg: String) { Log.i("$MASTER/$area", msg); write('I', area, msg, null) }
    fun w(area: String, msg: String, t: Throwable? = null) { Log.w("$MASTER/$area", msg, t); write('W', area, msg, t) }
    fun e(area: String, msg: String, t: Throwable? = null) { Log.e("$MASTER/$area", msg, t); write('E', area, msg, t) }

    /** The log file plus its previous rotation, oldest first, for sharing. */
    fun text(): String = listOfNotNull(file?.let { File(it.path + ".1") }, file)
        .filter { it.exists() }.joinToString("") { it.readText() }

    fun clear() { file?.let { File(it.path + ".1").delete(); it.delete() } }

    @Synchronized
    private fun write(level: Char, area: String, msg: String, t: Throwable?) {
        val f = file ?: return
        runCatching {
            if (f.length() > MAX_BYTES) f.renameTo(File(f.path + ".1"))
            val err = t?.let { "\n" + Log.getStackTraceString(it).lineSequence().take(15).joinToString("\n") }.orEmpty()
            f.appendText("${TS.format(LocalDateTime.now())} $level $area: $msg$err\n")
        }
    }
}
