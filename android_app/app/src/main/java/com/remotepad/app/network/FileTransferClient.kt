package com.remotepad.app.network

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

data class PcQuickDir(
    val label: String,
    val path: String,
)

data class PcDrive(
    val label: String,
    val path: String,
    val totalBytes: Long = 0L,
    val freeBytes: Long = 0L,
)

data class PcFileInfo(
    val pcName: String,
    val receivedDir: String,
    val quickDirs: List<PcQuickDir>,
    val drives: List<PcDrive>,
)

data class PcFileEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val modifiedMs: Long,
)

data class PcDirectoryListing(
    val path: String,
    val parent: String,
    val entries: List<PcFileEntry>,
)

data class LocalFileMeta(
    val displayName: String,
    val size: Long,
)

data class UploadedFileResult(
    val savedName: String,
    val savedPath: String,
    val size: Long,
)

data class DownloadedFileResult(
    val displayPath: String,
    val savedUri: Uri?,
    val mimeType: String,
    val size: Long,
)

class FileTransferClient {
    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    fun queryLocalFileMeta(context: Context, uri: Uri): LocalFileMeta {
        var name: String? = null
        var size = -1L
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIdx >= 0) name = cursor.getString(nameIdx)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
            }
        }
        val safeName = name?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "file_${System.currentTimeMillis()}"
        return LocalFileMeta(displayName = safeName, size = size)
    }

    fun fetchInfo(host: String, filePort: Int): PcFileInfo {
        val url = baseUrl(host, filePort)
            .addPathSegments("api/info")
            .build()
        val request = Request.Builder().url(url).get().build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException(extractError(body) ?: "HTTP ${response.code}")
            }
            return parseInfoJson(body, fallbackName = host)
        }
    }

    fun parseInfoJson(body: String, fallbackName: String): PcFileInfo {
        val obj = json.parseToJsonElement(body).jsonObject
        val pcName = obj["pc_name"]?.jsonPrimitive?.contentOrNull ?: fallbackName
        val receivedDir = obj["received_dir"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val quickDirs = obj["quick_dirs"]?.jsonArray?.mapNotNull { el ->
            val item = el.jsonObject
            val label = item["label"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val path = item["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            PcQuickDir(label, path)
        }.orEmpty()
        val drives = obj["drives"]?.jsonArray?.mapNotNull { el ->
            val item = el.jsonObject
            val label = item["label"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val path = item["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val totalBytes = item["total_bytes"]?.jsonPrimitive?.longOrNull ?: 0L
            val freeBytes = item["free_bytes"]?.jsonPrimitive?.longOrNull ?: 0L
            PcDrive(
                label = label,
                path = path,
                totalBytes = totalBytes,
                freeBytes = freeBytes,
            )
        }.orEmpty()
        return PcFileInfo(
            pcName = pcName,
            receivedDir = receivedDir,
            quickDirs = quickDirs,
            drives = drives,
        )
    }

    fun listDirectory(host: String, filePort: Int, path: String?): PcDirectoryListing {
        val urlBuilder = baseUrl(host, filePort).addPathSegments("api/list")
        if (!path.isNullOrBlank()) {
            urlBuilder.addQueryParameter("path", path)
        }
        val request = Request.Builder().url(urlBuilder.build()).get().build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException(extractError(body) ?: "HTTP ${response.code}")
            }
            return parseListJson(body)
        }
    }

    fun parseListJson(body: String): PcDirectoryListing {
        val obj = json.parseToJsonElement(body).jsonObject
        val resolvedPath = obj["path"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val parent = obj["parent"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val entries = obj["entries"]?.jsonArray?.mapNotNull { el ->
            val item = el.jsonObject
            val name = item["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val entryPath = item["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val isDir = item["is_dir"]?.jsonPrimitive?.booleanOrNull ?: false
            val size = item["size"]?.jsonPrimitive?.longOrNull ?: 0L
            val modifiedMs = item["modified_ms"]?.jsonPrimitive?.longOrNull ?: 0L
            PcFileEntry(
                name = name,
                path = entryPath,
                isDir = isDir,
                size = size,
                modifiedMs = modifiedMs,
            )
        }.orEmpty()
        return PcDirectoryListing(path = resolvedPath, parent = parent, entries = entries)
    }

    fun uploadFile(
        context: Context,
        host: String,
        filePort: Int,
        targetDir: String?,
        uri: Uri,
        meta: LocalFileMeta,
        onCallReady: (Call) -> Unit,
        onProgress: (transferred: Long, total: Long) -> Unit,
    ): UploadedFileResult {
        val urlBuilder = baseUrl(host, filePort)
            .addPathSegments("api/upload")
            .addQueryParameter("name", meta.displayName)
        if (!targetDir.isNullOrBlank()) {
            urlBuilder.addQueryParameter("dir", targetDir)
        }

        val streamBody = object : RequestBody() {
            override fun contentType(): MediaType = "application/octet-stream".toMediaType()

            override fun contentLength(): Long = if (meta.size >= 0L) meta.size else -1L

            override fun writeTo(sink: BufferedSink) {
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw IOException("Unable to open selected file")
                input.use { stream ->
                    val buffer = ByteArray(STREAM_BUFFER_BYTES)
                    var sent = 0L
                    var lastReportMs = 0L
                    while (true) {
                        val read = stream.read(buffer)
                        if (read <= 0) break
                        sink.write(buffer, 0, read)
                        sent += read
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastReportMs >= PROGRESS_REPORT_MS) {
                            lastReportMs = now
                            onProgress(sent, meta.size)
                        }
                    }
                    sink.flush()
                    onProgress(sent, if (meta.size >= 0L) meta.size else sent)
                }
            }
        }

        val request = Request.Builder()
            .url(urlBuilder.build())
            .post(streamBody)
            .build()

        val call = httpClient.newCall(request)
        onCallReady(call)
        call.execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException(extractError(body) ?: "Upload failed (HTTP ${response.code})")
            }
            val obj = json.parseToJsonElement(body).jsonObject
            return UploadedFileResult(
                savedName = obj["name"]?.jsonPrimitive?.contentOrNull ?: meta.displayName,
                savedPath = obj["path"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                size = obj["size"]?.jsonPrimitive?.longOrNull ?: meta.size.coerceAtLeast(0L),
            )
        }
    }

    fun downloadFile(
        context: Context,
        host: String,
        filePort: Int,
        remotePath: String,
        fileName: String,
        expectedSize: Long,
        onCallReady: (Call) -> Unit,
        onProgress: (transferred: Long, total: Long) -> Unit,
    ): DownloadedFileResult {
        val url = baseUrl(host, filePort)
            .addPathSegments("api/download")
            .addQueryParameter("path", remotePath)
            .build()

        val request = Request.Builder().url(url).get().build()
        val call = httpClient.newCall(request)
        onCallReady(call)

        val mimeType = guessMimeType(fileName)
        call.execute().use { response ->
            val respBody = response.body ?: throw IOException("Empty download response")
            if (!response.isSuccessful) {
                val errText = respBody.string()
                throw IOException(extractError(errText) ?: "Download failed (HTTP ${response.code})")
            }

            val total = respBody.contentLength().takeIf { it > 0L } ?: expectedSize

            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                downloadToMediaStore(context, respBody.byteStream(), fileName, mimeType, total, onProgress)
            } else {
                downloadToAppDownloads(context, respBody.byteStream(), fileName, mimeType, total, onProgress)
            }
        }
    }

    fun saveDownloadWithWriter(
        context: Context,
        fileName: String,
        expectedSize: Long,
        onProgress: (transferred: Long, total: Long) -> Unit,
        writer: (java.io.OutputStream) -> Long,
    ): DownloadedFileResult {
        val mimeType = guessMimeType(fileName)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/RemotePad")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val itemUri = resolver.insert(collection, values)
                ?: throw IOException("Could not create file in Downloads/RemotePad")
            try {
                val written = resolver.openOutputStream(itemUri)?.use { out ->
                    val bytes = writer(out)
                    out.flush()
                    bytes
                } ?: throw IOException("Could not open output stream")

                val doneValues = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                resolver.update(itemUri, doneValues, null, null)
                onProgress(written, if (expectedSize > 0L) expectedSize else written)
                DownloadedFileResult(
                    displayPath = "Downloads/RemotePad/$fileName",
                    savedUri = itemUri,
                    mimeType = mimeType,
                    size = written,
                )
            } catch (e: Exception) {
                runCatching { resolver.delete(itemUri, null, null) }
                throw e
            }
        } else {
            val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
            val outDir = File(baseDir, "RemotePad").apply { mkdirs() }
            val target = resolveUniqueLocalFile(outDir, fileName)
            try {
                val written = FileOutputStream(target).use { out ->
                    val bytes = writer(out)
                    out.flush()
                    bytes
                }
                onProgress(written, if (expectedSize > 0L) expectedSize else written)
                DownloadedFileResult(
                    displayPath = target.absolutePath,
                    savedUri = null,
                    mimeType = mimeType,
                    size = written,
                )
            } catch (e: Exception) {
                runCatching { target.delete() }
                throw e
            }
        }
    }

    private fun downloadToMediaStore(
        context: Context,
        inputStream: java.io.InputStream,
        fileName: String,
        mimeType: String,
        totalBytes: Long,
        onProgress: (transferred: Long, total: Long) -> Unit,
    ): DownloadedFileResult {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/RemotePad")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val itemUri = resolver.insert(collection, values)
            ?: throw IOException("Could not create file in Downloads/RemotePad")

        try {
            var written = 0L
            resolver.openOutputStream(itemUri)?.use { out ->
                inputStream.use { input ->
                    val buffer = ByteArray(STREAM_BUFFER_BYTES)
                    var lastReportMs = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        written += read
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastReportMs >= PROGRESS_REPORT_MS) {
                            lastReportMs = now
                            onProgress(written, totalBytes)
                        }
                    }
                    out.flush()
                }
            } ?: throw IOException("Could not open output stream")

            val doneValues = ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }
            resolver.update(itemUri, doneValues, null, null)
            onProgress(written, if (totalBytes > 0L) totalBytes else written)
            return DownloadedFileResult(
                displayPath = "Downloads/RemotePad/$fileName",
                savedUri = itemUri,
                mimeType = mimeType,
                size = written,
            )
        } catch (e: Exception) {
            runCatching { resolver.delete(itemUri, null, null) }
            throw e
        }
    }

    private fun downloadToAppDownloads(
        context: Context,
        inputStream: java.io.InputStream,
        fileName: String,
        mimeType: String,
        totalBytes: Long,
        onProgress: (transferred: Long, total: Long) -> Unit,
    ): DownloadedFileResult {
        val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        val outDir = File(baseDir, "RemotePad").apply { mkdirs() }
        val target = resolveUniqueLocalFile(outDir, fileName)

        try {
            var written = 0L
            FileOutputStream(target).use { out ->
                inputStream.use { input ->
                    val buffer = ByteArray(STREAM_BUFFER_BYTES)
                    var lastReportMs = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        written += read
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastReportMs >= PROGRESS_REPORT_MS) {
                            lastReportMs = now
                            onProgress(written, totalBytes)
                        }
                    }
                    out.flush()
                }
            }
            onProgress(written, if (totalBytes > 0L) totalBytes else written)
            return DownloadedFileResult(
                displayPath = target.absolutePath,
                savedUri = null,
                mimeType = mimeType,
                size = written,
            )
        } catch (e: Exception) {
            runCatching { target.delete() }
            throw e
        }
    }

    private fun resolveUniqueLocalFile(dir: File, name: String): File {
        val first = File(dir, name)
        if (!first.exists()) return first
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var idx = 1
        while (true) {
            val candidate = File(dir, "$stem ($idx)$ext")
            if (!candidate.exists()) return candidate
            idx++
        }
    }

    private fun baseUrl(host: String, port: Int): HttpUrl.Builder {
        return HttpUrl.Builder()
            .scheme("http")
            .host(host)
            .port(port.coerceIn(1, 65535))
    }

    private fun extractError(body: String): String? {
        return runCatching {
            json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
    }

    private fun guessMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return "application/octet-stream"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    fun shutdown() {
        httpClient.dispatcher.executorService.shutdown()
    }

    companion object {
        private const val STREAM_BUFFER_BYTES = 64 * 1024
        private const val PROGRESS_REPORT_MS = 80L

        fun formatBytes(bytes: Long): String {
            if (bytes < 0L) return "—"
            if (bytes < 1024L) return "$bytes B"
            val kb = bytes / 1024.0
            if (kb < 1024.0) return String.format("%.1f KB", kb)
            val mb = kb / 1024.0
            if (mb < 1024.0) return String.format("%.1f MB", mb)
            val gb = mb / 1024.0
            return String.format("%.2f GB", gb)
        }
    }
}
