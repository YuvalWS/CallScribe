package app.callscribe.providers.ondevice

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

sealed interface ModelState {
    data object NotInstalled : ModelState
    data class Downloading(val doneBytes: Long, val totalBytes: Long) : ModelState
    data object Installed : ModelState
    data class Failed(val message: String) : ModelState
}

/**
 * Downloads on-device models on demand into app-specific storage (`noBackupFilesDir/models/<id>`: private, not
 * backed up, removed with the app). Never bundled in the APK.
 */
class ModelStore(val context: Context, private val http: OkHttpClient = app.callscribe.providers.HttpLog.builder().build()) {
    private val root = File(context.noBackupFilesDir, "models")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val states = mutableMapOf<String, MutableStateFlow<ModelState>>()
    private val jobs = mutableMapOf<String, Job>()

    fun dir(m: OnDeviceModel) = File(root, m.id)
    fun path(m: OnDeviceModel, name: String) = File(dir(m), m.file(name).localName).absolutePath

    fun isInstalled(m: OnDeviceModel) = m.files.all { File(dir(m), it.localName).length() == it.bytes }

    /** Bytes the model occupies on the phone right now (including a partial download). */
    fun usedBytes(m: OnDeviceModel) = dir(m).listFiles()?.sumOf { it.length() } ?: 0L

    @Synchronized
    fun state(m: OnDeviceModel): StateFlow<ModelState> = states.getOrPut(m.id) {
        MutableStateFlow(if (isInstalled(m)) ModelState.Installed else ModelState.NotInstalled)
    }

    private fun set(m: OnDeviceModel, s: ModelState) { (state(m) as MutableStateFlow).value = s }

    @Synchronized
    fun startDownload(m: OnDeviceModel) {
        if (jobs[m.id]?.isActive == true) return
        jobs[m.id] = scope.launch {
            try {
                app.callscribe.Logx.i("ModelStore", "downloading ${m.id} (${m.totalBytes / 1_000_000} MB)")
                download(m)
                set(m, ModelState.Installed)
                app.callscribe.Logx.i("ModelStore", "${m.id} installed")
            } catch (e: kotlinx.coroutines.CancellationException) {
                set(m, if (isInstalled(m)) ModelState.Installed else ModelState.NotInstalled)
                throw e
            } catch (e: Exception) {
                app.callscribe.Logx.w("ModelStore", "download of ${m.id} failed", e)
                set(m, ModelState.Failed(e.message ?: e.javaClass.simpleName))
            }
        }
    }

    @Synchronized
    fun cancelDownload(m: OnDeviceModel) { jobs.remove(m.id)?.cancel() }

    fun delete(m: OnDeviceModel) {
        cancelDownload(m)
        dir(m).deleteRecursively()
        set(m, ModelState.NotInstalled)
    }

    private suspend fun download(m: OnDeviceModel) {
        val dir = dir(m).apply { mkdirs() }
        val missing = m.files.filter { File(dir, it.localName).length() != it.bytes }
        val need = missing.sumOf { it.bytes }
        if (dir.usableSpace < need + 50_000_000) {
            throw IOException("Not enough free space: need %.0f MB".format(need / 1e6))
        }
        var done = m.totalBytes - need
        set(m, ModelState.Downloading(done, m.totalBytes))
        for (f in missing) {
            val part = File(dir, f.localName + ".part")
            val req = Request.Builder().url(m.url(f)).build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("Download failed: HTTP ${resp.code}")
                val md = MessageDigest.getInstance("SHA-256")
                val buf = ByteArray(1 shl 16)
                var lastReport = 0L
                resp.body.byteStream().use { input ->
                    part.outputStream().use { out ->
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n); md.update(buf, 0, n)
                            done += n
                            if (done - lastReport > 500_000) { lastReport = done; set(m, ModelState.Downloading(done, m.totalBytes)) }
                        }
                    }
                }
                val sha = md.digest().joinToString("") { "%02x".format(it) }
                if (sha != f.sha256 || part.length() != f.bytes) {
                    part.delete()
                    throw IOException("Downloaded ${f.localName} is corrupt (checksum mismatch)")
                }
            }
            val target = File(dir, f.localName)
            target.delete()
            if (!part.renameTo(target)) throw IOException("Could not save ${f.localName}")
        }
    }
}
