package app.callscribe.backup

import java.io.InputStream

/** A file or folder in the user's Drive, as far as the backup cares. */
data class DriveFile(
    val id: String,
    val name: String,
    val appProperties: Map<String, String> = emptyMap(),
    val size: Long? = null,
)

/** What to upload: small payloads as bytes, recordings streamed. */
sealed interface UploadContent {
    val mimeType: String
    val length: Long

    class Bytes(val bytes: ByteArray, override val mimeType: String = "application/json") : UploadContent {
        override val length get() = bytes.size.toLong()
    }

    class Stream(override val length: Long, override val mimeType: String, val open: () -> InputStream) : UploadContent
}

/** Thrown when Google needs the user to sign in / grant access again (interactive step required). */
class DriveAuthRequiredException(message: String = "Google sign-in needed") : Exception(message)

/** HTTP failure from Drive. */
class DriveHttpException(val code: Int, message: String) : Exception("Drive HTTP $code: $message")

/**
 * The few Drive v3 calls the backup needs. [DriveRestApi] is the real one; tests use an in-memory fake.
 * All files the app manages carry [appProperties] so they are found by property, not by name.
 */
interface DriveApi {
    /** Non-trashed folders anywhere with this app property (with `drive.file`, only folders this app created). */
    suspend fun findFolders(propKey: String, propValue: String, parentId: String? = null): List<DriveFile>

    suspend fun createFolder(name: String, parentId: String?, appProperties: Map<String, String>): DriveFile

    /** Non-trashed children of a folder. */
    suspend fun list(folderId: String): List<DriveFile>

    /** Creates a file in [parentId] or, with [existingId], replaces that file's content and properties. */
    suspend fun upload(
        parentId: String,
        name: String,
        appProperties: Map<String, String>,
        content: UploadContent,
        existingId: String? = null,
    ): DriveFile

    suspend fun download(fileId: String): ByteArray

    /** Streams the content into [sink]; for recordings. */
    suspend fun downloadTo(fileId: String, sink: (InputStream) -> Unit)

    /** Moves to the Drive trash (recoverable for 30 days). */
    suspend fun trash(fileId: String)
}
