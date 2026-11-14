package com.comii.app.data

import android.content.Context
import android.os.Build
import com.comii.app.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全量运行日志（只保留本次启动）。
 * 启动时清空 logs/comii-session.log，之后所有事件追加写入，避免内存占用。
 */
object AppLog {
    private var sessionFile: File? = null
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())

    fun install(context: Context) {
        val directory = File(context.filesDir, "logs").apply { mkdirs() }
        val file = File(directory, "comii-session.log")
        file.writeText("")
        sessionFile = file
        add(
            "app",
            "start ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) / " +
                "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
        )
    }

    @Synchronized
    fun add(tag: String, message: String) {
        val line = "${stamp.format(Date())} [$tag] $message"
        runCatching {
            sessionFile?.appendText(line + "\n")
        }
    }

    fun snapshot(): String = sessionFile?.takeIf { it.isFile }?.readText().orEmpty()

    fun export(context: Context, serverUrl: String, settings: SettingsStore): File {
        val directory = File(context.cacheDir, "share").apply { mkdirs() }
        val target = File(directory, "comii-log.txt")
        val body = buildString {
            appendLine("Comii diagnostic log")
            appendLine("time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
            appendLine("app: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("server: $serverUrl")
            appendLine("imageQuality: ${settings.imageQuality}, prefetch: ${settings.prefetchPages}, readerMode: ${settings.readerMode}")
            appendLine("lastViewed: ${settings.lastViewedPath} / ${settings.lastViewedName}")
            appendLine("localAllPage: ${settings.localPage("__comii_all__")}")
            appendLine("--- session ---")
            appendLine(snapshot())
            val crash = CrashLog.read(context)
            if (!crash.isNullOrBlank()) {
                appendLine("--- last crash ---")
                appendLine(crash)
            }
        }
        target.writeText(body)
        return target
    }
}
