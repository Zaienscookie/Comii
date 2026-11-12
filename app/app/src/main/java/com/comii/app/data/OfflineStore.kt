package com.comii.app.data

import android.content.Context
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
data class OfflineSeries(
    val path: String = "",
    val name: String = "",
    val kind: String = NodeKind.MANGA,
    val images: List<String> = emptyList(),
    val videos: List<String> = emptyList(),
    val coverName: String? = null,
    val downloadedAt: Long = 0L,
    val totalBytes: Long = 0L,
)

/** Local copies of series so comics and videos stay readable without a server. */
class OfflineStore(private val context: Context) {

    private val root = File(context.filesDir, "offline")

    private fun key(path: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(path.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(24)
    }

    fun directory(path: String): File = File(root, key(path))

    fun imagesDir(path: String): File = File(directory(path), "images")

    fun videosDir(path: String): File = File(directory(path), "videos")

    fun imageFile(path: String, name: String): File = File(imagesDir(path), name)

    fun videoFile(path: String, name: String): File = File(videosDir(path), name)

    fun all(): List<OfflineSeries> {
        val dirs = root.listFiles() ?: return emptyList()
        return dirs.mapNotNull { dir ->
            val meta = File(dir, "meta.json")
            if (!meta.isFile) return@mapNotNull null
            runCatching { ComiiJson.decodeFromString<OfflineSeries>(meta.readText()) }.getOrNull()
        }.sortedByDescending { it.downloadedAt }
    }

    fun find(path: String): OfflineSeries? = all().firstOrNull { it.path == path }

    fun coverFile(series: OfflineSeries): File? {
        val name = series.coverName ?: series.images.firstOrNull() ?: return null
        return imageFile(series.path, name).takeIf { it.isFile }
    }

    fun save(series: OfflineSeries) {
        val dir = directory(series.path)
        dir.mkdirs()
        File(dir, "meta.json").writeText(ComiiJson.encodeToString(series))
    }

    fun delete(path: String) {
        directory(path).deleteRecursively()
    }

    fun totalBytes(): Long = all().sumOf { it.totalBytes }
}
