package app.callscribe.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Drive REST v3 over OkHttp — no Google API client library.
 * [token] returns an OAuth access token; `forceRefresh = true` after a 401 (the old token is dropped first).
 */
class DriveRestApi(
    private val token: suspend (forceRefresh: Boolean) -> String,
    private val http: OkHttpClient = app.callscribe.providers.HttpLog.builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .build(),
    private val base: String = "https://www.googleapis.com",
) : DriveApi {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json; charset=UTF-8".toMediaType()
    private val fileFields = "id,name,appProperties,size"
    private val folderMime = "application/vnd.google-apps.folder"

    override suspend fun findFolders(propKey: String, propValue: String, parentId: String?): List<DriveFile> {
        val q = buildString {
            append("mimeType = '$folderMime' and trashed = false")
            append(" and appProperties has { key='${esc(propKey)}' and value='${esc(propValue)}' }")
            if (parentId != null) append(" and '${esc(parentId)}' in parents")
        }
        return query(q)
    }

    override suspend fun createFolder(name: String, parentId: String?, appProperties: Map<String, String>): DriveFile {
        val meta = metadata(name, parentId, appProperties, mime = folderMime)
        val url = "$base/drive/v3/files".toHttpUrl().newBuilder().addQueryParameter("fields", fileFields).build()
        return call { Request.Builder().url(url).post(meta.toString().toRequestBody(jsonType)) }.use { parseFile(it) }
    }

    override suspend fun list(folderId: String): List<DriveFile> = query("'${esc(folderId)}' in parents and trashed = false")

    private suspend fun query(q: String): List<DriveFile> {
        val out = mutableListOf<DriveFile>()
        var page: String? = null
        do {
            val url = "$base/drive/v3/files".toHttpUrl().newBuilder()
                .addQueryParameter("q", q)
                .addQueryParameter("spaces", "drive")
                .addQueryParameter("pageSize", "1000")
                .addQueryParameter("fields", "nextPageToken,files($fileFields)")
                .apply { page?.let { addQueryParameter("pageToken", it) } }
                .build()
            val body = call { Request.Builder().url(url).get() }.use { it.body.string() }
            val obj = json.parseToJsonElement(body).jsonObject
            obj["files"]?.jsonArray?.forEach { out += toFile(it.jsonObject) }
            page = obj["nextPageToken"]?.jsonPrimitive?.content
        } while (page != null)
        return out
    }

    override suspend fun upload(
        parentId: String,
        name: String,
        appProperties: Map<String, String>,
        content: UploadContent,
        existingId: String?,
    ): DriveFile {
        // On update the parent can't be set through metadata.
        val meta = metadata(name, if (existingId == null) parentId else null, appProperties)
        val path = if (existingId == null) "$base/upload/drive/v3/files" else "$base/upload/drive/v3/files/$existingId"
        val method = if (existingId == null) "POST" else "PATCH"
        return when (content) {
            is UploadContent.Bytes -> {
                val url = path.toHttpUrl().newBuilder()
                    .addQueryParameter("uploadType", "multipart").addQueryParameter("fields", fileFields).build()
                val body = MultipartBody.Builder().setType("multipart/related".toMediaType())
                    .addPart(meta.toString().toRequestBody(jsonType))
                    .addPart(content.bytes.toRequestBody(content.mimeType.toMediaType()))
                    .build()
                call { Request.Builder().url(url).method(method, body) }.use { parseFile(it) }
            }
            is UploadContent.Stream -> {
                // Resumable session: metadata first, then the bytes streamed in one PUT.
                val url = path.toHttpUrl().newBuilder()
                    .addQueryParameter("uploadType", "resumable").addQueryParameter("fields", fileFields).build()
                val session = call {
                    Request.Builder().url(url).method(method, meta.toString().toRequestBody(jsonType))
                        .header("X-Upload-Content-Type", content.mimeType)
                        .header("X-Upload-Content-Length", content.length.toString())
                }.use { it.header("Location") ?: throw DriveHttpException(it.code, "no upload session") }
                val body = object : RequestBody() {
                    override fun contentType() = content.mimeType.toMediaType()
                    override fun contentLength() = content.length
                    override fun writeTo(sink: BufferedSink) {
                        content.open().use { sink.writeAll(it.source()) }
                    }
                }
                call { Request.Builder().url(session).put(body) }.use { parseFile(it) }
            }
        }
    }

    override suspend fun download(fileId: String): ByteArray =
        call { Request.Builder().url(mediaUrl(fileId)).get() }.use { it.body.bytes() }

    override suspend fun downloadTo(fileId: String, sink: (InputStream) -> Unit) {
        call { Request.Builder().url(mediaUrl(fileId)).get() }.use { r -> withContext(Dispatchers.IO) { r.body.byteStream().use(sink) } }
    }

    override suspend fun trash(fileId: String) {
        val body = buildJsonObject { put("trashed", true) }.toString().toRequestBody(jsonType)
        call { Request.Builder().url("$base/drive/v3/files/$fileId").patch(body) }.close()
    }

    private fun mediaUrl(id: String) = "$base/drive/v3/files/$id".toHttpUrl().newBuilder().addQueryParameter("alt", "media").build()

    private fun metadata(name: String, parentId: String?, props: Map<String, String>, mime: String? = null): JsonObject =
        buildJsonObject {
            put("name", name)
            mime?.let { put("mimeType", it) }
            if (parentId != null) putJsonArray("parents") { add(kotlinx.serialization.json.JsonPrimitive(parentId)) }
            putJsonObject("appProperties") { props.forEach { (k, v) -> put(k, v) } }
        }

    private fun parseFile(r: Response): DriveFile = toFile(json.parseToJsonElement(r.body.string()).jsonObject)

    private fun toFile(o: JsonObject) = DriveFile(
        id = o["id"]!!.jsonPrimitive.content,
        name = o["name"]?.jsonPrimitive?.content.orEmpty(),
        appProperties = o["appProperties"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty(),
        size = o["size"]?.jsonPrimitive?.content?.toLongOrNull(),
    )

    /** Executes with auth; retries once with a fresh token on 401. Throws on any other non-2xx. */
    private suspend fun call(build: () -> Request.Builder): Response = withContext(Dispatchers.IO) {
        var refreshed = false
        while (true) {
            val t = token(refreshed)
            val r = http.newCall(build().header("Authorization", "Bearer $t").build()).execute()
            if (r.isSuccessful) return@withContext r
            val msg = r.body.string().take(300)
            r.close()
            if (r.code == 401 && !refreshed) { refreshed = true; continue }
            if (r.code == 401) throw DriveAuthRequiredException("Google access expired — sign in again")
            throw DriveHttpException(r.code, msg)
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")
}
