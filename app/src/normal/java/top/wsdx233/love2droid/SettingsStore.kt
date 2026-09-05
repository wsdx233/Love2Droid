package top.wsdx233.love2droid

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

internal enum class ThemeMode(val storedValue: String) {
    LIGHT("light"),
    DARK("dark"),
    SYSTEM("system"),
    ;

    companion object {
        fun fromStored(value: String?): ThemeMode = entries.firstOrNull { it.storedValue == value } ?: SYSTEM
    }
}

internal fun ThemeMode.toAppCompatNightMode(): Int = when (this) {
    ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
    ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
    ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
}

internal fun ThemeMode.resolveEditorThemeId(systemIsDark: Boolean): String = when (this) {
    ThemeMode.LIGHT -> "quietlight"
    ThemeMode.DARK -> "darcula"
    ThemeMode.SYSTEM -> if (systemIsDark) "darcula" else "quietlight"
}

/** Small, process-safe store for preferences that affect the editor workspace. */
internal class SettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var appThemeMode: ThemeMode
        get() = ThemeMode.fromStored(preferences.getString(KEY_APP_THEME_MODE, ThemeMode.SYSTEM.storedValue))
        set(value) = preferences.edit().putString(KEY_APP_THEME_MODE, value.storedValue).apply()

    var editorThemeMode: ThemeMode
        get() = ThemeMode.fromStored(preferences.getString(KEY_EDITOR_THEME_MODE, ThemeMode.DARK.storedValue))
        set(value) = preferences.edit().putString(KEY_EDITOR_THEME_MODE, value.storedValue).apply()

    var editorFontSize: Float
        get() = preferences.getFloat(KEY_EDITOR_FONT_SIZE, DEFAULT_EDITOR_FONT_SIZE)
        set(value) = preferences.edit().putFloat(KEY_EDITOR_FONT_SIZE, value.coerceIn(8f, 24f)).apply()

    var editorWordWrap: Boolean
        get() = preferences.getBoolean(KEY_EDITOR_WORD_WRAP, false)
        set(value) = preferences.edit().putBoolean(KEY_EDITOR_WORD_WRAP, value).apply()

    var editorLineNumbers: Boolean
        get() = preferences.getBoolean(KEY_EDITOR_LINE_NUMBERS, true)
        set(value) = preferences.edit().putBoolean(KEY_EDITOR_LINE_NUMBERS, value).apply()
    var editorSymbolBar: Boolean
        get() = preferences.getBoolean(KEY_EDITOR_SYMBOL_BAR, true)
        set(value) = preferences.edit().putBoolean(KEY_EDITOR_SYMBOL_BAR, value).apply()

    var editorHoverInfo: Boolean
        get() = preferences.getBoolean(KEY_EDITOR_HOVER_INFO, true)
        set(value) = preferences.edit().putBoolean(KEY_EDITOR_HOVER_INFO, value).apply()


    var terminalFontSize: Float
        get() = preferences.getFloat(KEY_TERMINAL_FONT_SIZE, DEFAULT_TERMINAL_FONT_SIZE)
        set(value) = preferences.edit().putFloat(KEY_TERMINAL_FONT_SIZE, value.coerceIn(8f, 24f)).apply()

    var terminalKeepScreenOn: Boolean
        get() = preferences.getBoolean(KEY_TERMINAL_KEEP_SCREEN_ON, true)
        set(value) = preferences.edit().putBoolean(KEY_TERMINAL_KEEP_SCREEN_ON, value).apply()

    var terminalTranscriptRows: Int
        get() = preferences.getInt(KEY_TERMINAL_TRANSCRIPT_ROWS, DEFAULT_TERMINAL_TRANSCRIPT_ROWS)
        set(value) = preferences.edit().putInt(KEY_TERMINAL_TRANSCRIPT_ROWS, value.coerceIn(500, 5000)).apply()

    var ompUseProjectDirectory: Boolean
        get() = preferences.getBoolean(KEY_OMP_USE_PROJECT_DIRECTORY, true)
        set(value) = preferences.edit().putBoolean(KEY_OMP_USE_PROJECT_DIRECTORY, value).apply()

    var dshBackgroundEnabled: Boolean
        get() = preferences.getBoolean(KEY_DSH_BACKGROUND_ENABLED, false)
        set(value) = preferences.edit().putBoolean(KEY_DSH_BACKGROUND_ENABLED, value).apply()

    var hasShownInitialSetup: Boolean
        get() = preferences.getBoolean(KEY_HAS_SHOWN_INITIAL_SETUP, false)
        set(value) = preferences.edit().putBoolean(KEY_HAS_SHOWN_INITIAL_SETUP, value).apply()

    companion object {
        private const val NAME = "workspace-settings"
        private const val KEY_HAS_SHOWN_INITIAL_SETUP = "has_shown_initial_setup"
        private const val KEY_APP_THEME_MODE = "app_theme_mode"
        private const val KEY_EDITOR_THEME_MODE = "editor_theme_mode"
        private const val KEY_EDITOR_FONT_SIZE = "editor_font_size"
        private const val KEY_EDITOR_WORD_WRAP = "editor_word_wrap"
        private const val KEY_EDITOR_LINE_NUMBERS = "editor_line_numbers"
        private const val KEY_EDITOR_SYMBOL_BAR = "editor_symbol_bar"
        private const val KEY_EDITOR_HOVER_INFO = "editor_hover_info"
        private const val KEY_TERMINAL_FONT_SIZE = "terminal_font_size"
        private const val KEY_TERMINAL_KEEP_SCREEN_ON = "terminal_keep_screen_on"
        private const val KEY_TERMINAL_TRANSCRIPT_ROWS = "terminal_transcript_rows"
        private const val KEY_OMP_USE_PROJECT_DIRECTORY = "omp_use_project_directory"
        private const val KEY_DSH_BACKGROUND_ENABLED = "dsh_background_enabled"
        private const val DEFAULT_EDITOR_FONT_SIZE = 12f
        private const val DEFAULT_TERMINAL_FONT_SIZE = 12f
        private const val DEFAULT_TERMINAL_TRANSCRIPT_ROWS = 2000
    }
}
