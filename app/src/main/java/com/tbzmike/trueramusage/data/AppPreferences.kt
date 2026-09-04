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

    companion object {
        private const val KEY_DISPLAY_MODE = "display_mode"
        private const val KEY_THEME_MODE = "theme_mode"
    }
}
