package com.comii.app.data

import android.content.Context

/** Small persistent settings store (SharedPreferences keeps the app dependency free). */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("comii_settings", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString("server_url", "").orEmpty()
        set(value) = prefs.edit().putString("server_url", value.trim()).apply()

    var accessToken: String
        get() = prefs.getString("access_token", "").orEmpty()
        set(value) = prefs.edit().putString("access_token", value.trim()).apply()

    var authToken: String
        get() = prefs.getString("auth_token", "").orEmpty()
        set(value) = prefs.edit().putString("auth_token", value).apply()

    var authUsername: String
        get() = prefs.getString("auth_username", "").orEmpty()
        set(value) = prefs.edit().putString("auth_username", value).apply()

    var authRole: String
        get() = prefs.getString("auth_role", "").orEmpty()
        set(value) = prefs.edit().putString("auth_role", value).apply()

    var authGrantsJson: String
        get() = prefs.getString("auth_grants", "[]").orEmpty()
        set(value) = prefs.edit().putString("auth_grants", value).apply()

    /** 最近搜索，用换行分隔，最多 10 条 */
    var searchHistory: String
        get() = prefs.getString("search_history", "").orEmpty()
        set(value) = prefs.edit().putString("search_history", value).apply()

    var serverName: String
        get() = prefs.getString("server_name", "").orEmpty()
        set(value) = prefs.edit().putString("server_name", value).apply()

    var photosGallery: Boolean
        get() = prefs.getBoolean("photos_gallery", false)
        set(value) = prefs.edit().putBoolean("photos_gallery", value).apply()

    var videoPositions: String
        get() = prefs.getString("video_positions", "").orEmpty()
        set(value) = prefs.edit().putString("video_positions", value).apply()

    fun videoPosition(path: String, name: String): Long {
        val key = "$path|$name"
        return videoPositions.lineSequence()
            .mapNotNull { line ->
                val parts = line.split("|")
                if (parts.size >= 3 && parts[0] == path && parts[1] == name) parts[2].toLongOrNull() else null
            }
            .lastOrNull() ?: 0L
    }

    fun saveVideoPosition(path: String, name: String, positionMs: Long) {
        val key = "$path|$name"
        val kept = videoPositions.lineSequence()
            .filter { it.isNotBlank() }
            .filterNot { it.startsWith("$key|") }
            .toList()
            .takeLast(300)
        videoPositions = (kept + "$key|$positionMs").joinToString("\n")
    }

    var highlightsRaw: String
        get() = prefs.getString("video_highlights", "").orEmpty()
        set(value) = prefs.edit().putString("video_highlights", value).apply()

    fun highlights(path: String, name: String): List<Pair<Long, String>> =
        highlightsRaw.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split("|")
                if (parts.size >= 4 && parts[0] == path && parts[1] == name) {
                    parts[2].toLongOrNull()?.let { it to parts[3] }
                } else null
            }
            .sortedBy { it.first }
            .toList()

    fun addHighlight(path: String, name: String, timeMs: Long, label: String = "") {
        val kept = highlightsRaw.lineSequence()
            .filter { it.isNotBlank() }
            .filterNot { it.startsWith("$path|$name|") && kotlin.math.abs((it.split("|").getOrNull(2)?.toLongOrNull() ?: -99999) - timeMs) < 1500 }
            .toList()
        highlightsRaw = (kept + "$path|$name|$timeMs|${label.replace("|", " ")}").joinToString("\n")
    }

    fun removeHighlight(path: String, name: String, timeMs: Long) {
        highlightsRaw = highlightsRaw.lineSequence()
            .filter { it.isNotBlank() }
            .filterNot { it.startsWith("$path|$name|$timeMs|") }
            .joinToString("\n")
    }

    var themeMode: String
        get() = prefs.getString("theme", "auto").orEmpty()
        set(value) = prefs.edit().putString("theme", value).apply()

    var readerMode: String
        get() = prefs.getString("reader_mode", "vertical").orEmpty()
        set(value) = prefs.edit().putString("reader_mode", value).apply()

    var tapToTurn: Boolean
        get() = prefs.getBoolean("tap_turn", true)
        set(value) = prefs.edit().putBoolean("tap_turn", value).apply()

    var landscapeRotate: Boolean
        get() = prefs.getBoolean("landscape_rotate", false)
        set(value) = prefs.edit().putBoolean("landscape_rotate", value).apply()

    /** 阅读/视频跟随手机方向自动旋转。 */
    var autoRotate: Boolean
        get() = prefs.getBoolean("auto_rotate", false)
        set(value) = prefs.edit().putBoolean("auto_rotate", value).apply()

    var showProgressBar: Boolean
        get() = prefs.getBoolean("show_progress", true)
        set(value) = prefs.edit().putBoolean("show_progress", value).apply()

    var gridColumns: Int
        get() = prefs.getInt("grid_columns", 2)
        set(value) = prefs.edit().putInt("grid_columns", value).apply()

    /**
     * 阅读图片清晰度，服务端按宽度缩放后返回：
     * original（原图）/ high（1440）/ standard（1080）/ smooth（720）/ low（480）。
     */
    var imageQuality: String
        get() = prefs.getString("image_quality", "standard").orEmpty()
        set(value) = prefs.edit().putString("image_quality", value).apply()

    /** 阅读器向后预加载的页数（0 = 关闭）。 */
    var prefetchPages: Int
        get() = prefs.getInt("prefetch_pages", 5)
        set(value) = prefs.edit().putInt("prefetch_pages", value).apply()

    /** Library layout inside a folder: "folders" = one card per series, "flat" = every page in order. */
    var libraryLayout: String
        get() = prefs.getString("library_layout", "folders").orEmpty()
        set(value) = prefs.edit().putString("library_layout", value).apply()

    /** Downloads layout: "group" = grouped by category, "flat" = every page in order. */
    var downloadsLayout: String
        get() = prefs.getString("downloads_layout", "group").orEmpty()
        set(value) = prefs.edit().putString("downloads_layout", value).apply()

    // ---------------------------------------------------------- server history
    fun serverHistory(): List<String> =
        prefs.getString("server_history", "").orEmpty()
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    fun addServerHistory(url: String) {
        val clean = url.trim()
        if (clean.isEmpty()) return
        val list = (listOf(clean) + serverHistory()).distinct().take(8)
        prefs.edit().putString("server_history", list.joinToString("\n")).apply()
    }

    fun removeServerHistory(url: String) {
        val list = serverHistory().filter { it != url }
        prefs.edit().putString("server_history", list.joinToString("\n")).apply()
    }

    // ---------------------------------------------------------------- video
    var videoMuted: Boolean
        get() = prefs.getBoolean("video_muted", true)
        set(value) = prefs.edit().putBoolean("video_muted", value).apply()

    var videoPitchFollowsSpeed: Boolean
        get() = prefs.getBoolean("video_pitch_follow", false)
        set(value) = prefs.edit().putBoolean("video_pitch_follow", value).apply()

    var videoBassPreset: String
        get() = prefs.getString("video_bass_preset", "原声") ?: "原声"
        set(value) = prefs.edit().putString("video_bass_preset", value).apply()

    var videoBassUltra: Float
        get() = prefs.getFloat("video_bass_ultra", 0f)
        set(value) = prefs.edit().putFloat("video_bass_ultra", value).apply()

    var videoBassLow: Float
        get() = prefs.getFloat("video_bass_low", 0f)
        set(value) = prefs.edit().putFloat("video_bass_low", value).apply()

    var videoBassMid: Float
        get() = prefs.getFloat("video_bass_mid", 0f)
        set(value) = prefs.edit().putFloat("video_bass_mid", value).apply()

    // ------------------------------------------------------- entry / privacy
    var entryPrompt: Boolean
        get() = prefs.getBoolean("entry_prompt", true)
        set(value) = prefs.edit().putBoolean("entry_prompt", value).apply()

    var privacyBlur: Boolean
        get() = prefs.getBoolean("privacy_blur", true)
        set(value) = prefs.edit().putBoolean("privacy_blur", value).apply()

    var lastUpdateCheck: Long
        get() = prefs.getLong("last_update_check", 0L)
        set(value) = prefs.edit().putLong("last_update_check", value).apply()

    // Local mirror of reading positions, keyed by series path.
    fun localPage(path: String): Int = prefs.getInt("page_$path", 0)

    fun setLocalPage(path: String, page: Int) =
        prefs.edit().putInt("page_$path", page).apply()

    var lastViewedPath: String
        get() = prefs.getString("last_viewed_path", "").orEmpty()
        set(value) = prefs.edit().putString("last_viewed_path", value).apply()

    var lastViewedName: String
        get() = prefs.getString("last_viewed_name", "").orEmpty()
        set(value) = prefs.edit().putString("last_viewed_name", value).apply()

    fun lastReadAt(path: String): Long = prefs.getLong("read_at_$path", 0L)

    fun markRead(path: String) = prefs.edit().putLong("read_at_$path", System.currentTimeMillis()).apply()
}



