package com.comii.app.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source

/** Single entry point used by the UI; owns the API client and local caches. */
class ComiiRepository(private val context: Context) {

    val settings = SettingsStore(context)
    val offline = OfflineStore(context)
    /** 下载不跟页面生命周期绑定；切 Tab/退出页面也能继续。 */
    private val downloadScope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class FolderEntry(val path: String, val name: String, val depth: Int)

    fun hasServer(): Boolean = settings.serverUrl.isNotBlank()

    fun apiOrNull(): ComiiApi? =
        settings.serverUrl.trim().takeIf { it.isNotBlank() }?.let { ComiiApi(it) }

    /** Width the reader should request from the server (0 = original file). */
    fun imageWidth(): Int = when (settings.imageQuality) {
        "original" -> 0
        "high" -> 1440
        "smooth" -> 720
        "low" -> 480
        else -> 1080
    }

    /** How many pages ahead the reader pre-downloads into the Coil cache. */
    fun prefetchPages(): Int = settings.prefetchPages.coerceIn(0, 12)

    suspend fun tags(): TagsResponse = requireApi().tags()

    suspend fun seriesTags(path: String): List<String> = requireApi().seriesTags(path).tags

    suspend fun saveTags(paths: List<String>, tags: List<String>, mode: String = "add"): List<String> =
        requireApi().saveTags(paths, tags, mode).tags

    suspend fun createTag(name: String, group: String): TagCount = requireApi().createTag(name, group)

    suspend fun tagged(tag: String): List<LibraryNode> = requireApi().tagged(tag).items

    fun mediaUrl(relative: String?): String? {
        if (relative.isNullOrBlank()) return null
        if (relative.startsWith("http")) return relative
        val base = settings.serverUrl.trim().trimEnd('/')
        if (base.isBlank()) return null
        return base + if (relative.startsWith("/")) relative else "/$relative"
    }

    /** Breadth-first folder listing used by the move / upload target pickers. */
    suspend fun folderTree(includeRoot: Boolean = true): List<FolderEntry> = withContext(Dispatchers.IO) {
        val api = requireApi()
        val result = mutableListOf<FolderEntry>()
        if (includeRoot) result.add(FolderEntry("", "媒体库根目录", 0))
        val queue = ArrayDeque<Pair<String, Int>>()
        queue.add("" to 0)
        var guard = 0
        while (queue.isNotEmpty() && guard < 2000) {
            val (path, depth) = queue.removeFirst()
            guard++
            val response = runCatching { api.browse(path) }.getOrNull() ?: continue
            // browse 返回的目录可能是 folder / album / manga / video / mixed；
            // 移动目标需要展示所有真实目录，不能只看 kind == folder。
            response.items
                .filterNot { it.name == "#recycle" || it.name == "@eaDir" || it.name == "#snapshot" }
                .forEach { node ->
                    result.add(FolderEntry(node.path, node.name, depth + 1))
                    queue.add(node.path to (depth + 1))
                }
        }
        result
    }

    fun requireApi(): ComiiApi {
        val base = settings.serverUrl.trim()
        if (base.isBlank()) throw ApiException(0, "请先配置服务器地址")
        return ComiiApi(base)
    }

    suspend fun testPing(url: String): PingResponse = withContext(Dispatchers.IO) {
        ComiiApi(url).ping()
    }

    suspend fun browse(path: String): BrowseResponse = requireApi().browse(path)

    suspend fun series(path: String): SeriesDetail = requireApi().series(path)

    suspend fun search(query: String): List<LibraryNode> = requireApi().search(query).items

    suspend fun collectSeries(path: String): List<LibraryNode> = requireApi().collection(path).items

    suspend fun gallery(
        limit: Int = 300,
        offset: Int = 0,
        path: String = "",
        sort: String = "recent",
    ): GalleryResponse = requireApi().gallery(limit = limit, offset = offset, path = path, sort = sort)

    suspend fun setCover(path: String, name: String): ActionResult =
        requireApi().setCover(path, name)

    suspend fun setAlbumCover(path: String, series: String): ActionResult =
        requireApi().setAlbumCover(path, series)

    suspend fun addFavorite(
        path: String,
        name: String = "",
        collection: String = "默认",
    ): ActionResult = requireApi().addFavorite(path, name, collection)

    suspend fun favoriteStatus(path: String, name: String): Pair<Boolean, List<String>> =
        requireApi().favoriteStatus(path, name)

    suspend fun deleteMedia(path: String, names: List<String>): ActionResult =
        requireApi().deleteMedia(path, names)

    /**
     * Downloads every series below [path] and groups them under a local category
     * named after the folder, so a whole author / category is available offline.
     */
    suspend fun downloadFolder(path: String, onProgress: (Int, Int) -> Unit = { _, _ -> }): Int =
        withContext(Dispatchers.IO) {            val series = collectSeries(path)
            if (series.isEmpty()) throw ApiException(0, "这个文件夹里没有可下载的系列")
            val folderName = path.substringAfterLast('/').ifBlank { "未分类" }
            runCatching { offline.createCategory(folderName) }
            val total = series.size
            onProgress(0, total)
            var done = 0
            for (node in series) {
                downloadSeries(node.path, includeVideos = true)
                offline.setCategory(node.path, folderName)
                done++
                onProgress(done, total)
            }
            done
        }

    suspend fun trash(): TrashListResponse = requireApi().trash()

    suspend fun createFolder(parent: String, name: String, kind: String): ActionResult =
        requireApi().createFolder(parent, name, kind)

    suspend fun setFolderKind(path: String, kind: String): ActionResult =
        requireApi().setFolderKind(path, kind)

    suspend fun rename(path: String, newName: String): ActionResult =
        requireApi().rename(path, newName)

    suspend fun move(paths: List<String>, to: String): BatchResult = requireApi().move(paths, to)

    suspend fun delete(paths: List<String>): BatchResult = requireApi().deleteItems(paths)

    suspend fun restore(entryId: String): ActionResult = requireApi().restore(entryId)

    suspend fun purge(entryId: String): ActionResult = requireApi().purge(entryId)

    suspend fun saveProgress(path: String, page: Int, total: Int) {
        settings.setLocalPage(path, page)
        settings.markRead(path)
        runCatching { requireApi().saveProgress(path, page, total) }
    }

    suspend fun serverProgress(path: String): ReadingProgress? = runCatching {
        requireApi().series(path).progress
    }.getOrNull()

    /** Scans the local /24 for hosts answering the Comii ping. */
    suspend fun scanLan(port: Int = 8000, onFound: (String) -> Unit = {}): List<String> =
        withContext(Dispatchers.IO) {
            val local = localIpv4() ?: return@withContext emptyList()
            val prefix = local.substringBeforeLast('.', "")
            if (prefix.isEmpty()) return@withContext emptyList()
            val found = Collections.synchronizedList(mutableListOf<String>())
            val gate = Semaphore(48)
            coroutineScope {
                (1..254).map { index ->
                    async(Dispatchers.IO) {
                        gate.withPermit {
                            val host = "$prefix.$index"
                            if (portOpen(host, port)) {
                                val url = "http://$host:$port"
                                val api = ComiiApi(url)
                                if (api.isComiiServer()) {
                                    found.add(url)
                                    onFound(url)
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
            found.sorted()
        }

    private fun localIpv4(): String? = runCatching {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .flatMap { Collections.list(it.inetAddresses) }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    private fun portOpen(host: String, port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 350)
            true
        }
    }.getOrDefault(false)

    /**
     * Downloads every image (and optionally video) of a series into app storage.
     * Returns the stored metadata; progress is reported as (done, total).
     */
    suspend fun downloadSeries(
        path: String,
        includeVideos: Boolean,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): OfflineSeries = withContext(Dispatchers.IO) {
        val api = requireApi()
        val detail = api.series(path)
        if (detail.images.isEmpty() && detail.videos.isEmpty()) {
            throw ApiException(0, "这个系列没有可下载的内容")
        }
        offline.imagesDir(path).mkdirs()
        offline.videosDir(path).mkdirs()

        val pendingImages = detail.images.filter { name ->
            val file = offline.imageFile(path, name)
            !file.isFile || file.length() == 0L
        }
        val pendingVideos = if (includeVideos) {
            detail.videos.filter { name ->
                val file = offline.videoFile(path, name)
                !file.isFile || file.length() == 0L
            }
        } else {
            emptyList()
        }

        val total = pendingImages.size + pendingVideos.size
        val counter = AtomicInteger(0)
        val gate = Semaphore(3)
        onProgress(0, total)

        coroutineScope {
            val jobs = buildList {
                pendingImages.forEach { name ->
                    add(async(Dispatchers.IO) {
                        gate.withPermit {
                            download(api.imageUrl(path, name), offline.imageFile(path, name))
                        }
                        onProgress(counter.incrementAndGet(), total)
                    })
                }
                pendingVideos.forEach { name ->
                    add(async(Dispatchers.IO) {
                        gate.withPermit {
                            download(api.videoUrl(path, name), offline.videoFile(path, name))
                        }
                        onProgress(counter.incrementAndGet(), total)
                    })
                }
            }
            jobs.awaitAll()
        }

        val videos = detail.videos.filter { offline.videoFile(path, it).isFile }
        val bytes = offline.imagesDir(path).listFiles()?.sumOf { it.length() } ?: 0L
        val videoBytes = offline.videosDir(path).listFiles()?.sumOf { it.length() } ?: 0L
        val meta = OfflineSeries(
            path = detail.path,
            name = detail.name,
            kind = detail.kind,
            images = detail.images,
            videos = videos,
            coverName = detail.images.firstOrNull(),
            downloadedAt = System.currentTimeMillis(),
            totalBytes = bytes + videoBytes,
        )
        offline.save(meta)
        meta
    }

    private fun download(url: String, target: File) {
        val request = Request.Builder().url(url).get().build()
        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ApiException(response.code, "下载失败 (${response.code})")
            val body = response.body ?: throw ApiException(response.code, "下载内容为空")
            target.parentFile?.mkdirs()
            val temporary = File(target.parentFile, target.name + ".part")
            temporary.outputStream().use { output -> body.byteStream().use { it.copyTo(output) } }
            if (temporary.length() == 0L) {
                temporary.delete()
                throw ApiException(0, "下载内容为空")
            }
            if (target.exists()) target.delete()
            if (!temporary.renameTo(target)) throw ApiException(0, "无法保存文件")
        }
    }

    suspend fun upload(
        uris: List<Uri>,
        folder: String,
        name: String,
        direct: Boolean = false,
        onBytes: (Long) -> Unit = {},
    ): ImportResult = withContext(Dispatchers.IO) {
        val api = requireApi()
        val resolver = context.contentResolver
        val parts = uris.mapNotNull { uri ->
            val fileName = displayName(resolver, uri) ?: "upload_${System.currentTimeMillis()}"
            val mime = resolver.getType(uri) ?: guessMime(fileName)
            ComiiApi.UploadPart(fileName, UploadRequestBody(resolver, uri, mime, onBytes))
        }
        if (parts.isEmpty()) throw ApiException(0, "没有可上传的文件")
        api.upload(api.buildUpload(parts, folder, name, direct))
    }

    private fun guessMime(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        "cbz" -> "application/vnd.comicbook+zip"
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        else -> "application/octet-stream"
    }

    private fun displayName(resolver: ContentResolver, uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun sizeOf(resolver: ContentResolver, uri: Uri): Long = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
        } ?: -1L
    }.getOrDefault(-1L)

    private inner class UploadRequestBody(
        private val resolver: ContentResolver,
        private val uri: Uri,
        private val mime: String,
        private val onBytes: (Long) -> Unit,
    ) : RequestBody() {
        override fun contentType() = mime.toMediaType()
        override fun contentLength(): Long = sizeOf(resolver, uri).takeIf { it > 0 } ?: -1L
        override fun writeTo(sink: BufferedSink) {
            val input = resolver.openInputStream(uri) ?: throw IOException("无法读取所选文件")
            input.source().use { source ->
                val buffer = okio.Buffer()
                while (true) {
                    val read = source.read(buffer, 64 * 1024)
                    if (read <= 0L) break
                    sink.write(buffer, read)
                    onBytes(read)
                }
            }
        }
    }

    fun startDownloadFolder(
        path: String,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        onError: (Throwable) -> Unit = {},
    ): Job = downloadScope.launch {
        try {
            downloadFolder(path, onProgress)
        } catch (error: Throwable) {
            onError(error)
        }
    }

    fun startDownloadSeries(
        path: String,
        includeVideos: Boolean,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        onError: (Throwable) -> Unit = {},
    ): Job = downloadScope.launch {
        try {
            downloadSeries(path, includeVideos, onProgress)
        } catch (error: Throwable) {
            onError(error)
        }
    }
}
