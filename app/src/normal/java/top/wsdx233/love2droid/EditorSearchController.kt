package top.wsdx233.love2droid

import android.content.Context
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import io.github.rosemoe.sora.util.regex.RegexBackrefGrammar
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import java.util.regex.PatternSyntaxException

internal class EditorSearchController(
    private val context: Context,
    private val editor: CodeEditor,
    root: View,
    private val onError: (String) -> Unit,
) {
    private val panel: View = root.findViewById(R.id.editor_search_panel)
    private val expandButton: ImageButton = root.findViewById(R.id.search_expand)
    private val queryInput: EditText = root.findViewById(R.id.search_query)
    private val regexButton: MaterialButton = root.findViewById(R.id.search_regex)
    private val previousButton: ImageButton = root.findViewById(R.id.search_previous)
    private val nextButton: ImageButton = root.findViewById(R.id.search_next)
    private val closeButton: ImageButton = root.findViewById(R.id.search_close)
    private val replacementRow: LinearLayout = root.findViewById(R.id.search_replace_row)
    private val replacementInput: EditText = root.findViewById(R.id.search_replacement)
    private val replaceButton: MaterialButton = root.findViewById(R.id.search_replace)
    private val replaceAllButton: MaterialButton = root.findViewById(R.id.search_replace_all)

    private var open = false
    private var editorAvailable = false
    private var replaceExpanded = false
    private var queryValid = false

    val isVisible: Boolean
        get() = open && editorAvailable

    init {
        queryInput.doAfterTextChanged { submitQuery() }
        queryInput.setOnEditorActionListener { _, _, _ ->
            moveToNext()
            true
        }
        regexButton.addOnCheckedChangeListener { _, _ -> submitQuery() }
        expandButton.setOnClickListener { setReplaceExpanded(!replaceExpanded) }
        previousButton.setOnClickListener { moveToPrevious() }
        nextButton.setOnClickListener { moveToNext() }
        closeButton.setOnClickListener { close() }
        replacementInput.setOnEditorActionListener { _, _, _ ->
            replaceCurrent()
            true
        }
        replaceButton.setOnClickListener { replaceCurrent() }
        replaceAllButton.setOnClickListener { replaceAll() }
        updateActionState()
    }

    fun show() {
        open = true
        updatePanelVisibility()
        if (!editorAvailable) return
        submitQuery()
        queryInput.requestFocus()
        queryInput.setSelection(queryInput.text.length)
        queryInput.post {
            context.getSystemService(InputMethodManager::class.java)
                ?.showSoftInput(queryInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun close() {
        if (!open) return
        open = false
        panel.visibility = View.GONE
        editor.searcher.stopSearch()
        queryInput.error = null
        queryValid = false
        updateActionState()
        context.getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(queryInput.windowToken, 0)
        if (editorAvailable) editor.requestFocus()
    }

    fun setEditorAvailable(available: Boolean) {
        if (editorAvailable == available) return
        editorAvailable = available
        updatePanelVisibility()
        if (available && open) {
            submitQuery()
        } else {
            editor.searcher.stopSearch()
        }
    }

    fun dispose() {
        editor.searcher.stopSearch()
    }

    private fun updatePanelVisibility() {
        panel.visibility = if (open && editorAvailable) View.VISIBLE else View.GONE
    }

    private fun setReplaceExpanded(expanded: Boolean) {
        replaceExpanded = expanded
        replacementRow.visibility = if (expanded) View.VISIBLE else View.GONE
        expandButton.animate()
            .rotation(if (expanded) 90f else 0f)
            .setDuration(EXPAND_ANIMATION_MS)
            .start()
        expandButton.contentDescription = context.getString(
            if (expanded) R.string.collapse_replace else R.string.expand_replace,
        )
        if (expanded) {
            replacementInput.requestFocus()
            replacementInput.setSelection(replacementInput.text.length)
        } else {
            queryInput.requestFocus()
        }
    }

    private fun submitQuery() {
        if (!open || !editorAvailable) return
        val pattern = queryInput.text.toString()
        if (pattern.isEmpty()) {
            editor.searcher.stopSearch()
            queryInput.error = null
            queryValid = false
            updateActionState()
            return
        }
        val useRegex = regexButton.isChecked
        val options = EditorSearcher.SearchOptions(
            if (useRegex) {
                EditorSearcher.SearchOptions.TYPE_REGULAR_EXPRESSION
            } else {
                EditorSearcher.SearchOptions.TYPE_NORMAL
            },
            false,
            if (useRegex) RegexBackrefGrammar.DEFAULT else null,
        )
        try {
            editor.searcher.search(pattern, options)
            queryInput.error = null
            queryValid = true
        } catch (error: PatternSyntaxException) {
            editor.searcher.stopSearch()
            queryInput.error = context.getString(
                R.string.invalid_regular_expression,
                error.description,
            )
            queryValid = false
        }
        updateActionState()
    }

    private fun moveToPrevious() {
        if (!queryValid) return
        runSearchAction { editor.searcher.gotoPrevious() }
    }

    private fun moveToNext() {
        if (!queryValid) return
        runSearchAction { editor.searcher.gotoNext() }
    }

    private fun replaceCurrent() {
        if (!queryValid) return
        runSearchAction { editor.searcher.replaceCurrentMatch(replacementInput.text.toString()) }
    }

    private fun replaceAll() {
        if (!queryValid) return
        runSearchAction { editor.searcher.replaceAll(replacementInput.text.toString()) }
    }

    private inline fun runSearchAction(action: () -> Unit) {
        try {
            action()
        } catch (error: IllegalStateException) {
            onError(error.message ?: context.getString(R.string.search_not_ready))
        }
    }

    private fun updateActionState() {
        previousButton.isEnabled = queryValid
        nextButton.isEnabled = queryValid
        replaceButton.isEnabled = queryValid
        replaceAllButton.isEnabled = queryValid
    }

    private companion object {
        const val EXPAND_ANIMATION_MS = 160L
    }
}
