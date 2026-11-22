package app.callscribe.detect

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.security.MessageDigest

/** Lists call recordings from MediaStore and hashes files. */
class RecordingScanner(private val context: Context) {

    fun list(prefixes: List<String>): List<MediaFile> {
        if (prefixes.isEmpty()) return emptyList()
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATE_MODIFIED,
        )
        val selection = prefixes.joinToString(" OR ", "(", ")") { "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?" } +
            " AND ${MediaStore.Audio.Media.IS_PENDING} = 0 AND ${MediaStore.Audio.Media.SIZE} > 0"
        val args = prefixes.map { it.trim().trimEnd('/') + "%" }.toTypedArray()

        val out = mutableListOf<MediaFile>()
        context.contentResolver.query(collection, projection, selection, args, null)?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val iPath = c.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
            val iSize = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val iAdded = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val iMod = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                out += MediaFile(
                    mediaId = id,
                    uri = ContentUris.withAppendedId(collection, id).toString(),
                    displayName = c.getString(iName) ?: "",
                    relativePath = c.getString(iPath) ?: "",
                    sizeBytes = c.getLong(iSize),
                    durationMs = c.getLong(iDur),
                    dateAddedSec = c.getLong(iAdded),
                    dateModifiedSec = c.getLong(iMod),
                )
            }
        }
        return out
    }

    fun sha256(file: MediaFile): String {
        val md = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(Uri.parse(file.uri))!!.use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
