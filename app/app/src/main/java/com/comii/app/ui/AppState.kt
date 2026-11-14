package com.comii.app.ui

import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.comii.app.data.ComiiRepository
import com.comii.app.data.GalleryItem
import kotlinx.serialization.encodeToString

/** App-wide UI state: theme choice, server address and transient toast text. */
class AppState(
    val repository: ComiiRepository,
    val ai: com.comii.app.data.ai.AiRepository,
) {

    var themeMode by mutableStateOf(repository.settings.themeMode)
        private set

    init {
        com.comii.app.data.AuthTokenProvider.token = repository.settings.authToken
        com.comii.app.data.AuthTokenProvider.accessToken = repository.settings.accessToken
    }

    var serverUrl by mutableStateOf(repository.settings.serverUrl)
        private set

    var serverName by mutableStateOf(repository.settings.serverName)
        private set

    var authUsername by mutableStateOf(repository.settings.authUsername)
        private set
    var authRole by mutableStateOf(repository.settings.authRole)
        private set
    var authGrants by mutableStateOf(
        runCatching {
            com.comii.app.data.ComiiJson.decodeFromString<List<com.comii.app.data.AuthGrant>>(repository.settings.authGrantsJson)
        }.getOrDefault(emptyList()),
    )
        private set

    var authDisabled by mutableStateOf(false)
        private set

    val isLoggedIn: Boolean get() = authDisabled || repository.settings.authToken.isNotBlank()

    /** 切换服务器时递增，让照片/相册/系列界面强制丢弃旧服务器的数据与图片地址。 */
    var serverGeneration by mutableIntStateOf(0)
        private set

    var toastMessage by mutableStateOf<String?>(null)
        private set

    /** 相册滚动位置：从阅读器返回时恢复到原来的位置。 */
    var galleryScrollIndex by mutableIntStateOf(0)
    var galleryScrollOffset by mutableIntStateOf(0)
    var galleryScrollRestored by mutableStateOf(false)

    /** 照片 Tab 的内存缓存：切回照片时直接显示，不重新转圈。 */
    var photosItems by mutableStateOf<List<GalleryItem>>(emptyList())
    var photosTotal by mutableIntStateOf(0)
    var photosLoaded by mutableStateOf(false)
    var photosScrollIndex by mutableIntStateOf(0)
    var photosScrollOffset by mutableIntStateOf(0)

    val hasServer: Boolean get() = serverUrl.isNotBlank()
    /** 剪切板：存相对路径，文件夹或单个文件都可以。 */
    var clipboardPaths by mutableStateOf<List<String>>(emptyList())
        private set
    var clipboardLabel by mutableStateOf("")
        private set

    fun setClipboard(paths: List<String>, label: String = "") {
        clipboardPaths = paths
        clipboardLabel = label
    }

    fun clearClipboard() {
        clipboardPaths = emptyList()
        clipboardLabel = ""
    }


    fun setTheme(mode: String) {
        themeMode = mode
        repository.settings.themeMode = mode
    }

    suspend fun checkServerAuth(): Boolean {
        if (serverUrl.isBlank()) return false
        val status = runCatching { repository.authStatus(serverUrl) }.getOrNull()
            ?: return false
        authDisabled = !status.auth_enabled
        if (authDisabled) return true
        if (repository.settings.authToken.isBlank()) return false
        return runCatching {
            val me = repository.authMe()
            authUsername = me.user.username
            authRole = me.user.role
            authGrants = me.grants
            true
        }.getOrElse {
            repository.settings.authToken = ""
            com.comii.app.data.AuthTokenProvider.token = ""
            false
        }
    }

    suspend fun loginWithPassword(url: String, username: String, password: String): Unit {
        authDisabled = false
        repository.settings.serverUrl = url.trim()
        serverUrl = url.trim()
        val response = repository.authLogin(url, username, password)
        authUsername = response.user.username
        authRole = response.user.role
        authGrants = response.grants
        serverGeneration++
        photosLoaded = false
        photosItems = emptyList()
    }

    suspend fun loginWithToken(url: String, token: String): Unit {
        authDisabled = false
        repository.settings.serverUrl = url.trim()
        serverUrl = url.trim()
        com.comii.app.data.AuthTokenProvider.token = token.trim()
        val me = repository.authMe()
        repository.settings.authToken = token.trim()
        repository.settings.authUsername = me.user.username
        repository.settings.authRole = me.user.role
        repository.settings.authGrantsJson = com.comii.app.data.ComiiJson.encodeToString(me.grants)
        authUsername = me.user.username
        authRole = me.user.role
        authGrants = me.grants
        serverGeneration++
        photosLoaded = false
        photosItems = emptyList()
    }

    suspend fun logout() {
        repository.authLogout()
        authUsername = ""
        authRole = ""
        authGrants = emptyList()
        photosItems = emptyList()
        photosLoaded = false
    }

    fun can(action: String, path: String): Boolean {
        return when (authRole) {
            "owner", "admin" -> true
            "viewer" -> action == "view"
            "custom" -> {
                val normalized = path.trim('/')
                var best: com.comii.app.data.AuthGrant? = null
                var bestLen = -1
                authGrants.forEach { grant ->
                    val prefix = grant.path_prefix.trim('/')
                    val matches = prefix.isEmpty() || normalized == prefix || normalized.startsWith("$prefix/")
                    if (matches && prefix.length >= bestLen) {
                        best = grant
                        bestLen = prefix.length
                    }
                }
                when (action) {
                    "view" -> best?.view == true
                    "download" -> best?.download == true
                    "upload" -> best?.upload == true
                    "create" -> best?.create == true
                    "delete" -> best?.delete == true
                    "move" -> best?.move == true
                    "manage" -> best?.manage == true
                    else -> false
                }
            }
            else -> false
        }
    }

    fun setServer(url: String, name: String = "") {
        repository.settings.serverUrl = url
        repository.settings.serverName = name
        serverUrl = url
        serverName = name
        serverGeneration++
        photosItems = emptyList()
        photosTotal = 0
        photosLoaded = false
        photosScrollIndex = 0
        photosScrollOffset = 0
    }

    fun showToast(message: String) {
        toastMessage = message
    }

    fun consumeToast() {
        toastMessage = null
    }
}

/** 从「全部」打开阅读器时，用内存传递全局索引，避免导航参数被忽略。 */
object ReaderLaunch {
    private var path: String = ""
    private var index: Int = -1
    private var all: Boolean = false
    private var name: String = ""
    private var valid: Boolean = false

    fun prepare(path: String, index: Int, all: Boolean, name: String) {
        this.path = path
        this.index = index
        this.all = all
        this.name = name
        this.valid = true
    }

    data class Info(val index: Int, val all: Boolean, val name: String)

    fun consume(path: String): Info? {
        if (!valid || this.path != path) return null
        valid = false
        return Info(index, all, name)
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



