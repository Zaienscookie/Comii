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
        get() = prefs.getBoolean("landscape_rotate", true)
        set(value) = prefs.edit().putBoolean("landscape_rotate", value).apply()

    var showProgressBar: Boolean
        get() = prefs.getBoolean("show_progress", true)
        set(value) = prefs.edit().putBoolean("show_progress", value).apply()

    var gridColumns: Int
        get() = prefs.getInt("grid_columns", 2)
        set(value) = prefs.edit().putInt("grid_columns", value).apply()

    // Local mirror of reading positions, keyed by series path.
    fun localPage(path: String): Int = prefs.getInt("page_$path", 0)

    fun setLocalPage(path: String, page: Int) =
        prefs.edit().putInt("page_$path", page).apply()

    fun lastReadAt(path: String): Long = prefs.getLong("read_at_$path", 0L)

    fun markRead(path: String) = prefs.edit().putLong("read_at_$path", System.currentTimeMillis()).apply()
}
