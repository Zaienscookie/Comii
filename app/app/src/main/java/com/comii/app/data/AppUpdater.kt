package com.comii.app.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.comii.app.BuildConfig
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
data class AppUpdateInfo(
    val available: Boolean = false,
    val versionCode: Int = 0,
    val versionName: String = "",
    val notes: String = "",
    val size: Long = 0,
    val sha256: String = "",
    val url: String = "",
)

/**
 * 应用内更新：向服务器查询版本、下载 APK、拉起系统安装器。
 * 服务器没有更新（或版本不高于当前）时返回 null，界面不显示任何东西。
 */
object AppUpdater {

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    val currentVersionCode: Int get() = BuildConfig.VERSION_CODE
    val currentVersionName: String get() = BuildConfig.VERSION_NAME

    suspend fun check(serverUrl: String): AppUpdateInfo? = withContext(Dispatchers.IO) {
        val base = serverUrl.trim().trimEnd('/')
        if (base.isEmpty()) return@withContext null
        val request = Request.Builder()
            .url("$base/api/v1/app/update")
            .header("User-Agent", "Comii/${BuildConfig.VERSION_NAME}")
            .get()
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val text = response.body?.string().orEmpty()
                val info = ComiiJson.decodeFromString<AppUpdateInfo>(text)
                if (!info.available || info.versionCode <= currentVersionCode) null else info
            }
        }.getOrNull()
    }

    suspend fun download(
        context: Context,
        serverUrl: String,
        info: AppUpdateInfo,
        onProgress: (Float) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val base = serverUrl.trim().trimEnd('/')
        val url = if (info.url.startsWith("http")) info.url else base + info.url
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(directory, "comii-${info.versionName}.apk")
        val temporary = File(directory, "comii-${info.versionName}.apk.part")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Comii/${BuildConfig.VERSION_NAME}")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("下载失败（HTTP ${response.code}）")
            }
            val body = response.body ?: throw IllegalStateException("下载内容为空")
            val total = if (body.contentLength() > 0) body.contentLength() else info.size
            temporary.outputStream().use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (total > 0) onProgress((written.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        if (target.exists()) target.delete()
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
        onProgress(1f)
        target
    }

    fun install(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }
}
