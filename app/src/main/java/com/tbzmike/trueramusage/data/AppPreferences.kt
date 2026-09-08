package com.tbzmike.trueramusage.data

import android.content.Context

enum class DisplayMode { SIMPLE, DETAILED }
enum class ThemeMode { SYSTEM, LIGHT, DARK }

class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("true_ram_usage_preferences", Context.MODE_PRIVATE)

    var displayMode: DisplayMode
        get() = runCatching {
            DisplayMode.valueOf(prefs.getString(KEY_DISPLAY_MODE, DisplayMode.SIMPLE.name) ?: DisplayMode.SIMPLE.name)
        }.getOrDefault(DisplayMode.SIMPLE)
        set(value) { prefs.edit().putString(KEY_DISPLAY_MODE, value.name).apply() }

    var themeMode: ThemeMode
        get() = runCatching {
            ThemeMode.valueOf(prefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name)
        }.getOrDefault(ThemeMode.SYSTEM)
        set(value) { prefs.edit().putString(KEY_THEME_MODE, value.name).apply() }

    var automaticMemoryRefreshEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTOMATIC_MEMORY_REFRESH, true)
        set(value) { prefs.edit().putBoolean(KEY_AUTOMATIC_MEMORY_REFRESH, value).apply() }

    var memoryRefreshIntervalMs: Long
        get() = normalizeRefreshInterval(prefs.getLong(KEY_MEMORY_REFRESH_INTERVAL_MS, DEFAULT_REFRESH_INTERVAL_MS))
        set(value) { prefs.edit().putLong(KEY_MEMORY_REFRESH_INTERVAL_MS, normalizeRefreshInterval(value)).apply() }

    companion object {
        val SUPPORTED_REFRESH_INTERVALS_MS = listOf(1_000L, 2_000L, 5_000L, 10_000L)
        const val DEFAULT_REFRESH_INTERVAL_MS = 2_000L

        private const val KEY_DISPLAY_MODE = "display_mode"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_AUTOMATIC_MEMORY_REFRESH = "automatic_memory_refresh"
        private const val KEY_MEMORY_REFRESH_INTERVAL_MS = "memory_refresh_interval_ms"

        fun normalizeRefreshInterval(value: Long): Long =
            SUPPORTED_REFRESH_INTERVALS_MS.minByOrNull { kotlin.math.abs(it - value) } ?: DEFAULT_REFRESH_INTERVAL_MS
    }
}
