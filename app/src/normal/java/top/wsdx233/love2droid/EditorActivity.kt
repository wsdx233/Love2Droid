package top.wsdx233.love2droid

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.MenuItem
import android.view.animation.PathInterpolator
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.ClickEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import org.eclipse.tm4e.core.registry.IThemeSource
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.SelectionMovement
import io.github.rosemoe.sora.widget.subscribeAlways
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetBehavior
import io.github.rosemoe.sora.widget.component.EditorTextActionWindow
import io.github.rosemoe.sora.widget.getComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class EditorActivity : AppCompatActivity() {
    private lateinit var drawer: androidx.drawerlayout.widget.DrawerLayout
    private lateinit var toolbar: MaterialToolbar
    private lateinit var tabContainer: LinearLayout
    private lateinit var symbolBar: LinearLayout
    private lateinit var symbolScroll: View
    private lateinit var editor: CodeEditor
    private lateinit var welcomePage: View
    private lateinit var drawerProjectTitle: TextView
    private lateinit var drawerProjectPath: TextView
    private lateinit var drawerDirectoryMenu: View
    private lateinit var selectionActionBar: LinearLayout
    private lateinit var browserAdapter: FileBrowserAdapter
    private lateinit var terminalView: TerminalView
    private lateinit var terminalKeyBar: LinearLayout
    private var lspController: LuaLspController? = null

    private val projectRepository by lazy { ProjectRepository(this) }
    private val settings by lazy { SettingsStore(this) }
    private val editorSession = EditorSession()
    private val selectedPaths = linkedSetOf<String>()
    private var currentProject: Project? = null
    private var currentDirectory: File? = null
    private var visibleItems: List<BrowserItem> = emptyList()
    private var suppressEditorEvents = false
    private var selectionMode = false
    private var selectionAnchorPath: String? = null
    private var clipboardFiles: List<File> = emptyList()
    private var clipboardIsCut = false
    private var openedManagerForEmptyState = false
    private lateinit var editorTopInset: View
    private lateinit var drawerTopInset: View
    private var textMateReady = false
    private var lspJob: Job? = null
    private var workspaceRestoreJob: Job? = null
    private var symbolNavigationJob: Job? = null
    private var directoryObserver: FileObserver? = null
    private var directoryRefreshGeneration = 0L
    private val directoryRefreshHandler = Handler(Looper.getMainLooper())
    private var symbolDefinitionButton: ImageButton? = null
    private var symbolUsagesButton: ImageButton? = null
    private val directoryRefreshRunnable = Runnable { refreshFileList() }
    private var terminalCounter = 0
    private var ctrlPressed = false
    private var altPressed = false
    private var ctrlButton: TextView? = null
    private var altButton: TextView? = null
    private val mapleTypeface: Typeface by lazy {
        requireNotNull(ResourcesCompat.getFont(this, R.font.maple_mono_nf_cn_regular))
    }
    private val terminalDefaultTextSizePx: Float
        get() = sp(settings.terminalFontSize)
    private val terminalMinTextSizePx by lazy { sp(8f) }
    private val terminalMaxTextSizePx by lazy { sp(32f) }

    private val terminalSessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            if (::terminalView.isInitialized && terminalView.mTermSession === changedSession) {
                terminalView.onScreenUpdated()
            }
            observeOmpSession(changedSession)
        }

        override fun onTitleChanged(changedSession: TerminalSession) {
            val tab = editorSession.tabs.filterIsInstance<TerminalTab>()
                .firstOrNull { it.session === changedSession }
            val title = changedSession.title?.trim().orEmpty()
            if (tab != null && title.isNotEmpty()) {
                tab.title = title
                refreshTabs()
            }
        }

        override fun onSessionFinished(finishedSession: TerminalSession) {
            observeOmpSession(finishedSession)
            editorSession.tabs.filterIsInstance<TerminalTab>()
                .firstOrNull { it.session === finishedSession }
                ?.let { tab ->
                    tab.title = getString(R.string.terminal_finished, tab.title)
                    refreshTabs()
                }
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.terminal), text.orEmpty()))
        }

        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip ?: return
            if (clip.itemCount > 0) {
                session?.write(clip.getItemAt(0).coerceToText(this@EditorActivity).toString())
            }
        }

        override fun onBell(session: TerminalSession) = Unit
        override fun onColorsChanged(session: TerminalSession) = Unit
        override fun onTerminalCursorStateChange(state: Boolean) = Unit
        override fun setTerminalShellPid(session: TerminalSession, pid: Int) {
            val tab = editorSession.tabs.filterIsInstance<TerminalTab>()
                .firstOrNull { it.session === session }
                ?: return
            if (pid <= 0) return
            tab.shellPid = pid
            if (tab.pendingStartupCommand != null && tab.isOmp && tab.ompSessionId == null) {
                tab.ompStartedAtMillis = System.currentTimeMillis()
            }
            tab.pendingStartupCommand?.let { command ->
                tab.pendingStartupCommand = null
                session.write("$command\n")
            }
            if (tab.isOmp && tab.ompSessionId == null) {
                terminalView.postDelayed(
                    { observeOmpSession(session) },
                    OMP_SESSION_DISCOVERY_INTERVAL_MS,
                )
            }
        }
        override fun getTerminalCursorStyle(): Int = 0
        override fun logError(tag: String?, message: String?) = Unit
        override fun logWarn(tag: String?, message: String?) = Unit
        override fun logInfo(tag: String?, message: String?) = Unit
        override fun logDebug(tag: String?, message: String?) = Unit
        override fun logVerbose(tag: String?, message: String?) = Unit
        override fun logStackTraceWithMessage(tag: String?, message: String?, error: Exception?) = Unit
        override fun logStackTrace(tag: String?, error: Exception?) = Unit
    }

    private val projectManagerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val id = result.data?.getStringExtra(ProjectManagerActivity.EXTRA_PROJECT_ID)
            if (id != null) projectRepository.findProject(id)?.let(::switchProject)
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            lspController?.dismissHover()
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContentView(R.layout.activity_editor)

        drawer = findViewById(R.id.drawer_layout)
        toolbar = findViewById(R.id.toolbar)
        tabContainer = findViewById(R.id.tab_container)
        symbolBar = findViewById(R.id.symbol_bar)
        symbolScroll = findViewById(R.id.symbol_scroll)
        editorTopInset = findViewById(R.id.editor_top_inset)
        drawerTopInset = findViewById(R.id.drawer_top_inset)
        editor = findViewById(R.id.code_editor)
        terminalView = findViewById(R.id.terminal_view)
        terminalKeyBar = findViewById(R.id.terminal_key_bar)
        welcomePage = findViewById(R.id.welcome_page)
        drawerProjectTitle = findViewById(R.id.drawer_project_title)
        drawerProjectPath = findViewById(R.id.drawer_project_path)
        drawerDirectoryMenu = findViewById(R.id.drawer_directory_menu)
        selectionActionBar = findViewById(R.id.selection_action_bar)
        findViewById<View>(R.id.action_select_all).setOnClickListener { selectAllVisibleItems() }
        findViewById<View>(R.id.action_invert_selection).setOnClickListener { invertVisibleSelection() }
        findViewById<View>(R.id.action_clear_selection).setOnClickListener { clearSelection() }
        drawerDirectoryMenu.setOnClickListener(::showCurrentDirectoryMenu)
        findViewById<View>(R.id.welcome_open_file).setOnClickListener {
            drawer.openDrawer(GravityCompat.START)
        }
        findViewById<View>(R.id.welcome_new_file).setOnClickListener { newDocument() }

        setupWindowInsets()
        toolbar.navigationIcon = ContextCompat.getDrawable(this, R.drawable.ic_menu)
        toolbar.navigationContentDescription = getString(R.string.file_browser)
        toolbar.setNavigationOnClickListener { drawer.openDrawer(GravityCompat.START) }
        toolbar.inflateMenu(R.menu.editor_menu)
        tintToolbarMenuIcons()
        toolbar.setOnMenuItemClickListener(::onToolbarItemSelected)


        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    drawer.isDrawerOpen(GravityCompat.START) && selectionMode -> clearSelection()
                    drawer.isDrawerOpen(GravityCompat.START) && navigateToParentDirectory() -> Unit
                    drawer.isDrawerOpen(GravityCompat.START) -> drawer.closeDrawer(GravityCompat.START)
                    selectionMode -> clearSelection()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
        setupSymbolBar()
        setupTerminal()
        setupEditorInput()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            lspController = LuaLspController(
                context = this,
                codeEditor = editor,
                onConnectionError = { error ->
                    toast(getString(R.string.lua_lsp_connection_failed, error.message ?: error.javaClass.simpleName))
                },
                onFileLink = ::openHoverFileLink,
            )
        }
        browserAdapter = FileBrowserAdapter(
            onClick = ::onBrowserItemClicked,
            onLongClick = ::showBrowserItemMenu,
            onSwipe = ::selectRangeFromSwipe,
        )
        findViewById<RecyclerView>(R.id.file_list).apply {
            layoutManager = LinearLayoutManager(this@EditorActivity)
            adapter = browserAdapter
            setHasFixedSize(true)
        }

        setupTextMate()
        editor.subscribeAlways<ContentChangeEvent> {
            if (!suppressEditorEvents) {
                editorSession.activeEditorTab?.let { tab ->
                    tab.text = editor.text.toString()
                    tab.dirty = true
                    refreshTabs()
                }
            }
        }

        val initial = projectRepository.lastOpenedProject()
        if (initial != null) {
            switchProject(initial)
        } else {
            showEmptyEditor()
            window.decorView.post { openProjectManagerIfNeeded() }
        }
    }
    private fun tintToolbarMenuIcons() {
        val iconColor = ContextCompat.getColor(this, android.R.color.white)
        for (index in 0 until toolbar.menu.size()) {
            val item = toolbar.menu.getItem(index)
            val icon = item.icon ?: continue
            val tinted = DrawableCompat.wrap(icon.mutate())
            DrawableCompat.setTint(tinted, iconColor)
            item.icon = tinted
        }
    }
    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(drawer) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            editorTopInset.layoutParams = editorTopInset.layoutParams.apply { height = systemBars.top }
            drawerTopInset.layoutParams = drawerTopInset.layoutParams.apply { height = systemBars.top }
            val selectionBarHeight = dp(SELECTION_ACTION_BAR_HEIGHT_DP) + systemBars.bottom
            if (selectionActionBar.layoutParams.height != selectionBarHeight) {
                selectionActionBar.layoutParams = selectionActionBar.layoutParams.apply {
                    height = selectionBarHeight
                }
            }
            selectionActionBar.setPadding(0, 0, 0, systemBars.bottom)
            val bottomInset = maxOf(systemBars.bottom, ime.bottom)
            listOf(symbolScroll, terminalKeyBar).forEach { bottomBar ->
                (bottomBar.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                    if (params.bottomMargin != bottomInset) {
                        params.bottomMargin = bottomInset
                        bottomBar.layoutParams = params
                    }
                }
            }
            insets
        }
        ViewCompat.requestApplyInsets(drawer)
    }

    private fun setupSymbolBar() {
        val selectableBackground = obtainStyledAttributes(
            intArrayOf(android.R.attr.selectableItemBackgroundBorderless),
        ).let { attributes ->
            val drawable = attributes.getDrawable(0)
            attributes.recycle()
            drawable
        }

        fun addButton(label: String, action: () -> Unit) {
            val button = TextView(this).apply {
                text = label
                textSize = 18f
                typeface = mapleTypeface
                gravity = Gravity.CENTER
                minWidth = dp(44)
                minHeight = dp(48)
                setTextColor(Color.LTGRAY)
                background = selectableBackground?.constantState?.newDrawable()
                contentDescription = label
                setOnClickListener { action() }
            }
            symbolBar.addView(button, LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.MATCH_PARENT))
        }

        addButton("←") { moveCursor(SelectionMovement.LEFT) }
        addButton("→") { moveCursor(SelectionMovement.RIGHT) }
        addButton("fun") { insertSymbol("function") }
        addButton("(") { insertSymbolPair("(", ")") }
        addButton("[") { insertSymbolPair("[", "]") }
        addButton("{") { insertSymbolPair("{", "}") }
        listOf("\"", "=", ":", ".", ",", "_", "+", "-", "*", "/", "\\", "%", "#", "^", "$", "?", "&", "|", "<", ">", "~", ";", "'")
            .forEach { symbol -> addButton(symbol) { insertSymbol(symbol) } }
    }

    private fun setupTerminal() {
        terminalView.setBackgroundColor(Color.BLACK)
        terminalView.setTextSize(terminalDefaultTextSizePx.toInt())
        terminalView.setTypeface(mapleTypeface)
        terminalView.keepScreenOn = settings.terminalKeepScreenOn
        terminalView.setTerminalViewClient(object : TerminalViewClient {
            override fun onScale(scale: Float): Float {
                val baseTextSizePx = terminalDefaultTextSizePx
                val textSizePx = (baseTextSizePx * scale).coerceIn(
                    terminalMinTextSizePx,
                    terminalMaxTextSizePx,
                )
                terminalView.setTextSize(textSizePx.toInt().coerceAtLeast(1))
                return textSizePx / baseTextSizePx
            }

            override fun onSingleTapUp(event: MotionEvent?) {
                terminalView.requestFocus()
                val input = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                input.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
            }

            override fun shouldBackButtonBeMappedToEscape(): Boolean = false
            override fun shouldEnforceCharBasedInput(): Boolean = false
            override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
            override fun isTerminalViewSelected(): Boolean = terminalView.isShown
            override fun copyModeChanged(copyMode: Boolean) = Unit
            override fun onKeyDown(keyCode: Int, event: KeyEvent?, session: TerminalSession?): Boolean = false
            override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean = false
            override fun onLongPress(event: MotionEvent?): Boolean = false

            override fun readControlKey(): Boolean {
                val active = ctrlPressed
                ctrlPressed = false
                updateTerminalModifierButtons()
                return active
            }

            override fun readAltKey(): Boolean {
                val active = altPressed
                altPressed = false
                updateTerminalModifierButtons()
                return active
            }

            override fun readShiftKey(): Boolean = false
            override fun readFnKey(): Boolean = false
            override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false
            override fun onEmulatorSet() = Unit
            override fun logError(tag: String?, message: String?) = Unit
            override fun logWarn(tag: String?, message: String?) = Unit
            override fun logInfo(tag: String?, message: String?) = Unit
            override fun logDebug(tag: String?, message: String?) = Unit
            override fun logVerbose(tag: String?, message: String?) = Unit
            override fun logStackTraceWithMessage(tag: String?, message: String?, error: Exception?) = Unit
            override fun logStackTrace(tag: String?, error: Exception?) = Unit
        })
        setupTerminalKeys()
    }

    private fun setupTerminalKeys() {
        val firstRow = listOf(
            TerminalKey("ESC", "\u001b"),
            TerminalKey("/", "/"),
            TerminalKey("—", "-"),
            TerminalKey("HOME", "\u001b[H"),
            TerminalKey("↑", "\u001b[A"),
            TerminalKey("END", "\u001b[F"),
            TerminalKey("PGUP", "\u001b[5~"),
        )
        val secondRow = listOf(
            TerminalKey("⇥", "\t"),
            TerminalKey("CTRL", modifier = TerminalModifier.CTRL),
            TerminalKey("ALT", modifier = TerminalModifier.ALT),
            TerminalKey("←", "\u001b[D"),
            TerminalKey("↓", "\u001b[B"),
            TerminalKey("→", "\u001b[C"),
            TerminalKey("PGDN", "\u001b[6~"),
        )
        terminalKeyBar.removeAllViews()
        terminalKeyBar.addView(createTerminalKeyRow(firstRow))
        terminalKeyBar.addView(View(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))
        terminalKeyBar.addView(createTerminalKeyRow(secondRow))
    }

    private fun createTerminalKeyRow(keys: List<TerminalKey>): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            keys.forEach { key ->
                val button = TextView(this@EditorActivity).apply {
                    text = key.label
                    setTextColor(Color.WHITE)
                    setTextSize(
                        TypedValue.COMPLEX_UNIT_SP,
                        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 11f else 12f,
                    )
                    typeface = mapleTypeface
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        setColor(TERMINAL_KEY_COLOR)
                        cornerRadius = dp(4).toFloat()
                    }
                    contentDescription = key.label
                }
                button.layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                    marginStart = dp(1)
                    marginEnd = dp(1)
                }
                when (key.modifier) {
                    TerminalModifier.CTRL -> {
                        ctrlButton = button
                        button.setOnClickListener {
                            ctrlPressed = !ctrlPressed
                            updateTerminalModifierButtons()
                            terminalView.requestFocus()
                        }
                    }
                    TerminalModifier.ALT -> {
                        altButton = button
                        button.setOnClickListener {
                            altPressed = !altPressed
                            updateTerminalModifierButtons()
                            terminalView.requestFocus()
                        }
                    }
                    null -> button.setOnClickListener {
                        (editorSession.activeTab as? TerminalTab)?.session?.write(key.sequence)
                        terminalView.requestFocus()
                    }
                }
                addView(button)
            }
        }
    }

    private fun updateTerminalModifierButtons() {
        (ctrlButton?.background as? GradientDrawable)?.setColor(
            if (ctrlPressed) TERMINAL_MODIFIER_COLOR else TERMINAL_KEY_COLOR,
        )
        (altButton?.background as? GradientDrawable)?.setColor(
            if (altPressed) TERMINAL_MODIFIER_COLOR else TERMINAL_KEY_COLOR,
        )
    }

    private fun setupEditorInput() {
        editor.typefaceText = mapleTypeface
        editor.typefaceLineNumber = mapleTypeface
        editor.setTextSize(settings.editorFontSize)
        editor.isLineNumberEnabled = settings.editorLineNumbers
        editor.isWordwrap = settings.editorWordWrap
        editor.isFocusableInTouchMode = true
        editor.setInputType(
            InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
        )
        editor.props.allowFullscreen = false
        setupSymbolNavigationButtons()
        editor.subscribeAlways<SelectionChangeEvent> {
            updateSymbolNavigationButtons()
        }
        editor.subscribeAlways<ClickEvent> {
            lspController?.dismissHover()
        }
        updateSymbolNavigationButtons()
    }

    private fun setupSymbolNavigationButtons() {
        val actionWindow = editor.getComponent<EditorTextActionWindow>()
        val horizontalScroll = actionWindow.getView()
            .findViewById<ViewGroup>(io.github.rosemoe.sora.R.id.panel_hv)
        val buttonRow = horizontalScroll?.getChildAt(0) as? ViewGroup ?: return
        val selectableBackground = obtainStyledAttributes(
            intArrayOf(android.R.attr.selectableItemBackgroundBorderless),
        ).let { attributes ->
            val drawable = attributes.getDrawable(0)
            attributes.recycle()
            drawable
        }
        val iconTint = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorOnSurface,
            Color.WHITE,
        )

        fun addAction(icon: Int, description: Int, action: () -> Unit): ImageButton {
            return ImageButton(this).apply {
                layoutParams = ViewGroup.LayoutParams(dp(45), dp(45))
                setImageResource(icon)
                imageTintList = android.content.res.ColorStateList.valueOf(iconTint)
                background = selectableBackground?.constantState?.newDrawable()
                contentDescription = getString(description)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                visibility = View.GONE
                setOnClickListener {
                    actionWindow.dismiss()
                    action()
                }
            }.also(buttonRow::addView)
        }

        symbolDefinitionButton = addAction(
            R.drawable.ic_symbol_definition,
            R.string.go_to_definition,
        ) {
            currentSelectedSymbol()?.let { selected ->
                findDefinition(selected.file, selected.line, selected.column)
            }
        }
        symbolUsagesButton = addAction(
            R.drawable.ic_symbol_references,
            R.string.find_usages,
        ) {
            currentSelectedSymbol()?.let { selected ->
                findUsages(selected.file, selected.line, selected.column)
            }
        }
    }

    private data class SelectedSymbol(
        val file: File,
        val line: Int,
        val column: Int,
    )

    private fun currentSelectedSymbol(): SelectedSymbol? {
        val file = editorSession.activeEditorTab?.file ?: return null
        val start = editor.cursor.left
        val end = editor.cursor.right
        selectedSymbol(editor.text, start, end) ?: return null
        val position = editor.text.indexer.getCharPosition(start)
        return SelectedSymbol(file, position.line, position.column)
    }

    private fun updateSymbolNavigationButtons() {
        val selected = currentSelectedSymbol()
        val visible = selected != null && lspController?.isNavigationAvailable(selected.file) == true
        val visibility = if (visible) View.VISIBLE else View.GONE
        symbolDefinitionButton?.visibility = visibility
        symbolUsagesButton?.visibility = visibility
    }

    private fun findDefinition(file: File, line: Int, column: Int) {
        val controller = lspController ?: return
        val project = currentProject ?: return
        symbolNavigationJob?.cancel()
        symbolNavigationJob = lifecycleScope.launch {
            try {
                val locations = controller.findDefinitions(file, line, column)
                if (currentProject?.id != project.id) return@launch
                if (locations.isEmpty()) {
                    toast(getString(R.string.symbol_definition_not_found))
                    return@launch
                }
                val targets = withContext(Dispatchers.IO) {
                    locations.mapNotNull { resolveProjectNavigationTarget(project.root, it) }
                }
                when {
                    targets.isEmpty() -> toast(getString(R.string.symbol_location_outside_project))
                    targets.size == 1 -> openFile(targets.single().file, targets.single())
                    else -> showNavigationResults(R.string.definition_results, project, targets)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                toast(getString(R.string.symbol_navigation_failed, error.rootMessage()))
            }
        }
    }

    private fun findUsages(file: File, line: Int, column: Int) {
        val controller = lspController ?: return
        val project = currentProject ?: return
        symbolNavigationJob?.cancel()
        symbolNavigationJob = lifecycleScope.launch {
            try {
                val locations = controller.findReferences(file, line, column)
                if (currentProject?.id != project.id) return@launch
                if (locations.isEmpty()) {
                    toast(getString(R.string.symbol_usages_not_found))
                    return@launch
                }
                val targets = withContext(Dispatchers.IO) {
                    locations.mapNotNull { resolveProjectNavigationTarget(project.root, it) }
                }
                if (targets.isEmpty()) {
                    toast(getString(R.string.symbol_location_outside_project))
                    return@launch
                }
                showNavigationResults(R.string.usage_results, project, targets)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                toast(getString(R.string.symbol_navigation_failed, error.rootMessage()))
            }
        }
    }

    private suspend fun showNavigationResults(titleRes: Int, project: Project, targets: List<EditorNavigationTarget>) {
        val items = withContext(Dispatchers.IO) {
            buildSymbolNavigationItems(project.root, targets)
        }
        if (currentProject?.id != project.id || items.isEmpty()) return
        val dialog = BottomSheetDialog(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@EditorActivity).apply {
                text = getString(R.string.symbol_results_title, getString(titleRes), items.size)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(20), dp(20), dp(20), dp(12))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val results = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@EditorActivity)
            adapter = SymbolNavigationAdapter(items) { target ->
                dialog.dismiss()
                openFile(target.file, target)
            }
        }
        val maxHeight = minOf((resources.displayMetrics.heightPixels * 0.65f).toInt(), dp(560))
        content.addView(results, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxHeight))
        dialog.setContentView(content)
        dialog.setOnShowListener {
            dialog.behavior.skipCollapsed = true
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
        dialog.show()
    }

    private fun openHoverFileLink(link: String) {
        lspController?.dismissHover()
        val project = currentProject ?: run {
            toast(getString(R.string.hover_file_link_invalid))
            return
        }
        val target = resolveProjectFileLink(project.root, link)
        if (target == null || !target.file.isFile) {
            toast(getString(R.string.hover_file_link_invalid))
            return
        }
        openFile(target.file, target)
    }

    private fun Throwable.rootMessage(): String {
        var current: Throwable = this
        while (current.cause != null && current.cause !== current) current = checkNotNull(current.cause)
        return current.message ?: current.javaClass.simpleName
    }


    private fun insertSymbol(symbol: String) {
        if (!editor.isShown) return
        val start = editor.cursor.left
        val end = editor.cursor.right
        editor.text.replace(start, end, symbol)
        val position = editor.text.indexer.getCharPosition(start + symbol.length)
        editor.setSelection(position.line, position.column)
        editor.requestFocus()
        editor.showSoftInput()
    }

    private fun insertSymbolPair(opening: String, closing: String) {
        if (!editor.isShown) return
        val start = editor.cursor.left
        val end = editor.cursor.right
        val selectedText = if (start == end) "" else editor.text.substring(start, end)
        val replacement = buildString(opening.length + selectedText.length + closing.length) {
            append(opening)
            append(selectedText)
            append(closing)
        }
        editor.text.replace(start, end, replacement)
        val cursorIndex = if (start == end) start + opening.length else start + replacement.length
        val position = editor.text.indexer.getCharPosition(cursorIndex)
        editor.setSelection(position.line, position.column)
        editor.requestFocus()
        editor.showSoftInput()
    }

    private fun moveCursor(movement: SelectionMovement) {
        if (!editor.isShown) return
        editor.moveSelection(movement)
        editor.requestFocus()
        editor.showSoftInput()
    }
    private fun newDocument() {
        captureEditorState()
        val index = editorSession.add(EditorTab(null, "", null))
        selectTab(index)
    }

    private fun newOmp() {
        newTerminal(startupCommand = "omp", isOmp = true)
    }

    private fun newTerminal(
        startupCommand: String? = null,
        ompSessionId: String? = null,
        isOmp: Boolean = false,
    ) {
        if (!ProotRuntime.isEnvironmentReady(this)) {
            startActivity(Intent(this, SetupActivity::class.java))
            return
        }
        val projectRoot = currentProject?.root?.takeIf { settings.ompUseProjectDirectory }
        createTerminalTab(
            startupCommand = startupCommand,
            ompSessionId = ompSessionId,
            isOmp = isOmp,
            projectRoot = projectRoot,
            selectAfterCreate = true,
        )
    }

    private fun createTerminalTab(
        startupCommand: String?,
        ompSessionId: String?,
        isOmp: Boolean,
        projectRoot: File?,
        title: String? = null,
        selectAfterCreate: Boolean,
    ): Int {
        val launch = ProotRuntime.terminalLaunch(this, projectRoot)
        val session = TerminalSession(
            launch.executable,
            launch.workingDirectory,
            launch.arguments,
            launch.environment,
            settings.terminalTranscriptRows,
            terminalSessionClient,
        )
        if (title == null) terminalCounter += 1
        val index = editorSession.add(
            TerminalTab(
                session = session,
                title = title ?: getString(R.string.terminal_tab_title, terminalCounter),
                workingDirectory = projectRoot?.let { root ->
                    currentProject?.let { project -> StorageUtils.relativePath(project.root, root) }
                },
                ompSessionId = ompSessionId,
                isOmp = isOmp,
                pendingStartupCommand = startupCommand,
            ),
        )
        if (selectAfterCreate) selectTab(index)
        return index
    }

    private fun observeOmpSession(session: TerminalSession) {
        val tab = editorSession.tabs.filterIsInstance<TerminalTab>()
            .firstOrNull { it.session === session }
            ?: return
        if (!tab.isOmp || tab.ompSessionId != null) return
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - tab.lastOmpSessionDiscoveryAtMillis
        if (tab.ompSessionDiscoveryInFlight || elapsed < OMP_SESSION_DISCOVERY_INTERVAL_MS) {
            if (!tab.ompSessionDiscoveryScheduled) {
                tab.ompSessionDiscoveryScheduled = true
                terminalView.postDelayed({
                    tab.ompSessionDiscoveryScheduled = false
                    observeOmpSession(session)
                }, (OMP_SESSION_DISCOVERY_INTERVAL_MS - elapsed).coerceAtLeast(1L))
            }
            return
        }
        tab.lastOmpSessionDiscoveryAtMillis = now
        tab.ompSessionDiscoveryInFlight = true
        val workingDirectory = terminalWorkingDirectory(tab)
        val usedSessionIds = editorSession.tabs
            .filterIsInstance<TerminalTab>()
            .mapNotNull { it.ompSessionId }
            .toSet()
        lifecycleScope.launch {
            try {
                val sessionId = withContext(Dispatchers.IO) {
                    ProotRuntime.findOmpSessionId(
                        this@EditorActivity,
                        workingDirectory,
                        tab.ompStartedAtMillis,
                        usedSessionIds,
                        tab.shellPid,
                    )
                }
                val currentTab = editorSession.tabs.filterIsInstance<TerminalTab>()
                    .firstOrNull { it.session === session }
                val claimedByAnotherTab = sessionId != null && editorSession.tabs
                    .filterIsInstance<TerminalTab>()
                    .any { it !== tab && it.ompSessionId == sessionId }
                if (currentTab === tab && sessionId != null && !claimedByAnotherTab) {
                    tab.ompSessionId = sessionId
                    persistWorkspace()
                    refreshTabs()
                }
            } finally {
                tab.ompSessionDiscoveryInFlight = false
                if (tab.ompSessionDiscoveryScheduled) {
                    terminalView.post { observeOmpSession(session) }
                }
            }
        }
    }

    private fun terminalWorkingDirectory(tab: TerminalTab): File? {
        val project = currentProject ?: return null
        val relativeDirectory = tab.workingDirectory ?: return null
        return runCatching { StorageUtils.resolveChild(project.root, relativeDirectory) }
            .getOrNull()
            ?.takeIf { it.isDirectory }
    }

    private fun isSafeOmpSessionId(value: String): Boolean {
        return ProotRuntime.ompSessionIdFromFileName("session_$value.jsonl") == value
    }


    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private fun onToolbarItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_play -> {
                playCurrentProject()
                true
            }
            R.id.action_new_document -> {
                newDocument()
                true
            }
            R.id.action_new_terminal -> {
                newTerminal()
                true
            }
            R.id.action_new_omp -> {
                newOmp()
                true
            }
            R.id.action_save -> {
                saveActiveDocument()
                true
            }
            R.id.action_save_as -> {
                saveActiveDocumentAs()
                true
            }
            R.id.action_find_replace -> {
                if (editor.isShown) {
                    editor.beginSearchMode()
                } else {
                    toast(getString(R.string.no_active_document))
                }
                true
            }
            R.id.action_word_wrap -> {
                if (editor.isShown) {
                    editor.isWordwrap = !editor.isWordwrap
                    settings.editorWordWrap = editor.isWordwrap
                    item.isChecked = editor.isWordwrap
                } else {
                    toast(getString(R.string.no_active_document))
                }
                true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_projects -> {
                openProjectManagerIfNeeded(force = true)
                true
            }
            else -> false
        }
    }

    private fun openProjectManagerIfNeeded(force: Boolean = false) {
        if (force || !openedManagerForEmptyState) {
            openedManagerForEmptyState = true
            projectManagerLauncher.launch(Intent(this, ProjectManagerActivity::class.java))
        }
    }

    private fun switchProject(project: Project) {
        if (!saveAllBlocking(requireNamed = true)) return
        persistWorkspace()
        workspaceRestoreJob?.cancel()
        stopDirectoryObserver()
        finishTerminalTabs()
        currentProject = projectRepository.markOpened(project)
        editorSession.clear()
        selectedPaths.clear()
        selectionAnchorPath = null
        currentDirectory = currentProject?.root
        visibleItems = emptyList()
        selectionMode = false
        updateSelectionActionBar()
        browserAdapter.submitItems(emptyList(), emptySet())
        toolbar.title = currentProject?.displayName.orEmpty()
        drawerProjectTitle.text = currentProject?.displayName
        updateDirectoryHeader()
        refreshTabs()
        showEmptyEditor()
        refreshFileList()
        restoreWorkspace(project)

    }
    private fun refreshFileList() {
        directoryRefreshGeneration += 1
        val refreshGeneration = directoryRefreshGeneration
        val project = currentProject ?: run {
            stopDirectoryObserver()
            currentDirectory = null
            visibleItems = emptyList()
            drawerProjectTitle.text = getString(R.string.no_project)
            drawerProjectPath.text = ""
            drawerDirectoryMenu.isEnabled = false
            browserAdapter.submitItems(emptyList(), emptySet())
            return
        }
        val directory = currentDirectory
            ?.takeIf { it.isDirectory && StorageUtils.isWithin(project.root, it) }
            ?: project.root
        currentDirectory = directory
        updateDirectoryHeader()
        watchCurrentDirectory()
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { listDirectory(project.root, directory) }
            if (currentProject?.id == project.id &&
                currentDirectory?.absolutePath == directory.absolutePath &&
                refreshGeneration == directoryRefreshGeneration
            ) {
                visibleItems = items
                val selectablePaths = items.asSequence()
                    .filterNot { it.parentNavigation }
                    .mapTo(hashSetOf()) { it.relativePath }
                selectedPaths.retainAll(selectablePaths)
                if (selectionAnchorPath !in selectablePaths) {
                    selectionAnchorPath = selectedPaths.lastOrNull()
                }
                if (selectedPaths.isEmpty()) {
                    selectionMode = false
                    selectionAnchorPath = null
                }
                updateSelectionUi()
            }
        }
    }

    private fun watchCurrentDirectory() {
        val project = currentProject
        val directory = currentDirectory
        if (project == null || directory == null || !directory.isDirectory ||
            !StorageUtils.isWithin(project.root, directory)
        ) {
            stopDirectoryObserver()
            return
        }
        directoryObserver?.stopWatching()
        directoryObserver = object : FileObserver(directory.path, DIRECTORY_WATCH_MASK) {
            override fun onEvent(event: Int, path: String?) {
                if (path == StorageUtils.METADATA_FILE ||
                    path == StorageUtils.WORKSPACE_FILE ||
                    path?.startsWith("${StorageUtils.METADATA_FILE}.") == true ||
                    path?.startsWith("${StorageUtils.WORKSPACE_FILE}.") == true
                ) return
                directoryRefreshHandler.removeCallbacks(directoryRefreshRunnable)
                directoryRefreshHandler.postDelayed(directoryRefreshRunnable, DIRECTORY_REFRESH_DEBOUNCE_MS)
            }
        }.also { it.startWatching() }
    }

    private fun stopDirectoryObserver() {
        directoryObserver?.stopWatching()
        directoryObserver = null
        directoryRefreshHandler.removeCallbacks(directoryRefreshRunnable)
    }

    private fun showEmptyEditor() {
        editor.clearFocus()
        terminalView.clearFocus()
        editor.visibility = View.GONE
        terminalView.visibility = View.GONE
        symbolScroll.visibility = View.GONE
        terminalKeyBar.visibility = View.GONE
        welcomePage.visibility = View.VISIBLE
        scheduleLsp(null)
    }

    private fun finishTerminalTabs() {
        editorSession.tabs.filterIsInstance<TerminalTab>()
            .forEach { it.session.finishIfRunning() }
    }


    private fun listDirectory(root: File, directory: File): List<BrowserItem> = buildList {
        if (StorageUtils.relativePath(root, directory).isNotBlank()) {
            directory.parentFile
                ?.takeIf { it.isDirectory && StorageUtils.isWithin(root, it) }
                ?.let { parent ->
                    add(
                        BrowserItem(
                            file = parent,
                            relativePath = StorageUtils.relativePath(root, parent),
                            directory = true,
                            childCount = 0,
                            parentNavigation = true,
                        ),
                    )
                }
        }
        directory.listFiles()
            ?.asSequence()
            ?.filter { it.name != StorageUtils.METADATA_FILE && it.name != StorageUtils.WORKSPACE_FILE }
            ?.filter { StorageUtils.isWithin(root, it) }
            ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
            ?.forEach { child ->
                val childCount = if (child.isDirectory) {
                    child.listFiles()?.count {
                        it.name != StorageUtils.METADATA_FILE && it.name != StorageUtils.WORKSPACE_FILE && StorageUtils.isWithin(root, it)
                    } ?: 0
                } else {
                    0
                }
                add(
                    BrowserItem(
                        file = child,
                        relativePath = StorageUtils.relativePath(root, child),
                        directory = child.isDirectory,
                        childCount = childCount,
                    ),
                )
            }
    }

    private fun updateDirectoryHeader() {
        val project = currentProject
        val directory = currentDirectory
        if (project == null || directory == null) {
            drawerProjectTitle.text = getString(R.string.no_project)
            drawerProjectPath.text = ""
            drawerDirectoryMenu.isEnabled = false
            return
        }
        val relativePath = StorageUtils.relativePath(project.root, directory)
        drawerProjectTitle.text = project.displayName
        drawerProjectPath.text = if (relativePath.isBlank()) "/" else "/$relativePath"
        drawerDirectoryMenu.isEnabled = true
    }

    private fun showCurrentDirectoryMenu(anchor: View) {
        val directory = currentDirectory ?: return
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, MENU_NEW_FILE, 0, R.string.new_file)
        menu.menu.add(0, MENU_NEW_FOLDER, 1, R.string.new_folder)
        menu.menu.add(0, MENU_PASTE, 2, R.string.paste).isEnabled = clipboardFiles.isNotEmpty()
        menu.menu.add(0, MENU_REFRESH, 3, R.string.refresh)
        menu.setOnMenuItemClickListener { selected ->
            when (selected.itemId) {
                MENU_NEW_FILE -> createChild(directory, false)
                MENU_NEW_FOLDER -> createChild(directory, true)
                MENU_PASTE -> pasteInto(directory)
                MENU_REFRESH -> refreshFileList()
            }
            true
        }
        menu.show()
    }

    private fun navigateToParentDirectory(): Boolean {
        val project = currentProject ?: return false
        val directory = currentDirectory ?: return false
        if (StorageUtils.relativePath(project.root, directory).isBlank()) return false
        val parent = directory.parentFile
            ?.takeIf { it.isDirectory && StorageUtils.isWithin(project.root, it) }
            ?: project.root
        navigateToDirectory(parent)
        return true
    }

    private fun navigateToDirectory(directory: File) {
        val project = currentProject ?: return
        if (!directory.isDirectory || !StorageUtils.isWithin(project.root, directory)) return
        selectedPaths.clear()
        selectionAnchorPath = null
        selectionMode = false
        visibleItems = emptyList()
        currentDirectory = directory
        updateSelectionUi()
        refreshFileList()
    }

    private fun onBrowserItemClicked(item: BrowserItem) {
        if (item.parentNavigation) {
            navigateToDirectory(item.file)
        } else if (selectionMode) {
            toggleSelection(item)
        } else {
            openBrowserItem(item)
        }
    }

    private fun openBrowserItem(item: BrowserItem) {
        if (item.directory) {
            navigateToDirectory(item.file)
        } else {
            openFile(item.file)
            drawer.closeDrawer(GravityCompat.START)
        }
    }

    private fun toggleSelection(item: BrowserItem) {
        selectionMode = true
        if (selectedPaths.remove(item.relativePath)) {
            if (selectionAnchorPath == item.relativePath) {
                selectionAnchorPath = selectedPaths.lastOrNull()
            }
        } else {
            selectedPaths += item.relativePath
            selectionAnchorPath = item.relativePath
        }
        if (selectedPaths.isEmpty()) {
            selectionMode = false
            selectionAnchorPath = null
        }
        updateSelectionUi()
    }

    private fun selectRangeFromSwipe(item: BrowserItem) {
        if (!selectionMode) {
            selectedPaths += item.relativePath
            selectionAnchorPath = item.relativePath
            selectionMode = true
            updateSelectionUi()
            return
        }

        val orderedPaths = selectableBrowserPaths()
        val anchorPath = selectionAnchorPath
            ?.takeIf { it in orderedPaths }
            ?: selectedPaths.lastOrNull { it in orderedPaths }
            ?: item.relativePath
        val range = inclusiveSelectionRange(orderedPaths, anchorPath, item.relativePath) ?: return
        selectedPaths.clear()
        selectedPaths.addAll(range)
        selectionAnchorPath = anchorPath
        updateSelectionUi()
    }

    private fun selectableBrowserPaths(): List<String> = visibleItems.asSequence()
        .filterNot { it.parentNavigation }
        .map { it.relativePath }
        .toList()

    private fun showBrowserItemMenu(item: BrowserItem, anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, MENU_OPEN, 0, R.string.open)
        menu.menu.add(0, MENU_RENAME, 1, R.string.rename)
        menu.menu.add(0, MENU_COPY, 2, R.string.copy)
        menu.menu.add(0, MENU_CUT, 3, R.string.cut)
        menu.menu.add(0, MENU_DELETE, 4, R.string.delete)
        menu.menu.add(0, MENU_DETAILS, 5, R.string.details)
        if (selectedPaths.size == 2) menu.menu.add(0, MENU_RANGE, 6, R.string.select_range)
        if (selectionMode) menu.menu.add(0, MENU_CLEAR_SELECTION, 7, R.string.clear_selection)
        menu.setOnMenuItemClickListener { selected ->
            when (selected.itemId) {
                MENU_OPEN -> openBrowserItem(item)
                MENU_RENAME -> rename(item.file)
                MENU_COPY -> copySelection(item.file, false)
                MENU_CUT -> copySelection(item.file, true)
                MENU_DELETE -> deleteSelection(item.file)
                MENU_DETAILS -> showDetails(item.file)
                MENU_RANGE -> selectRange()
                MENU_CLEAR_SELECTION -> clearSelection()
            }
            true
        }
        menu.setOnDismissListener { updateSelectionUi() }
        menu.show()
    }

    private fun clearSelection() {
        selectedPaths.clear()
        selectionAnchorPath = null
        selectionMode = false
        updateSelectionUi()
    }

    private fun updateSelectionTitle() {
        toolbar.title = if (selectionMode) {
            getString(R.string.selected_count, selectedPaths.size)
        } else {
            currentProject?.displayName.orEmpty()
        }
    }

    private fun updateSelectionUi() {
        updateSelectionTitle()
        browserAdapter.submitItems(visibleItems, selectedPaths)
        updateSelectionActionBar()
    }

    private fun selectAllVisibleItems() {
        val orderedPaths = selectableBrowserPaths()
        selectedPaths.clear()
        selectedPaths.addAll(orderedPaths)
        selectionAnchorPath = orderedPaths.firstOrNull()
        selectionMode = selectedPaths.isNotEmpty()
        updateSelectionUi()
    }

    private fun invertVisibleSelection() {
        val inverted = invertedSelection(selectableBrowserPaths(), selectedPaths)
        selectedPaths.clear()
        selectedPaths.addAll(inverted)
        selectionAnchorPath = selectedPaths.firstOrNull()
        selectionMode = selectedPaths.isNotEmpty()
        updateSelectionUi()
    }

    private fun updateSelectionActionBar() {
        selectionActionBar.animate().cancel()
        if (selectionMode && selectedPaths.isNotEmpty()) {
            if (selectionActionBar.visibility != View.VISIBLE) {
                selectionActionBar.alpha = 0f
                selectionActionBar.translationY = dp(20).toFloat()
                selectionActionBar.scaleX = 0.94f
                selectionActionBar.scaleY = 0.94f
                selectionActionBar.visibility = View.VISIBLE
            }
            selectionActionBar.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(SELECTION_BAR_SHOW_DURATION_MS)
                .setInterpolator(PathInterpolator(0f, 0f, 0.2f, 1f))
                .start()
        } else if (selectionActionBar.visibility == View.VISIBLE) {
            selectionActionBar.animate()
                .alpha(0f)
                .translationY(dp(12).toFloat())
                .scaleX(0.96f)
                .scaleY(0.96f)
                .setDuration(SELECTION_BAR_HIDE_DURATION_MS)
                .setInterpolator(PathInterpolator(0.4f, 0f, 1f, 1f))
                .withEndAction {
                    if (!selectionMode) {
                        selectionActionBar.visibility = View.GONE
                        selectionActionBar.translationY = 0f
                    }
                }
                .start()
        }
    }

    private fun selectRange() {
        if (selectedPaths.size != 2) return
        val paths = selectedPaths.toList()
        val range = inclusiveSelectionRange(selectableBrowserPaths(), paths[0], paths[1]) ?: return
        selectedPaths.clear()
        selectedPaths.addAll(range)
        selectionAnchorPath = paths[0]
        updateSelectionUi()
    }

    private fun createChild(parent: File, directory: Boolean) {
        val project = currentProject ?: return
        val targetDirectory = if (parent.isDirectory) parent else parent.parentFile
        if (targetDirectory == null || !StorageUtils.isWithin(project.root, targetDirectory)) return
        promptForText(if (directory) getString(R.string.new_folder) else getString(R.string.new_file), "名称") { name ->
            if (!isSafeName(name)) {
                toast("名称包含非法字符")
                return@promptForText
            }
            val target = File(targetDirectory, name)
            if (!StorageUtils.isWithin(project.root, target) || target.exists()) {
                toast("目标已存在或路径无效")
                return@promptForText
            }
            try {
                if (directory) require(target.mkdirs()) else {
                    target.parentFile?.mkdirs()
                    require(target.createNewFile())
                }
                refreshFileList()
                if (!directory) openFile(target)
            } catch (error: IOException) {
                toast(error.message ?: "创建失败")
            }
        }
    }

    private fun rename(file: File) {
        val project = currentProject ?: return
        promptForText(getString(R.string.rename), "名称", file.name) { name ->
            if (!isSafeName(name)) {
                toast("名称包含非法字符")
                return@promptForText
            }
            val target = File(file.parentFile, name)
            if (!StorageUtils.isWithin(project.root, target) || target.exists()) {
                toast("目标已存在或路径无效")
                return@promptForText
            }
            if (!file.renameTo(target)) {
                toast("重命名失败")
                return@promptForText
            }
            editorSession.find(file)?.let { tab ->
                val index = editorSession.tabs.indexOf(tab)
                editorSession.remove(index)
            }
            refreshFileList()
            refreshTabs()
        }
    }

    private fun copySelection(file: File, cut: Boolean) {
        val project = currentProject ?: return
        val files = if (selectedPaths.isEmpty()) listOf(file) else selectedPaths.mapNotNull {
            runCatching { StorageUtils.resolveChild(project.root, it) }.getOrNull()
        }
        clipboardFiles = files.filter { StorageUtils.isWithin(project.root, it) }
        clipboardIsCut = cut
        toast(if (cut) "已剪切 ${clipboardFiles.size} 项" else "已复制 ${clipboardFiles.size} 项")
    }

    private fun pasteInto(targetDirectory: File?) {
        val project = currentProject ?: return
        val destination = targetDirectory
            ?.takeIf { it.isDirectory && StorageUtils.isWithin(project.root, it) }
            ?: return
        val sources = clipboardFiles.toList()
        val cut = clipboardIsCut
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    sources.forEach { source ->
                        if (!source.exists() || !StorageUtils.isWithin(project.root, source)) return@forEach
                        require(!source.isDirectory || !StorageUtils.isWithin(source, destination)) {
                            "不能粘贴到文件夹自身或其子目录"
                        }
                        val target = File(destination, source.name)
                        require(!target.exists()) { "目标已存在：${source.name}" }
                        require(StorageUtils.isWithin(project.root, target))
                        StorageUtils.copyRecursively(source, target)
                        if (cut) StorageUtils.deleteRecursively(source)
                    }
                }
                clipboardFiles = emptyList()
                refreshFileList()
                toast("粘贴完成")
            } catch (error: Exception) {
                toast(error.message ?: "粘贴失败")
            }
        }
    }

    private fun deleteSelection(file: File) {
        val project = currentProject ?: return
        val files = if (selectedPaths.isEmpty()) listOf(file) else selectedPaths.mapNotNull {
            runCatching { StorageUtils.resolveChild(project.root, it) }.getOrNull()
        }.filter { StorageUtils.isWithin(project.root, it) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirm_delete)
            .setMessage(getString(R.string.delete_warning) + "\n" + files.joinToString { it.name })
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) { files.forEach(StorageUtils::deleteRecursively) }
                    }.onSuccess {
                        clearSelection()
                        refreshFileList()
                    }.onFailure { toast(it.message ?: "删除失败") }
                }
            }
            .show()
    }

    private fun showDetails(file: File) {
        val project = currentProject ?: return
        val relative = StorageUtils.relativePath(project.root, file)
        val type = if (file.isDirectory) "文件夹" else LanguageResolver.displayName(file)
        val size = if (file.isDirectory) "目录" else StorageUtils.formatBytes(file.length())
        MaterialAlertDialogBuilder(this)
            .setTitle(file.name)
            .setMessage("类型：$type\n大小：$size\n路径：$relative")
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun isSafeName(name: String): Boolean {
        val value = name.trim()
        return value.isNotEmpty() && value != "." && value != ".." && !value.contains('/') && !value.contains('\\') && !value.any { it.isISOControl() }
    }

    private fun promptForText(title: String, hint: String, initial: String = "", onConfirm: (String) -> Unit) {
        val input = EditText(this).apply {
            this.hint = hint
            setSingleLine(true)
            setText(initial)
            setSelection(length())
            setPadding(48, 0, 48, 0)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> onConfirm(input.text.toString().trim()) }
            .show()
    }

    private fun openFile(file: File, target: EditorNavigationTarget? = null) {
        val project = currentProject ?: return
        if (!file.isFile || !StorageUtils.isWithin(project.root, file)) return
        if (file.length() > MAX_EDITOR_BYTES) {
            toast("文件过大，暂不载入编辑器")
            return
        }
        editorSession.find(file)?.let { tab ->
            val index = editorSession.tabs.indexOf(tab)
            if (editorSession.activeIndex != index) selectTab(index)
            target?.let(::moveToNavigationTarget)
            return
        }
        lifecycleScope.launch {
            try {
                val text = withContext(Dispatchers.IO) { file.readText(Charsets.UTF_8) }
                captureEditorState()
                val tab = EditorTab(file, text, LanguageResolver.scopeFor(file))
                val index = editorSession.add(tab)
                selectTab(index)
                target?.let(::moveToNavigationTarget)
            } catch (error: Exception) {
                toast(error.message ?: "无法打开文件")
            }
        }
    }

    private fun moveToNavigationTarget(target: EditorNavigationTarget) {
        val activeFile = editorSession.activeEditorTab?.file ?: return
        if (runCatching { activeFile.canonicalFile != target.file.canonicalFile }.getOrDefault(true)) return
        val lastLine = (editor.lineCount - 1).coerceAtLeast(0)
        val startLine = target.startLine.coerceIn(0, lastLine)
        val startColumn = target.startColumn.coerceIn(0, editor.text.getColumnCount(startLine))
        val endLine = target.endLine.coerceIn(startLine, lastLine)
        val rawEndColumn = target.endColumn.coerceIn(0, editor.text.getColumnCount(endLine))
        val endColumn = if (endLine == startLine) rawEndColumn.coerceAtLeast(startColumn) else rawEndColumn
        editor.setSelectionRegion(startLine, startColumn, endLine, endColumn, false)
        editor.ensurePositionVisible(startLine, startColumn, true)
    }

    private fun selectTab(index: Int) {
        if (editorSession.activeIndex != index) captureEditorState()
        editorSession.select(index)
        when (val tab = editorSession.activeTab) {
            is EditorTab -> showEditorTab(tab)
            is TerminalTab -> showTerminalTab(tab)
            null -> showEmptyEditor()
        }
        refreshTabs()
    }

    private fun showEditorTab(tab: EditorTab) {
        suppressEditorEvents = true
        val language = createEditorLanguage(tab.languageScope)
        editor.setEditorLanguage(language)
        editor.setText(tab.text)
        editor.postInvalidate()
        val left = tab.selectionStart.coerceIn(0, editor.text.length)
        val right = tab.selectionEnd.coerceIn(left, editor.text.length)
        val leftPosition = editor.text.indexer.getCharPosition(left)
        val rightPosition = editor.text.indexer.getCharPosition(right)
        editor.setSelectionRegion(
            leftPosition.line,
            leftPosition.column,
            rightPosition.line,
            rightPosition.column,
            false,
        )
        editor.scroller.startScroll(tab.scrollX, tab.scrollY, 0, 0, 0)
        editor.scroller.abortAnimation()
        suppressEditorEvents = false
        terminalView.visibility = View.GONE
        terminalKeyBar.visibility = View.GONE
        editor.visibility = View.VISIBLE
        symbolScroll.visibility = View.VISIBLE
        welcomePage.visibility = View.GONE
        scheduleLsp(tab, language)
        updateSymbolNavigationButtons()
    }

    private fun showTerminalTab(tab: TerminalTab) {
        editor.clearFocus()
        editor.visibility = View.GONE
        symbolScroll.visibility = View.GONE
        welcomePage.visibility = View.GONE
        terminalView.visibility = View.VISIBLE
        terminalKeyBar.visibility = View.VISIBLE
        ctrlPressed = false
        altPressed = false
        updateTerminalModifierButtons()
        terminalView.attachSession(tab.session)
        terminalView.onScreenUpdated()
        terminalView.requestFocus()
        scheduleLsp(null)
        updateSymbolNavigationButtons()
    }

    private fun captureEditorState() {
        val tab = editorSession.activeEditorTab ?: return
        if (suppressEditorEvents || !editor.isShown) return
        tab.text = editor.text.toString()
        tab.selectionStart = editor.cursor.left
        tab.selectionEnd = editor.cursor.right
        tab.scrollX = editor.offsetX
        tab.scrollY = editor.offsetY
    }

    private fun scheduleLsp(tab: EditorTab?, language: Language? = null) {
        val controller = lspController ?: return
        lspJob?.cancel()
        lspJob = lifecycleScope.launch {
            val file = tab?.file
            val project = currentProject
            if (file != null && project != null && language != null) {
                controller.attach(project.root, file, language)
                updateSymbolNavigationButtons()
            } else {
                controller.detach()
                updateSymbolNavigationButtons()
            }
        }
    }

    private data class RestoredWorkspaceTab(
        val originalIndex: Int,
        val state: WorkspaceTabSnapshot,
        val file: File?,
        val text: String?,
    )

    private fun persistWorkspace() {
        val project = currentProject ?: return
        if (workspaceRestoreJob?.isActive == true && editorSession.tabs.isEmpty()) return
        captureEditorState()
        val directory = currentDirectory
            ?.takeIf { it.isDirectory && StorageUtils.isWithin(project.root, it) }
            ?.let { runCatching { StorageUtils.relativePath(project.root, it) }.getOrNull() }
            ?: ""
        val tabs = editorSession.tabs.mapNotNull { tab ->
            when (tab) {
                is EditorTab -> {
                    val path = tab.file?.let { file ->
                        runCatching { StorageUtils.relativePath(project.root, file) }.getOrNull()
                    }
                    if (tab.file != null && path == null) return@mapNotNull null
                    WorkspaceTabSnapshot(
                        type = WorkspaceTabType.EDITOR,
                        path = path,
                        text = tab.text.takeIf { tab.file == null || tab.dirty },
                        dirty = tab.dirty,
                        selectionStart = tab.selectionStart,
                        selectionEnd = tab.selectionEnd,
                        scrollX = tab.scrollX,
                        scrollY = tab.scrollY,
                    )
                }
                is TerminalTab -> WorkspaceTabSnapshot(
                    type = WorkspaceTabType.TERMINAL,
                    title = tab.title,
                    workingDirectory = tab.workingDirectory,
                    ompSessionId = tab.ompSessionId,
                    isOmp = tab.isOmp || tab.ompSessionId != null,
                )
            }
        }
        val snapshot = WorkspaceSnapshot(
            directory = directory,
            activeTab = editorSession.activeIndex,
            terminalCounter = terminalCounter,
            tabs = tabs,
        )
        runCatching { WorkspaceStore.write(project.root, snapshot) }
            .onFailure { error -> android.util.Log.w(TAG, "Unable to save workspace", error) }
    }

    private fun restoreWorkspace(project: Project) {
        workspaceRestoreJob?.cancel()
        workspaceRestoreJob = lifecycleScope.launch {
            val snapshot = withContext(Dispatchers.IO) { WorkspaceStore.read(project.root) } ?: return@launch
            val loaded = withContext(Dispatchers.IO) {
                snapshot.tabs.mapIndexedNotNull { index, state ->
                    if (state.type == WorkspaceTabType.TERMINAL) {
                        RestoredWorkspaceTab(index, state, null, null)
                    } else {
                        val file = state.path
                            ?.takeIf { it.isNotBlank() }
                            ?.let { path -> runCatching { StorageUtils.resolveChild(project.root, path) }.getOrNull() }
                        if (state.path?.isNotBlank() == true &&
                            (file == null || !file.isFile || file.length() > MAX_EDITOR_BYTES)
                        ) {
                            null
                        } else {
                            val text = state.text ?: file?.readText(Charsets.UTF_8) ?: ""
                            RestoredWorkspaceTab(index, state, file, text)
                        }
                    }
                }
            }
            if (currentProject?.id != project.id || editorSession.tabs.isNotEmpty()) return@launch
            currentDirectory = snapshot.directory
                .takeIf { it.isNotBlank() }
                ?.let { path -> runCatching { StorageUtils.resolveChild(project.root, path) }.getOrNull() }
                ?.takeIf { it.isDirectory && StorageUtils.isWithin(project.root, it) }
                ?: project.root
            refreshFileList()
            terminalCounter = snapshot.terminalCounter
            val restoredIndices = mutableMapOf<Int, Int>()
            loaded.forEach { restored ->
                val newIndex = when (restored.state.type) {
                    WorkspaceTabType.EDITOR -> {
                        val state = restored.state
                        editorSession.add(EditorTab(restored.file, restored.text.orEmpty(), restored.file?.let(LanguageResolver::scopeFor)).apply {
                            dirty = state.dirty
                            selectionStart = state.selectionStart
                            selectionEnd = state.selectionEnd
                            scrollX = state.scrollX
                            scrollY = state.scrollY
                        })
                    }
                    WorkspaceTabType.TERMINAL -> {
                        if (!ProotRuntime.isEnvironmentReady(this@EditorActivity)) return@forEach
                        val relativeDirectory = restored.state.workingDirectory
                        val terminalDirectory = when (relativeDirectory) {
                            null -> null
                            else -> runCatching {
                                StorageUtils.resolveChild(project.root, relativeDirectory)
                            }.getOrNull()?.takeIf { it.isDirectory }
                                ?: project.root
                        }
                        val sessionId = restored.state.ompSessionId
                            ?.takeIf(::isSafeOmpSessionId)
                        val isOmp = restored.state.isOmp || sessionId != null
                        val startup = if (isOmp) sessionId?.let { "omp -r $it" } ?: "omp" else null
                        runCatching {
                            createTerminalTab(
                                startupCommand = startup,
                                ompSessionId = sessionId,
                                isOmp = isOmp,
                                projectRoot = terminalDirectory,
                                title = restored.state.title,
                                selectAfterCreate = false,
                            )
                        }.getOrNull() ?: return@forEach
                    }
                }
                restoredIndices[restored.originalIndex] = newIndex
            }
            val active = restoredIndices[snapshot.activeTab] ?: restoredIndices.values.firstOrNull()
            if (active != null) selectTab(active) else showEmptyEditor()
            refreshTabs()
        }
    }

    private fun refreshTabs() {
        tabContainer.removeAllViews()
        val tabRipple = obtainStyledAttributes(
            intArrayOf(android.R.attr.selectableItemBackground),
        ).let { attributes ->
            val drawable = attributes.getDrawable(0)
            attributes.recycle()
            drawable
        }
        val iconRipple = obtainStyledAttributes(
            intArrayOf(android.R.attr.selectableItemBackgroundBorderless),
        ).let { attributes ->
            val drawable = attributes.getDrawable(0)
            attributes.recycle()
            drawable
        }
        val activeIndicatorColor = MaterialColors.getColor(
            tabContainer,
            androidx.appcompat.R.attr.colorPrimary,
        )
        editorSession.tabs.forEachIndexed { index, tab ->
            val tabRoot = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = tabRipple?.constantState?.newDrawable()?.mutate()
                setOnClickListener { selectTab(index) }
            }
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            val label = TextView(this).apply {
                val name = when (tab) {
                    is EditorTab -> tab.file?.name ?: getString(R.string.unnamed_file)
                    is TerminalTab -> tab.title
                }
                val shortenedName = if (name.length > MAX_TAB_NAME_CHARS) {
                    name.take(MAX_TAB_NAME_CHARS) + "..."
                } else {
                    name
                }
                val dirty = (tab as? EditorTab)?.dirty == true
                text = if (dirty) getString(R.string.dirty_tab_label, shortenedName) else shortenedName
                setTextColor(if (index == editorSession.activeIndex) Color.WHITE else Color.LTGRAY)
                typeface = mapleTypeface
                textSize = 13f
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(dp(16), 0, dp(8), 0)
            }
            val close = ImageButton(this).apply {
                setImageResource(R.drawable.ic_close)
                background = iconRipple?.constantState?.newDrawable()?.mutate()
                adjustViewBounds = true
                scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(3), dp(3), dp(3), dp(3))
                minimumWidth = 0
                minimumHeight = 0
                contentDescription = getString(
                    if (tab is TerminalTab) R.string.close_terminal else R.string.close_file,
                )
                setOnClickListener { closeTab(index) }
            }
            content.addView(label, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
            content.addView(close, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
            tabRoot.addView(content, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                0,
                1f,
            ))
            tabRoot.addView(View(this).apply {
                setBackgroundColor(
                    if (index == editorSession.activeIndex) activeIndicatorColor else Color.GRAY,
                )
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)))
            tabContainer.addView(tabRoot, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
        }
        val add = ImageButton(this).apply {
            setImageResource(R.drawable.ic_add)
            background = iconRipple?.constantState?.newDrawable()?.mutate()
            adjustViewBounds = true
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(3), dp(3), dp(3), dp(3))
            minimumWidth = 0
            minimumHeight = 0
            contentDescription = getString(R.string.new_file)
            setOnClickListener { newDocument() }
        }
        tabContainer.addView(add, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
    }

    private fun closeTab(index: Int) {
        when (val tab = editorSession.tabs.getOrNull(index) ?: return) {
            is TerminalTab -> {
                tab.session.finishIfRunning()
                removeTab(index)
            }
            is EditorTab -> {
                if (tab.dirty) {
                    MaterialAlertDialogBuilder(this)
                        .setTitle(
                            getString(
                                R.string.unsaved_file_title,
                                tab.file?.name ?: getString(R.string.unnamed_file),
                            ),
                        )
                        .setNegativeButton(R.string.cancel, null)
                        .setNeutralButton(R.string.discard_changes) { _, _ -> removeTab(index) }
                        .setPositiveButton(R.string.save_changes) { _, _ ->
                            if (tab.file == null) {
                                saveTabAs(tab) { removeTab(index) }
                            } else if (saveTabBlocking(tab)) {
                                removeTab(index)
                            }
                        }
                        .show()
                } else {
                    removeTab(index)
                }
            }
        }
    }

    private fun removeTab(index: Int) {
        captureEditorState()
        editorSession.remove(index)
        if (editorSession.activeIndex >= 0) selectTab(editorSession.activeIndex) else showEmptyEditor()
        refreshTabs()
    }

    private fun saveActiveDocument() {
        captureEditorState()
        val tab = editorSession.activeEditorTab ?: run {
            toast(getString(R.string.no_active_document))
            return
        }
        if (tab.file == null) {
            saveTabAs(tab)
        } else if (saveTabBlocking(tab)) {
            refreshTabs()
        }
    }

    private fun saveActiveDocumentAs() {
        captureEditorState()
        editorSession.activeEditorTab?.let(::saveTabAs)
            ?: toast(getString(R.string.no_active_document))
    }

    private fun saveTabAs(tab: EditorTab, onSaved: () -> Unit = {}) {
        val project = currentProject ?: run {
            toast(getString(R.string.no_project))
            return
        }
        promptForText(getString(R.string.save_as), "文件名", tab.file?.name ?: "untitled.lua") { name ->
            if (!isSafeName(name)) {
                toast("名称包含非法字符")
                return@promptForText
            }
            val target = File(project.root, name)
            val sameFile = tab.file?.let { runCatching { it.canonicalFile == target.canonicalFile }.getOrDefault(false) } == true
            if (!StorageUtils.isWithin(project.root, target) || (target.exists() && !sameFile)) {
                toast("目标已存在或路径无效")
                return@promptForText
            }
            runCatching {
                StorageUtils.writeTextAtomic(target, tab.text)
                tab.file = target
                tab.languageScope = LanguageResolver.scopeFor(target)
                tab.dirty = false
                if (editorSession.activeEditorTab === tab) applyEditorLanguage(tab)
            }.onSuccess {
                refreshTabs()
                refreshFileList()
                onSaved()
            }.onFailure { toast(it.message ?: "保存失败") }
        }
    }

    private fun saveTabBlocking(tab: EditorTab): Boolean {
        val file = tab.file ?: return false
        val saved = runCatching {
            StorageUtils.writeTextAtomic(file, tab.text)
            tab.dirty = false
        }.onFailure { toast(it.message ?: "保存失败") }.isSuccess
        if (saved) {
            lspController?.let { controller ->
                lifecycleScope.launch { controller.notifySaved(file) }
            }
        }
        return saved
    }

    private fun saveAllBlocking(requireNamed: Boolean = false): Boolean {
        captureEditorState()
        val editorTabs = editorSession.tabs.filterIsInstance<EditorTab>()
        if (requireNamed && editorTabs.any { it.dirty && it.file == null }) {
            toast("存在未命名未保存文件，请先另存为")
            return false
        }
        val saved = editorTabs.filter { it.file != null }.all(::saveTabBlocking)
        if (saved) refreshTabs()
        return saved
    }

    private fun playCurrentProject() {
        val project = currentProject ?: run {
            openProjectManagerIfNeeded(force = true)
            return
        }
        if (!saveAllBlocking(requireNamed = true)) return
        lifecycleScope.launch {
            try {
                val packageFile = withContext(Dispatchers.IO) {
                    ProjectValidator.validate(project)?.let { throw IOException(it) }
                    LovePackageBuilder.build(project, cacheDir)
                }
                val uri = FileProvider.getUriForFile(this@EditorActivity, "$packageName.fileprovider", packageFile)
                startActivity(
                    Intent(this@EditorActivity, top.wsdx233.love2droid.runtime.LoveGameActivity::class.java)
                        .setData(uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                )
            } catch (error: Exception) {
                toast(error.message ?: "无法启动项目")
            }
        }
    }

    private fun applyEditorLanguage(tab: EditorTab) {
        val language = createEditorLanguage(tab.languageScope)
        editor.setEditorLanguage(language)
        scheduleLsp(tab, language)
    }

    private fun createEditorLanguage(scope: String?): Language {
        return if (scope == null || !textMateReady) {
            EmptyLanguage()
        } else {
            runCatching { TextMateLanguage.create(scope, true) }
                .onFailure { toast(getString(R.string.syntax_highlighting_failed, scope)) }
                .getOrElse { EmptyLanguage() }
        }
    }

    private fun setupTextMate() {
        runCatching {
            val providers = FileProviderRegistry.getInstance()
            providers.addFileProvider(AssetsFileResolver(applicationContext.assets))
            val themes = ThemeRegistry.getInstance()
            val themePath = "textmate/darcula.json"
            val themeStream = requireNotNull(providers.tryGetInputStream(themePath)) { "缺少 $themePath" }
            themes.loadTheme(
                ThemeModel(
                    IThemeSource.fromInputStream(themeStream, themePath, null),
                    "darcula",
                ).apply { isDark = true },
            )
            GrammarRegistry.getInstance().loadGrammars("textmate/languages.json")
            requireNotNull(GrammarRegistry.getInstance().findGrammar("source.lua")) { "Lua grammar 未注册" }
            themes.setTheme("darcula")
            editor.colorScheme = TextMateColorScheme.create(themes)
            textMateReady = true
        }.onFailure { error ->
            textMateReady = false
            toast(getString(R.string.syntax_highlighting_failed, error.message ?: error.javaClass.simpleName))
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onPause() {
        saveAllBlocking()
        persistWorkspace()
        super.onPause()
    }

    override fun onDestroy() {
        persistWorkspace()
        directoryRefreshHandler.removeCallbacks(directoryRefreshRunnable)
        directoryObserver?.stopWatching()
        directoryObserver = null
        workspaceRestoreJob?.cancel()
        lspJob?.cancel()
        symbolNavigationJob?.cancel()
        lspController?.close()
        finishTerminalTabs()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (::editor.isInitialized) {
            editor.setTextSize(settings.editorFontSize)
            editor.isLineNumberEnabled = settings.editorLineNumbers
            editor.isWordwrap = settings.editorWordWrap
            terminalView.setTextSize(terminalDefaultTextSizePx.toInt())
            terminalView.keepScreenOn = settings.terminalKeepScreenOn
            refreshFileList()
        }
    }

    private enum class TerminalModifier { CTRL, ALT }

    private data class TerminalKey(
        val label: String,
        val sequence: String = "",
        val modifier: TerminalModifier? = null,
    )

    companion object {
        private const val TAG = "EditorActivity"
        private const val MAX_EDITOR_BYTES = 5L * 1024 * 1024
        private const val DIRECTORY_REFRESH_DEBOUNCE_MS = 250L
        private const val SELECTION_ACTION_BAR_HEIGHT_DP = 56
        private const val SELECTION_BAR_SHOW_DURATION_MS = 160L
        private const val SELECTION_BAR_HIDE_DURATION_MS = 100L
        private const val DIRECTORY_WATCH_MASK =
            FileObserver.CREATE or FileObserver.DELETE or FileObserver.MOVED_FROM or
                FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE or FileObserver.MODIFY or
                FileObserver.ATTRIB or FileObserver.DELETE_SELF or FileObserver.MOVE_SELF
        private const val MENU_OPEN = 1
        private const val MENU_NEW_FILE = 2
        private const val MENU_NEW_FOLDER = 3
        private const val MENU_PASTE = 4
        private const val MENU_RENAME = 5
        private const val MENU_COPY = 6
        private const val MENU_CUT = 7
        private const val MENU_DELETE = 8
        private const val MENU_DETAILS = 9
        private const val MENU_RANGE = 10
        private const val MENU_CLEAR_SELECTION = 11
        private const val MENU_REFRESH = 12
        private const val MAX_TAB_NAME_CHARS = 15
        private const val OMP_SESSION_DISCOVERY_INTERVAL_MS = 500L
        private const val TERMINAL_KEY_COLOR = 0xFF424242.toInt()
        private const val TERMINAL_MODIFIER_COLOR = 0xFF5C6BC0.toInt()
    }
}
