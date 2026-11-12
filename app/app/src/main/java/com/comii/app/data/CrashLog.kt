package com.comii.app.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persists the last uncaught exception so the user can send it back when the
 * app closes unexpectedly. Writes to filesDir/last-crash.txt.
 */
object CrashLog {

    private const val FILE_NAME = "last-crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { write(appContext, thread.name, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun write(context: Context, threadName: String, throwable: Throwable) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val text = buildString {
            appendLine("time: $stamp")
            appendLine("thread: $threadName")
            appendLine("device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE}")
            appendLine()
            appendLine(throwable.stackTraceToString())
        }
        File(context.filesDir, FILE_NAME).writeText(text)
    }

    fun read(context: Context): String? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.isFile) return null
        return runCatching { file.readText() }.getOrNull()
    }

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }
}
