package top.wsdx233.love2droid

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.regex.PatternSyntaxException
import kotlin.coroutines.coroutineContext

internal class ProjectSearchSheet(
    private val activity: AppCompatActivity,
    private val scope: CoroutineScope,
    private val project: Project,
    private val lspController: LuaLspController?,
    private val onOpen: (EditorNavigationTarget) -> Unit,
) {
    private val dialog = BottomSheetDialog(activity)
    private val queryInput = EditText(activity)
    private val regexButton = MaterialButton(activity)
    private val tabs = TabLayout(activity)
    private val progress = ProgressBar(activity)
    private val message = TextView(activity)
    private val adapter = ProjectSearchAdapter { result ->
        dialog.dismiss()
        onOpen(result.target)
    }
    private var mode = ProjectSearchMode.FILES
    private var searchJob: Job? = null

    fun show() {
        val content = buildContent()
        dialog.setContentView(content)
        dialog.setOnShowListener {
            dialog.behavior.skipCollapsed = true
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
        dialog.setOnDismissListener { searchJob?.cancel() }
        dialog.show()
        queryInput.requestFocus()
    }

    private fun buildContent(): View {
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        content.addView(TextView(activity).apply {
            text = activity.getString(R.string.project_search_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(20), dp(8), dp(20), dp(8))
        })
        val searchRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(12), 0)
        }
        queryInput.apply {
            hint = activity.getString(R.string.project_search_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            maxLines = 1
            doAfterTextChanged { scheduleSearch() }
        }
        searchRow.addView(queryInput, LinearLayout.LayoutParams(0, dp(52), 1f))
        regexButton.apply {
            text = activity.getString(R.string.regex_short)
            contentDescription = activity.getString(R.string.use_regular_expression)
            isCheckable = true
            isAllCaps = false
            minWidth = 0
            insetLeft = 0
            insetRight = 0
            insetTop = 0
            insetBottom = 0
            setPadding(dp(8), 0, dp(8), 0)
            val primary = MaterialColors.getColor(
                activity,
                androidx.appcompat.R.attr.colorPrimary,
                Color.BLUE,
            )
            val primaryContainer = MaterialColors.getColor(
                activity,
                com.google.android.material.R.attr.colorPrimaryContainer,
                primary,
            )
            val onPrimaryContainer = MaterialColors.getColor(
                activity,
                com.google.android.material.R.attr.colorOnPrimaryContainer,
                Color.WHITE,
            )
            val onSurfaceVariant = MaterialColors.getColor(
                activity,
                com.google.android.material.R.attr.colorOnSurfaceVariant,
                Color.DKGRAY,
            )
            val outline = MaterialColors.getColor(
                activity,
                com.google.android.material.R.attr.colorOutline,
                onSurfaceVariant,
            )
            val checkedState = intArrayOf(android.R.attr.state_checked)
            val uncheckedState = intArrayOf()
            backgroundTintList = ColorStateList(
                arrayOf(checkedState, uncheckedState),
                intArrayOf(primaryContainer, Color.TRANSPARENT),
            )
            setTextColor(
                ColorStateList(
                    arrayOf(checkedState, uncheckedState),
                    intArrayOf(onPrimaryContainer, primary),
                ),
            )
            strokeWidth = dp(1)
            strokeColor = ColorStateList(
                arrayOf(checkedState, uncheckedState),
                intArrayOf(primary, outline),
            )
            addOnCheckedChangeListener { _, _ -> scheduleSearch() }
        }
        searchRow.addView(regexButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
        content.addView(searchRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        listOf(
            ProjectSearchMode.FILES to R.string.project_search_files,
            ProjectSearchMode.TEXT to R.string.project_search_text,
            ProjectSearchMode.SYMBOLS to R.string.project_search_symbols,
        ).forEach { (_, title) -> tabs.addTab(tabs.newTab().setText(title)) }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                mode = ProjectSearchMode.entries[tab.position]
                scheduleSearch(immediate = true)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        content.addView(tabs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        progress.visibility = View.GONE
        content.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)))
        message.apply {
            gravity = android.view.Gravity.CENTER
            setTextColor(MaterialColors.getColor(activity, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY))
            setPadding(dp(24), dp(24), dp(24), dp(24))
            text = activity.getString(R.string.project_search_enter_query)
        }
        content.addView(message, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = this@ProjectSearchSheet.adapter
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxSheetHeight()))
        return content
    }

    private fun scheduleSearch(immediate: Boolean = false) {
        searchJob?.cancel()
        adapter.submitItems(emptyList())
        val query = queryInput.text.toString()
        queryInput.error = null
        if (query.isEmpty()) {
            progress.visibility = View.GONE
            message.visibility = View.VISIBLE
            message.text = activity.getString(R.string.project_search_enter_query)
            return
        }
        searchJob = scope.launch {
            if (!immediate) delay(SEARCH_DEBOUNCE_MS)
            progress.visibility = View.VISIBLE
            message.visibility = View.GONE
            try {
                val results = when (mode) {
                    ProjectSearchMode.FILES -> searchFiles(query)
                    ProjectSearchMode.TEXT -> searchText(query)
                    ProjectSearchMode.SYMBOLS -> searchSymbols(query)
                }
                if (!isActive) return@launch
                adapter.submitItems(results)
                message.visibility = if (results.isEmpty()) View.VISIBLE else View.GONE
                message.text = activity.getString(R.string.project_search_no_results)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: PatternSyntaxException) {
                queryInput.error = activity.getString(R.string.invalid_regular_expression, error.description)
                message.visibility = View.VISIBLE
                message.text = activity.getString(R.string.project_search_invalid_query)
            } catch (error: Throwable) {
                message.visibility = View.VISIBLE
                message.text = activity.getString(
                    R.string.project_search_failed,
                    error.rootMessage(),
                )
            } finally {
                if (isActive) progress.visibility = View.GONE
            }
        }
    }

    private suspend fun searchFiles(query: String): List<ProjectSearchResult> = withContext(Dispatchers.IO) {
        val currentJob = coroutineContext[Job]
        ProjectSearchEngine.searchFiles(project.root, query, regexButton.isChecked) {
            currentJob?.isActive != true
        }
    }

    private suspend fun searchText(query: String): List<ProjectSearchResult> = withContext(Dispatchers.IO) {
        val currentJob = coroutineContext[Job]
        ProjectSearchEngine.searchText(project.root, query, regexButton.isChecked) {
            currentJob?.isActive != true
        }
    }

    private suspend fun searchSymbols(query: String): List<ProjectSearchResult> {
        val semanticSymbols = runCatching {
            lspController?.searchWorkspaceSymbols(if (regexButton.isChecked) "" else query).orEmpty()
        }.getOrDefault(emptyList())
        val semantic = withContext(Dispatchers.IO) {
            ProjectSearchEngine.semanticSymbols(project.root, query, regexButton.isChecked, semanticSymbols)
        }
        if (semantic.isNotEmpty()) return semantic
        return withContext(Dispatchers.IO) {
            val currentJob = coroutineContext[Job]
            ProjectSearchEngine.searchLocalSymbols(project.root, query, regexButton.isChecked) {
                currentJob?.isActive != true
            }
        }
    }

    private fun Throwable.rootMessage(): String {
        var value: Throwable = this
        while (value.cause != null && value.cause !== value) value = checkNotNull(value.cause)
        return value.message ?: value.javaClass.simpleName
    }

    private fun maxSheetHeight(): Int = minOf(
        (activity.resources.displayMetrics.heightPixels * 0.62f).toInt(),
        dp(560),
    )

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 220L
    }
}
