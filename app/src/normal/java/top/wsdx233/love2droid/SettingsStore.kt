package top.wsdx233.love2droid

import android.content.Context

/** Small, process-safe store for preferences that affect the editor workspace. */
internal class SettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var editorFontSize: Float
        get() = preferences.getFloat(KEY_EDITOR_FONT_SIZE, DEFAULT_EDITOR_FONT_SIZE)
        set(value) = preferences.edit().putFloat(KEY_EDITOR_FONT_SIZE, value.coerceIn(8f, 24f)).apply()

    var editorWordWrap: Boolean
        get() = preferences.getBoolean(KEY_EDITOR_WORD_WRAP, false)
        set(value) = preferences.edit().putBoolean(KEY_EDITOR_WORD_WRAP, value).apply()

    var editorLineNumbers: Boolean
        get() = preferences.getBoolean(KEY_EDITOR_LINE_NUMBERS, true)
        set(value) = preferences.edit().putBoolean(KEY_EDITOR_LINE_NUMBERS, value).apply()

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


    companion object {
        private const val NAME = "workspace-settings"
        private const val KEY_EDITOR_FONT_SIZE = "editor_font_size"
        private const val KEY_EDITOR_WORD_WRAP = "editor_word_wrap"
        private const val KEY_EDITOR_LINE_NUMBERS = "editor_line_numbers"
        private const val KEY_TERMINAL_FONT_SIZE = "terminal_font_size"
        private const val KEY_TERMINAL_KEEP_SCREEN_ON = "terminal_keep_screen_on"
        private const val KEY_TERMINAL_TRANSCRIPT_ROWS = "terminal_transcript_rows"
        private const val KEY_OMP_USE_PROJECT_DIRECTORY = "omp_use_project_directory"
        private const val DEFAULT_EDITOR_FONT_SIZE = 12f
        private const val DEFAULT_TERMINAL_FONT_SIZE = 12f
        private const val DEFAULT_TERMINAL_TRANSCRIPT_ROWS = 2000
    }
}
