package com.comii.app.data

import android.content.Context

/** Small persistent settings store (SharedPreferences keeps the app dependency free). */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("comii_settings", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString("server_url", "").orEmpty()
        set(value) = prefs.edit().putString("server_url", value.trim()).apply()

    var serverName: String
        get() = prefs.getString("server_name", "").orEmpty()
        set(value) = prefs.edit().putString("server_name", value).apply()

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

    fun lastReadAt(path: String): Long = prefs.getLong("read_at_$path", 0L)

    fun markRead(path: String) = prefs.edit().putLong("read_at_$path", System.currentTimeMillis()).apply()
}
