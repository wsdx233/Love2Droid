package top.wsdx233.love2droid

import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

class SettingsActivity : AppCompatActivity() {
    private lateinit var content: LinearLayout
    private lateinit var settings: SettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsStore(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val topInset = View(this).apply { setBackgroundColor(getColor(R.color.action_bar_background)) }
        root.addView(topInset, LinearLayout.LayoutParams.MATCH_PARENT, 0)
        val toolbar = MaterialToolbar(this).apply {
            setBackgroundColor(getColor(R.color.action_bar_background))
            setTitleTextColor(getColor(R.color.action_bar_foreground))
            setNavigationIconTint(getColor(R.color.action_bar_foreground))
            title = getString(R.string.settings)
            setNavigationIcon(R.drawable.ic_arrow_back)
            setNavigationContentDescription(R.string.back)
            setNavigationOnClickListener { finish() }
        }
        root.addView(toolbar, LinearLayout.LayoutParams.MATCH_PARENT, dp(64))
        val scroll = ScrollView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(32))
        }
        scroll.addView(content, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(scroll, LinearLayout.LayoutParams.MATCH_PARENT, 0)
        (scroll.layoutParams as LinearLayout.LayoutParams).weight = 1f
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            topInset.layoutParams = topInset.layoutParams.apply { height = bars.top }
            root.setPadding(root.paddingLeft, root.paddingTop, root.paddingRight, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        render()
    }

    private fun render() {
        content.removeAllViews()

        addSection(R.string.settings_appearance)
        addChoicePreference(
            R.string.settings_app_theme,
            R.string.settings_app_theme_summary,
            settings.appThemeMode,
        ) { mode ->
            settings.appThemeMode = mode
            AppCompatDelegate.setDefaultNightMode(mode.toAppCompatNightMode())
            render()
        }
        addChoicePreference(
            R.string.settings_editor_theme,
            R.string.settings_editor_theme_summary,
            settings.editorThemeMode,
        ) { mode ->
            settings.editorThemeMode = mode
            render()
        }

        addSection(R.string.settings_editor)
        addSeekPreference(
            R.string.settings_editor_font_size,
            R.string.settings_editor_font_size_summary,
            8,
            24,
            settings.editorFontSize.toInt(),
        ) { settings.editorFontSize = it.toFloat() }
        addSwitchPreference(
            R.string.settings_editor_word_wrap,
            R.string.settings_editor_word_wrap_summary,
            settings.editorWordWrap,
        ) { settings.editorWordWrap = it }
        addSwitchPreference(
            R.string.settings_editor_line_numbers,
            R.string.settings_editor_line_numbers_summary,
            settings.editorLineNumbers,
        ) { settings.editorLineNumbers = it }

        addSection(R.string.settings_terminal)
        addSeekPreference(
            R.string.settings_terminal_font_size,
            R.string.settings_terminal_font_size_summary,
            8,
            24,
            settings.terminalFontSize.toInt(),
        ) { settings.terminalFontSize = it.toFloat() }
        addSeekPreference(
            R.string.settings_terminal_history,
            R.string.settings_terminal_history_summary,
            500,
            5000,
            settings.terminalTranscriptRows,
            step = 500,
        ) { settings.terminalTranscriptRows = it }
        addSwitchPreference(
            R.string.settings_terminal_keep_screen_on,
            R.string.settings_terminal_keep_screen_on_summary,
            settings.terminalKeepScreenOn,
        ) { settings.terminalKeepScreenOn = it }

        addSection(R.string.settings_omp)
        addSwitchPreference(
            R.string.settings_omp_project_directory,
            R.string.settings_omp_project_directory_summary,
            settings.ompUseProjectDirectory,
        ) { settings.ompUseProjectDirectory = it }
        addInfoPreference(R.string.settings_omp_config, getString(R.string.settings_omp_config_summary))
        val modelButton = MaterialButton(this).apply {
            text = getString(R.string.settings_models)
            setIconResource(R.drawable.ic_settings)
            setOnClickListener { startActivity(android.content.Intent(this@SettingsActivity, ModelSettingsActivity::class.java)) }
        }
        content.addView(modelButton, LinearLayout.LayoutParams.MATCH_PARENT, dp(52))
    }

    private fun addSection(title: Int) {
        val view = TextView(this).apply {
            text = getString(title)
            setTextColor(currentTextColor())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setPadding(0, dp(20), 0, dp(8))
        }
        content.addView(view, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun addChoicePreference(
        title: Int,
        summary: Int,
        selected: ThemeMode,
        onChanged: (ThemeMode) -> Unit,
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setPadding(0, dp(10), 0, dp(10))
        }
        row.addView(preferenceLabels(title, summary), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(this).apply {
            text = getString(selected.labelRes())
            setTextColor(currentSecondaryTextColor())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            contentDescription = getString(title)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        row.setOnClickListener {
            val modes = ThemeMode.entries
            MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setSingleChoiceItems(
                    modes.map { getString(it.labelRes()) }.toTypedArray(),
                    selected.ordinal,
                ) { dialog, which ->
                    onChanged(modes[which])
                    dialog.dismiss()
                }
                .show()
        }
        content.addView(row)
    }

    private fun addSwitchPreference(title: Int, summary: Int, checked: Boolean, onChanged: (Boolean) -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        val labels = preferenceLabels(title, summary)
        row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val switch = MaterialSwitch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, value -> onChanged(value) }
            contentDescription = getString(title)
        }
        row.addView(switch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        row.setOnClickListener { switch.isChecked = !switch.isChecked }
        content.addView(row)
    }

    private fun addSeekPreference(
        title: Int,
        summary: Int,
        minimum: Int,
        maximum: Int,
        initial: Int,
        step: Int = 1,
        onChanged: (Int) -> Unit,
    ) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val label = TextView(this).apply { textSize = 16f; text = getString(title) }
        val summaryView = TextView(this).apply {
            text = getString(summary)
            textSize = 13f
            setTextColor(currentSecondaryTextColor())
        }
        val value = TextView(this).apply {
            setTextColor(currentSecondaryTextColor())
            textSize = 13f
        }
        val bar = SeekBar(this).apply {
            max = (maximum - minimum) / step
            progress = ((initial.coerceIn(minimum, maximum) - minimum) / step)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val actual = minimum + progress * step
                    value.text = getString(R.string.settings_value, actual)
                    if (fromUser) onChanged(actual)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        value.text = getString(R.string.settings_value, minimum + bar.progress * step)
        box.addView(label)
        box.addView(summaryView)
        box.addView(value)
        box.addView(bar)
        content.addView(box)
    }

    private fun addInfoPreference(title: Int, summary: String) {
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            addView(TextView(this@SettingsActivity).apply { text = getString(title); textSize = 16f })
            addView(TextView(this@SettingsActivity).apply {
                text = summary
                textSize = 13f
                setTextColor(currentSecondaryTextColor())
            })
        }
        content.addView(labels, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun preferenceLabels(title: Int, summary: Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@SettingsActivity).apply { text = getString(title); textSize = 16f })
            addView(TextView(this@SettingsActivity).apply {
                text = getString(summary)
                textSize = 13f
                setTextColor(currentSecondaryTextColor())
            })
        }
    }

    private fun ThemeMode.labelRes(): Int = when (this) {
        ThemeMode.LIGHT -> R.string.settings_theme_light
        ThemeMode.DARK -> R.string.settings_theme_dark
        ThemeMode.SYSTEM -> R.string.settings_theme_system
    }

    private fun currentTextColor(): Int =
        MaterialColors.getColor(content, com.google.android.material.R.attr.colorOnSurface)

    private fun currentSecondaryTextColor(): Int =
        MaterialColors.getColor(content, com.google.android.material.R.attr.colorOnSurfaceVariant)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
