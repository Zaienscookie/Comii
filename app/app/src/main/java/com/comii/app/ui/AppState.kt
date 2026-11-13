package com.comii.app.ui

import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.comii.app.data.ComiiRepository

/** App-wide UI state: theme choice, server address and transient toast text. */
class AppState(
    val repository: ComiiRepository,
    val ai: com.comii.app.data.ai.AiRepository,
) {

    var themeMode by mutableStateOf(repository.settings.themeMode)
        private set

    var serverUrl by mutableStateOf(repository.settings.serverUrl)
        private set

    var serverName by mutableStateOf(repository.settings.serverName)
        private set

    var toastMessage by mutableStateOf<String?>(null)
        private set

    /** 相册滚动位置：从阅读器返回时恢复到原来的位置。 */
    var galleryScrollIndex by mutableIntStateOf(0)
    var galleryScrollOffset by mutableIntStateOf(0)
    var galleryScrollRestored by mutableStateOf(false)

    val hasServer: Boolean get() = serverUrl.isNotBlank()

    fun setTheme(mode: String) {
        themeMode = mode
        repository.settings.themeMode = mode
    }

    fun setServer(url: String, name: String = "") {
        repository.settings.serverUrl = url
        repository.settings.serverName = name
        serverUrl = url
        serverName = name
    }

    fun showToast(message: String) {
        toastMessage = message
    }

    fun consumeToast() {
        toastMessage = null
    }
}

/** Navigation arguments are base64url encoded so paths with slashes stay safe. */
object Routes {
    private const val FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING

    fun encode(value: String): String = Base64.encodeToString(value.toByteArray(Charsets.UTF_8), FLAGS)

    fun decode(value: String): String {
        if (value.isEmpty()) return ""
        return runCatching { String(Base64.decode(value, FLAGS), Charsets.UTF_8) }.getOrDefault("")
    }

    fun reader(path: String, page: Int = -1, all: Boolean = false, name: String = ""): String {
        val query = buildList {
            if (page >= 0) add("page=$page")
            if (all) add("all=1")
            if (name.isNotEmpty()) add("name=${encode(name)}")
        }.joinToString("&")
        val base = "reader/${encode(path)}"
        return if (query.isEmpty()) base else "$base?$query"
    }
    fun series(path: String) = "series/${encode(path)}"
    fun player(path: String, name: String) = "player/${encode(path)}/${encode(name)}"
    fun upload(folder: String) = "upload/${encode(folder)}"
}
