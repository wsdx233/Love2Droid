package top.wsdx233.love2droid

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Menu
import android.view.MenuItem
import android.view.animation.PathInterpolator
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import android.graphics.Rect
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.menu.MenuBuilder
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
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.ColorSchemeUpdateEvent
import io.github.rosemoe.sora.event.ClickEvent
import io.github.rosemoe.sora.event.EditorMotionEvent
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
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
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
import org.json.JSONObject
import java.io.IOException

class EditorActivity : AppCompatActivity() {
    private lateinit var drawer: androidx.drawerlayout.widget.DrawerLayout
    private lateinit var toolbar: MaterialToolbar
    private lateinit var tabContainer: LinearLayout
    private lateinit var tabScroll: View
    private lateinit var symbolBar: LinearLayout
    private lateinit var symbolScroll: View
    private lateinit var editorContainer: View
    private lateinit var editor: BreakpointCodeEditor
    private lateinit var editorSearchController: EditorSearchController
    private lateinit var welcomePage: View
    private lateinit var drawerProjectTitle: TextView
    private lateinit var drawerProjectPath: TextView
    private lateinit var drawerProjectSearch: View
    private lateinit var drawerVersionControl: View
    private lateinit var drawerProjectProperties: View
    private lateinit var drawerDirectoryMenu: View
    private lateinit var selectionActionBar: LinearLayout
    private lateinit var browserAdapter: FileBrowserAdapter
    private lateinit var terminalView: TerminalView
    private lateinit var terminalKeyBar: LinearLayout
    private lateinit var dshWebView: WebView
    private lateinit var dshLoadingIndicator: LinearProgressIndicator
    private val dshWebLoadState = DshWebLoadState()
    private var lspController: LuaLspController? = null
    private var dshWebUrlObserver: (() -> Unit)? = null
    private var loadedDshWebUrl: String? = null

    private val projectRepository by lazy { ProjectRepository(this) }
    private val settings by lazy { SettingsStore(this) }
    private val editorSession = EditorSession()
    private val selectedPaths = linkedSetOf<String>()
    private var currentProject: Project? = null
    private val currentBreakpoints = linkedSetOf<ProjectBreakpoint>()
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
    private var appliedEditorThemeId: String? = null
    private val symbolBarButtons = mutableListOf<TextView>()
    private val tabLabels = mutableMapOf<WorkspaceTab, TextView>()
    private var terminalScreenUpdateScheduled = false
    private var lspJob: Job? = null
    private var workspaceRestoreJob: Job? = null
    private var symbolNavigationJob: Job? = null
    private var breakpointSaveJob: Job? = null
    private val openingFiles = mutableMapOf<String, MutableList<(String?) -> Unit>>()
    private var selectedSymbolForNavigation: SelectedSymbol? = null
    private var directoryObserver: FileObserver? = null
    private var directoryRefreshGeneration = 0L
    private val directoryRefreshHandler = Handler(Looper.getMainLooper())
    private var symbolDefinitionButton: ImageButton? = null
    private var symbolUsagesButton: ImageButton? = null
    private var pendingProjectIconSelection: ((Uri?) -> Unit)? = null
    private var pendingSigningKeySelection: ((Uri?) -> Unit)? = null
    private data class PendingSafTransfer(
        val projectId: String,
        val projectRoot: File,
        val sourceOrTarget: File,
    )
    private var pendingDirectoryImport: PendingSafTransfer? = null
    private var pendingFileImport: PendingSafTransfer? = null
    private var pendingDirectoryExport: PendingSafTransfer? = null
    private var pendingFileExport: PendingSafTransfer? = null
    private val directoryRefreshRunnable = Runnable {
        refreshFileList()
        refreshOpenEditorFiles()
    }
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
            DshDaemon.captureAuthenticatedUrl(changedSession)
            if (::terminalView.isInitialized && terminalView.mTermSession === changedSession && !terminalScreenUpdateScheduled) {
                terminalScreenUpdateScheduled = true
                terminalView.postOnAnimation {
                    terminalScreenUpdateScheduled = false
                    if (::terminalView.isInitialized && terminalView.mTermSession === changedSession) {
                        terminalView.onScreenUpdated()
                    }
                }
            }
        }

        override fun onTitleChanged(changedSession: TerminalSession) {
            val tab = editorSession.tabs.filterIsInstance<TerminalTab>()
                .firstOrNull { it.session === changedSession }
            val title = changedSession.title?.trim().orEmpty()
            if (tab != null && title.isNotEmpty() && tab.title != title) {
                tab.title = title
                updateTabLabel(tab)
            }
        }

        override fun onSessionFinished(finishedSession: TerminalSession) {
            DshDaemon.onSessionFinished(finishedSession)
            editorSession.tabs.filterIsInstance<TerminalTab>()
                .firstOrNull { it.session === finishedSession }
                ?.let { tab ->
                    tab.title = getString(R.string.terminal_finished, tab.title)
                    updateTabLabel(tab)
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
            tab.pendingStartupCommand?.let { command ->
                tab.pendingStartupCommand = null
                session.write("$command\n")
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

    private val projectIconLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        val callback = pendingProjectIconSelection
        pendingProjectIconSelection = null
        callback?.invoke(uri)
    }

    private val signingKeyLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val callback = pendingSigningKeySelection
        pendingSigningKeySelection = null
        callback?.invoke(uri)
    }
    private val importDirectoryLauncher = registerForActivityResult(
        DocumentsUiOpenDocumentTreeContract(),
    ) { uri ->
        val pending = pendingDirectoryImport
        pendingDirectoryImport = null
        if (uri != null && pending != null) importDirectory(uri, pending)
    }
    private val importFileLauncher = registerForActivityResult(
        DocumentsUiOpenDocumentContract(),
    ) { uri ->
        val pending = pendingFileImport
        pendingFileImport = null
        if (uri != null && pending != null) importFile(uri, pending)
    }


    private val exportDirectoryLauncher = registerForActivityResult(
        DocumentsUiOpenDocumentTreeContract(),
    ) { uri ->
        val pending = pendingDirectoryExport
        pendingDirectoryExport = null
        if (uri != null && pending != null) exportDirectory(uri, pending)
    }

    private val exportFileLauncher = registerForActivityResult(
        DocumentsUiCreateDocumentContract(),
    ) { uri ->
        val pending = pendingFileExport
        pendingFileExport = null
        if (uri != null && pending != null) exportFile(uri, pending)
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
        tabScroll = findViewById(R.id.tab_scroll)
        symbolBar = findViewById(R.id.symbol_bar)
        symbolScroll = findViewById(R.id.symbol_scroll)
        editorTopInset = findViewById(R.id.editor_top_inset)
        drawerTopInset = findViewById(R.id.drawer_top_inset)
        editorContainer = findViewById(R.id.editor_container)
        editor = findViewById(R.id.code_editor)
        terminalView = findViewById(R.id.terminal_view)
        terminalKeyBar = findViewById(R.id.terminal_key_bar)
        dshWebView = findViewById(R.id.dsh_web_view)
        dshLoadingIndicator = findViewById(R.id.dsh_loading_indicator)
        setupDshWebView()
        dshWebUrlObserver = DshDaemon.observeWebUrl { url ->
            runOnUiThread { applyDshWebUrl(url) }
        }
        welcomePage = findViewById(R.id.welcome_page)
        drawerProjectTitle = findViewById(R.id.drawer_project_title)
        drawerProjectPath = findViewById(R.id.drawer_project_path)
        drawerProjectSearch = findViewById(R.id.drawer_project_search)
        drawerVersionControl = findViewById(R.id.drawer_version_control)
        drawerProjectProperties = findViewById(R.id.drawer_project_properties)
        drawerDirectoryMenu = findViewById(R.id.drawer_directory_menu)
        selectionActionBar = findViewById(R.id.selection_action_bar)
        editorSearchController = EditorSearchController(
            context = this,
            editor = editor,
            root = findViewById(android.R.id.content),
            onError = ::toast,
        )
        findViewById<View>(R.id.action_select_all).setOnClickListener { selectAllVisibleItems() }
        findViewById<View>(R.id.action_invert_selection).setOnClickListener { invertVisibleSelection() }
        findViewById<View>(R.id.action_clear_selection).setOnClickListener { clearSelection() }
        drawerDirectoryMenu.setOnClickListener(::showCurrentDirectoryMenu)
        drawerProjectSearch.setOnClickListener { showProjectSearch() }
        drawerVersionControl.setOnClickListener { showVersionControl() }
        drawerProjectProperties.setOnClickListener { showProjectProperties() }
        findViewById<View>(R.id.welcome_open_file).setOnClickListener {
            drawer.openDrawer(GravityCompat.START)
        }
        findViewById<View>(R.id.welcome_new_file).setOnClickListener { newDocument() }

        setupWindowInsets()
        toolbar.navigationIcon = ContextCompat.getDrawable(this, R.drawable.ic_menu)
        toolbar.navigationContentDescription = getString(R.string.file_browser)
        toolbar.setNavigationOnClickListener { drawer.openDrawer(GravityCompat.START) }
        toolbar.inflateMenu(R.menu.editor_menu)
        enableMenuIcons(toolbar.menu)
        updateEditorMenuState()
        toolbar.setOnMenuItemClickListener(::onToolbarItemSelected)


        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    editorSearchController.isVisible -> editorSearchController.close()
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
        setupTextMate()
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
                hoverInfoEnabled = settings.editorHoverInfo,
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

        editor.subscribeAlways<ContentChangeEvent> { event ->
            if (!suppressEditorEvents) {
                editorSession.activeEditorTab?.let { tab ->
                    tab.text = editor.text.toString()
                    tab.dirty = true
                    adjustBreakpointsForEdit(tab, event)
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
    @SuppressLint("RestrictedApi")
    private fun enableMenuIcons(menu: Menu = toolbar.menu) {
        if (menu is MenuBuilder) {
            menu.setOptionalIconsVisible(true)
        }
        for (index in 0 until menu.size()) {
            val item = menu.getItem(index)
            item.subMenu?.let(::enableMenuIcons)
        }
    }

    private fun updateEditorMenuState() {
        val activeTab = editorSession.activeEditorTab
        toolbar.menu.findItem(R.id.action_undo).isEnabled = activeTab != null && !activeTab.readOnly
        toolbar.menu.findItem(R.id.action_word_wrap).isChecked = editor.isWordwrap
        toolbar.menu.findItem(R.id.action_symbol_bar).isChecked = settings.editorSymbolBar
        toolbar.menu.findItem(R.id.action_read_only).apply {
            isEnabled = activeTab != null
            isChecked = activeTab?.readOnly == true
        }
        toolbar.menu.findItem(R.id.action_lsp_hover).isChecked = settings.editorHoverInfo
    }

    private fun updateSymbolBarVisibility() {
        symbolScroll.visibility = if (editorSession.activeEditorTab != null && settings.editorSymbolBar) {
            View.VISIBLE
        } else {
            View.GONE
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
        symbolBarButtons.clear()
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
                setTextColor(editor.colorScheme.getColor(EditorColorScheme.TEXT_NORMAL))
                background = selectableBackground?.constantState?.newDrawable()
                contentDescription = label
                setOnClickListener { action() }
            }
            symbolBarButtons += button
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
        editor.subscribeAlways<ColorSchemeUpdateEvent> {
            applyEditorSurfaceColors()
            updateEditorBreakpointHighlights()
        }
        editor.subscribeAlways<ClickEvent> { event ->
            lspController?.dismissHover()
            if (event.motionRegion == EditorMotionEvent.REGION_LINE_NUMBER && toggleBreakpoint(event.line)) {
                event.intercept()
            }
        }
        updateSymbolNavigationButtons()
        applyEditorSurfaceColors()
    }

    private fun toggleBreakpoint(zeroBasedLine: Int): Boolean {
        val project = currentProject ?: return false
        val tab = editorSession.activeEditorTab ?: return false
        val file = tab.file ?: return false
        if (tab.languageScope != "source.lua" || !StorageUtils.isWithin(project.root, file)) return false
        val relativePath = StorageUtils.relativePath(project.root, file)
        val breakpoint = ProjectBreakpoint(relativePath, zeroBasedLine + 1)
        if (!currentBreakpoints.remove(breakpoint)) {
            currentBreakpoints.add(breakpoint)
        }
        updateEditorBreakpointHighlights()
        persistBreakpoints()
        return true
    }

    private fun adjustBreakpointsForEdit(tab: EditorTab, event: ContentChangeEvent) {
        val project = currentProject ?: return
        val file = tab.file ?: return
        if (tab.languageScope != "source.lua" || !StorageUtils.isWithin(project.root, file)) return
        val kind = when (event.action) {
            ContentChangeEvent.ACTION_INSERT -> BreakpointEditKind.INSERT
            ContentChangeEvent.ACTION_DELETE -> BreakpointEditKind.DELETE
            else -> return
        }
        val adjusted = currentBreakpoints.adjustedForEdit(
            file = StorageUtils.relativePath(project.root, file),
            kind = kind,
            startLine = event.changeStart.line,
            endLine = event.changeEnd.line,
        )
        if (adjusted.toSet() == currentBreakpoints) return
        currentBreakpoints.clear()
        currentBreakpoints.addAll(adjusted)
        updateEditorBreakpointHighlights()
        persistBreakpoints()
    }

    private fun updateEditorBreakpointHighlights() {
        val project = currentProject
        val tab = editorSession.activeEditorTab
        val file = tab?.file
        val lines = if (project != null && file != null && tab.languageScope == "source.lua" &&
            StorageUtils.isWithin(project.root, file)
        ) {
            val relativePath = StorageUtils.relativePath(project.root, file)
            currentBreakpoints.asSequence()
                .filter { it.file == relativePath }
                .map { it.line - 1 }
                .toList()
        } else {
            emptyList()
        }
        editor.setBreakpointLines(lines)
    }

    private fun persistBreakpoints() {
        val project = currentProject ?: return
        val snapshot = currentBreakpoints.toList()
        val previous = breakpointSaveJob
        breakpointSaveJob = lifecycleScope.launch {
            previous?.join()
            val updated = withContext(Dispatchers.IO) {
                val latest = projectRepository.findProject(project.id) ?: return@withContext null
                projectRepository.updateBreakpoints(latest, snapshot)
            }
            if (updated != null && currentProject?.id == project.id) {
                currentProject = currentProject?.copy(breakpoints = updated.breakpoints)
            }
        }
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

        fun addAction(icon: Int, description: Int, action: (SelectedSymbol) -> Unit): ImageButton {
            return ImageButton(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(45), dp(45))
                setImageResource(icon)
                background = selectableBackground?.constantState?.newDrawable()
                contentDescription = getString(description)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                isClickable = true
                isFocusable = false
                visibility = View.GONE
                setOnClickListener {
                    android.util.Log.d(TAG, "Symbol action clicked: ${getString(description)}")
                    val selected = selectedSymbolForNavigation ?: currentSelectedSymbol() ?: return@setOnClickListener
                    val projectId = currentProject?.id ?: return@setOnClickListener
                    actionWindow.dismiss()
                    if (currentProject?.id == projectId) {
                        action(selected)
                    }
                }
            }.also(buttonRow::addView)
        }

        symbolDefinitionButton = addAction(
            R.drawable.ic_symbol_definition,
            R.string.go_to_definition,
        ) { selected ->
            findDefinition(selected.file, selected.line, selected.column)
        }
        symbolUsagesButton = addAction(
            R.drawable.ic_symbol_references,
            R.string.find_usages,
        ) { selected ->
            findUsages(selected.file, selected.line, selected.column)
        }
        val customButtons = listOfNotNull(symbolDefinitionButton, symbolUsagesButton)
        val resizeActionWindow = Runnable {
            if (actionWindow.isShowing()) {
                val contentWidth = buttonRow.measuredWidth
                val maxWidth = (resources.displayMetrics.widthPixels - dp(32)).coerceAtLeast(actionWindow.getWidth())
                val width = contentWidth.coerceAtMost(maxWidth)
                if (width > actionWindow.getWidth()) {
                    actionWindow.setSize(width, actionWindow.getHeight())
                }
            }
        }
        buttonRow.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            editor.removeCallbacks(resizeActionWindow)
            editor.post(resizeActionWindow)
        }
        editor.subscribeAlways<SelectionChangeEvent> {
            editor.removeCallbacks(resizeActionWindow)
            editor.postDelayed(resizeActionWindow, 80L)
        }
        actionWindow.getPopup().setTouchInterceptor { _, event ->
            if (event.actionMasked != MotionEvent.ACTION_UP) {
                false
            } else {
                val target = customButtons.firstOrNull { button ->
                    if (button.visibility != View.VISIBLE) {
                        false
                    } else {
                        val rect = Rect()
                        button.getGlobalVisibleRect(rect) &&
                            event.rawX >= rect.left && event.rawX < rect.right &&
                            event.rawY >= rect.top && event.rawY < rect.bottom
                    }
                }
                target?.performClick() == true
            }
        }
        buttonRow.requestLayout()
        horizontalScroll.requestLayout()
    }

    private fun updateSymbolNavigationButtonColors() {
        val tint = android.content.res.ColorStateList.valueOf(
            editor.colorScheme.getColor(EditorColorScheme.TEXT_ACTION_WINDOW_ICON_COLOR),
        )
        symbolDefinitionButton?.imageTintList = tint
        symbolUsagesButton?.imageTintList = tint
    }

    private fun applyEditorSurfaceColors() {
        if (!::editor.isInitialized) return
        val colors = editor.colorScheme
        val background = colors.getColor(EditorColorScheme.WHOLE_BACKGROUND)
        editorContainer.setBackgroundColor(background)
        editor.setBackgroundColor(background)
        symbolScroll.setBackgroundColor(background)
        symbolBarButtons.forEach { it.setTextColor(colors.getColor(EditorColorScheme.TEXT_NORMAL)) }
        updateSymbolNavigationButtonColors()
        refreshTabs()
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
        selectedSymbolForNavigation = selected?.takeIf {
            lspController?.isNavigationAvailable(it.file) == true
        }
        val visibility = if (selectedSymbolForNavigation != null) View.VISIBLE else View.GONE
        symbolDefinitionButton?.visibility = visibility
        symbolUsagesButton?.visibility = visibility
    }

    private fun findDefinition(file: File, line: Int, column: Int) {
        android.util.Log.d(TAG, "Starting definition request")
        val controller = lspController ?: return
        val project = currentProject ?: return
        symbolNavigationJob?.cancel()
        symbolNavigationJob = lifecycleScope.launch {
            try {
                val locations = controller.findDefinitions(file, line, column)
                android.util.Log.d(TAG, "Definition locations: ${locations.size}")
                if (currentProject?.id != project.id) return@launch
                if (locations.isEmpty()) {
                    toast(getString(R.string.symbol_definition_not_found))
                    return@launch
                }
                val targets = withContext(Dispatchers.IO) {
                    locations.mapNotNull { resolveProjectNavigationTarget(project.root, it) }
                }
                android.util.Log.d(TAG, "Definition targets: ${targets.size}")
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
                when {
                    targets.isEmpty() -> toast(getString(R.string.symbol_location_outside_project))
                    targets.size == 1 -> openFile(targets.single().file, targets.single())
                    else -> showNavigationResults(R.string.usage_results, project, targets)
                }
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
        if (!editor.isShown || !editor.isEditable) return
        val start = editor.cursor.left
        val end = editor.cursor.right
        editor.text.replace(start, end, symbol)
        val position = editor.text.indexer.getCharPosition(start + symbol.length)
        editor.setSelection(position.line, position.column)
        editor.requestFocus()
        editor.showSoftInput()
    }

    private fun insertSymbolPair(opening: String, closing: String) {
        if (!editor.isShown || !editor.isEditable) return
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
        newTerminal(startupCommand = ProotRuntime.ompStartupCommand(resume = false), isOmp = true)
    }

    private fun newDsh() {
        if (!ProotRuntime.isDshReady(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.components_dsh_name)
                .setMessage(R.string.dsh_not_installed_message)
                .setPositiveButton(R.string.install) { _, _ ->
                    SetupActivity.start(this, targetComponent = InstallRegistry.ID_DSH)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }

        if (!settings.dshBackgroundEnabled && !DshDaemon.isRunning()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dsh_service_not_enabled_title)
                .setMessage(R.string.dsh_service_not_enabled_message)
                .setPositiveButton(R.string.dsh_service_enable_and_start) { _, _ ->
                    settings.dshBackgroundEnabled = true
                    if (DshDaemon.ensureStarted(this)) openDshTab()
                    else toast(getString(R.string.dsh_start_failed))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }

        if (!DshDaemon.ensureStarted(this)) {
            toast(getString(R.string.dsh_start_failed))
            return
        }
        openDshTab()
    }

    private fun openDshTab() {
        val existingIndex = editorSession.tabs.indexOfFirst { it is DshWebTab }
        if (existingIndex >= 0) {
            selectTab(existingIndex)
        } else {
            val tab = DshWebTab(title = getString(R.string.dsh_tab_title))
            val index = editorSession.add(tab)
            selectTab(index)
        }
    }

    private fun newTerminal(
        startupCommand: String? = null,
        isOmp: Boolean = false,
    ) {
        if (isOmp) {
            if (!ProotRuntime.isOmpReady(this)) {
                SetupActivity.start(this, targetComponent = InstallRegistry.ID_OMP)
                return
            }
        } else {
            if (!ProotRuntime.isRootfsReady(this)) {
                SetupActivity.start(this, targetComponent = InstallRegistry.ID_ROOTFS)
                return
            }
        }
        val projectRoot = currentProject?.root?.takeIf { settings.ompUseProjectDirectory }
        createTerminalTab(
            startupCommand = startupCommand,
            isOmp = isOmp,
            projectRoot = projectRoot,
            selectAfterCreate = true,
        )
    }

    private fun createTerminalTab(
        startupCommand: String?,
        isOmp: Boolean,
        projectRoot: File?,
        title: String? = null,
        selectAfterCreate: Boolean,
    ): Int? {
        val launch = try {
            ProotRuntime.terminalLaunch(this, projectRoot)
        } catch (error: Exception) {
            android.util.Log.e("EditorActivity", "Failed to prepare terminal", error)
            toast(getString(R.string.terminal_start_failed, error.localizedMessage.orEmpty()))
            return null
        }
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
                isOmp = isOmp,
                pendingStartupCommand = startupCommand,
            ),
        )
        if (selectAfterCreate) selectTab(index)
        return index
    }


    private fun terminalWorkingDirectory(tab: TerminalTab): File? {
        val project = currentProject ?: return null
        val relativeDirectory = tab.workingDirectory ?: return null
        return runCatching { StorageUtils.resolveChild(project.root, relativeDirectory) }
            .getOrNull()
            ?.takeIf { it.isDirectory }
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
            R.id.action_new_dsh -> {
                newDsh()
                true
            }
            R.id.action_save -> {
                saveActiveDocument()
                true
            }
            R.id.action_undo -> {
                val tab = editorSession.activeEditorTab
                if (tab == null) {
                    toast(getString(R.string.no_active_document))
                } else if (!tab.readOnly) {
                    editor.undo()
                }
                true
            }
            R.id.action_save_as -> {
                saveActiveDocumentAs()
                true
            }
            R.id.action_find_replace -> {
                if (editor.isShown) {
                    editorSearchController.show()
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
            R.id.action_symbol_bar -> {
                settings.editorSymbolBar = !settings.editorSymbolBar
                updateSymbolBarVisibility()
                updateEditorMenuState()
                true
            }
            R.id.action_read_only -> {
                val tab = editorSession.activeEditorTab
                if (tab == null) {
                    toast(getString(R.string.no_active_document))
                } else {
                    tab.readOnly = !tab.readOnly
                    editor.editable = !tab.readOnly
                    updateEditorMenuState()
                }
                true
            }
            R.id.action_lsp_hover -> {
                settings.editorHoverInfo = !settings.editorHoverInfo
                lspController?.setHoverInfoEnabled(settings.editorHoverInfo)
                updateEditorMenuState()
                true
            }
            R.id.action_package_android -> {
                showAndroidPackaging()
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

    private fun showProjectSearch() {
        val project = currentProject ?: return
        drawer.closeDrawer(GravityCompat.START)
        ProjectSearchSheet(
            activity = this,
            scope = lifecycleScope,
            project = project,
            lspController = lspController,
            onOpen = { target -> openFile(target.file, target) },
        ).show()
    }

    private fun showVersionControl() {
        val project = currentProject ?: return
        drawer.closeDrawer(GravityCompat.START)
        GitBottomSheet(this, lifecycleScope, project).show()
    }

    private fun showProjectProperties() {
        val project = currentProject ?: return
        drawer.closeDrawer(GravityCompat.START)
        ProjectPropertiesSheet(
            activity = this,
            scope = lifecycleScope,
            repository = projectRepository,
            project = project,
            chooseIcon = { callback ->
                pendingProjectIconSelection = callback
                projectIconLauncher.launch("image/*")
            },
            onSaved = { updated ->
                if (currentProject?.id == updated.id) {
                    currentProject = updated
                    toolbar.title = updated.displayName
                    drawerProjectTitle.text = updated.displayName
                }
            },
        ).show()
    }

    private fun showAndroidPackaging() {
        val project = currentProject ?: run {
            openProjectManagerIfNeeded(force = true)
            return
        }
        saveAllThen(requireNamed = true) {
            if (currentProject?.id != project.id) return@saveAllThen
            AndroidPackagingSheet(
                activity = this,
                scope = lifecycleScope,
                repository = projectRepository,
                project = project,
                chooseSigningKey = { callback ->
                    pendingSigningKeySelection = callback
                    signingKeyLauncher.launch(
                        arrayOf("application/x-pkcs12", "application/pkcs12", "application/octet-stream"),
                    )
                },
            ).show()
        }
    }

    private fun openProjectManagerIfNeeded(force: Boolean = false) {
        if (force || !openedManagerForEmptyState) {
            openedManagerForEmptyState = true
            projectManagerLauncher.launch(Intent(this, ProjectManagerActivity::class.java))
        }
    }

    private fun switchProject(project: Project) {
        saveAllThen(requireNamed = true) {
            switchProjectNow(project)
        }
    }

    private fun switchProjectNow(project: Project) {
        persistWorkspace()
        workspaceRestoreJob?.cancel()
        stopDirectoryObserver()
        finishTerminalTabs()
        currentProject = projectRepository.markOpened(project)
        currentBreakpoints.clear()
        currentBreakpoints.addAll(currentProject?.breakpoints.orEmpty())
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
        drawerProjectSearch.isEnabled = true
        drawerVersionControl.isEnabled = true
        drawerProjectProperties.isEnabled = true
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
            drawerProjectSearch.isEnabled = false
            drawerVersionControl.isEnabled = false
            drawerProjectProperties.isEnabled = false
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
        if (::dshWebView.isInitialized) {
            dshWebView.visibility = View.GONE
            updateDshLoadingIndicator()
        }
        updateSymbolBarVisibility()
        terminalKeyBar.visibility = View.GONE
        editorSearchController.setEditorAvailable(false)
        welcomePage.visibility = View.VISIBLE
        scheduleLsp(null)
        if (::editor.isInitialized) editor.setBreakpointLines(emptyList())
        updateEditorMenuState()
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
        menu.menu.add(0, MENU_IMPORT, 2, R.string.import_action)
        menu.menu.add(0, MENU_EXPORT, 3, R.string.export_action)
        menu.menu.add(0, MENU_PASTE, 4, R.string.paste).isEnabled = clipboardFiles.isNotEmpty()
        menu.menu.add(0, MENU_REFRESH, 5, R.string.refresh)
        menu.setOnMenuItemClickListener { selected ->
            when (selected.itemId) {
                MENU_NEW_FILE -> createChild(directory, false)
                MENU_NEW_FOLDER -> createChild(directory, true)
                MENU_IMPORT -> showImportMenu(directory)
                MENU_EXPORT -> startDirectoryExport(directory)
                MENU_PASTE -> pasteInto(directory)
                MENU_REFRESH -> refreshFileList()
            }
            true
        }
        menu.show()
    }

    private fun showImportMenu(targetDirectory: File) {
        val project = currentProject ?: return
        if (!targetDirectory.isDirectory || !StorageUtils.isWithin(project.root, targetDirectory)) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.import_action)
            .setItems(
                arrayOf(getString(R.string.import_file), getString(R.string.import_folder)),
            ) { _, which ->
                when (which) {
                    0 -> startFileImport(targetDirectory)
                    1 -> startDirectoryImport(targetDirectory)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun startFileImport(targetDirectory: File) {
        val project = currentProject ?: return
        if (!targetDirectory.isDirectory || !StorageUtils.isWithin(project.root, targetDirectory)) return
        pendingFileImport = PendingSafTransfer(project.id, project.root, targetDirectory)
        importFileLauncher.launch(arrayOf("*/*"))
    }

    private fun startDirectoryImport(targetDirectory: File) {
        val project = currentProject ?: return
        if (!targetDirectory.isDirectory || !StorageUtils.isWithin(project.root, targetDirectory)) return
        pendingDirectoryImport = PendingSafTransfer(project.id, project.root, targetDirectory)
        importDirectoryLauncher.launch(null)
    }

    private fun startDirectoryExport(sourceDirectory: File) {
        val project = currentProject ?: return
        if (!sourceDirectory.isDirectory || !StorageUtils.isWithin(project.root, sourceDirectory)) return
        pendingDirectoryExport = PendingSafTransfer(project.id, project.root, sourceDirectory)
        exportDirectoryLauncher.launch(null)
    }

    private fun startFileExport(sourceFile: File) {
        val project = currentProject ?: return
        if (!sourceFile.isFile || !StorageUtils.isWithin(project.root, sourceFile)) return
        pendingFileExport = PendingSafTransfer(project.id, project.root, sourceFile)
        exportFileLauncher.launch(sourceFile.name)
    }

    private fun isCurrentSafTransfer(pending: PendingSafTransfer): Boolean {
        val project = currentProject ?: return false
        return project.id == pending.projectId && runCatching {
            project.root.canonicalFile == pending.projectRoot.canonicalFile
        }.getOrDefault(false)
    }

    private fun importFile(uri: Uri, pending: PendingSafTransfer) {
        if (!isCurrentSafTransfer(pending)) {
            toast(getString(R.string.saf_project_changed))
            return
        }
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    SafFileTransfer.importFile(this@EditorActivity, uri, pending.sourceOrTarget)
                }
                if (isCurrentSafTransfer(pending)) {
                    refreshFileList()
                    toast(getString(R.string.import_success))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                toast(getString(R.string.import_failed, error.message ?: error.javaClass.simpleName))
            }
        }
    }

    private fun importDirectory(uri: Uri, pending: PendingSafTransfer) {
        if (!isCurrentSafTransfer(pending)) {
            toast(getString(R.string.saf_project_changed))
            return
        }
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    SafFileTransfer.importDirectory(this@EditorActivity, uri, pending.sourceOrTarget)
                }
                if (isCurrentSafTransfer(pending)) {
                    refreshFileList()
                    toast(getString(R.string.import_success))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                toast(getString(R.string.import_failed, error.message ?: error.javaClass.simpleName))
            }
        }
    }

    private fun exportDirectory(uri: Uri, pending: PendingSafTransfer) {
        if (!isCurrentSafTransfer(pending)) {
            toast(getString(R.string.saf_project_changed))
            return
        }
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    SafFileTransfer.exportDirectory(
                        this@EditorActivity,
                        pending.projectRoot,
                        pending.sourceOrTarget,
                        uri,
                    )
                }
                if (isCurrentSafTransfer(pending)) toast(getString(R.string.export_success))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                toast(getString(R.string.export_failed, error.message ?: error.javaClass.simpleName))
            }
        }
    }

    private fun exportFile(uri: Uri, pending: PendingSafTransfer) {
        if (!isCurrentSafTransfer(pending)) {
            toast(getString(R.string.saf_project_changed))
            return
        }
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    SafFileTransfer.exportFile(
                        this@EditorActivity,
                        pending.projectRoot,
                        pending.sourceOrTarget,
                        uri,
                    )
                }
                if (isCurrentSafTransfer(pending)) toast(getString(R.string.export_success))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                toast(getString(R.string.export_failed, error.message ?: error.javaClass.simpleName))
            }
        }
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
        if (item.directory) menu.menu.add(0, MENU_IMPORT, 6, R.string.import_action)
        menu.menu.add(0, MENU_EXPORT, 7, R.string.export_action)
        if (selectedPaths.size == 2) menu.menu.add(0, MENU_RANGE, 8, R.string.select_range)
        if (selectionMode) menu.menu.add(0, MENU_CLEAR_SELECTION, 9, R.string.clear_selection)
        menu.setOnMenuItemClickListener { selected ->
            when (selected.itemId) {
                MENU_OPEN -> openBrowserItem(item)
                MENU_RENAME -> rename(item.file)
                MENU_COPY -> copySelection(item.file, false)
                MENU_CUT -> copySelection(item.file, true)
                MENU_DELETE -> deleteSelection(item.file)
                MENU_DETAILS -> showDetails(item.file)
                MENU_IMPORT -> showImportMenu(item.file)
                MENU_EXPORT -> if (item.directory) startDirectoryExport(item.file) else startFileExport(item.file)
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

    private fun editorFileAccess() = EditorFileAccess(
        ProotRuntime.rootfsDir(this), ProotRuntime.hostTmpDir(this),
        filesDir, cacheDir, getExternalFilesDir(null),
    )

    private fun openFile(
        file: File,
        target: EditorNavigationTarget? = null,
        allowExternal: Boolean = false,
        onOpened: (String?) -> Unit = {},
    ) {
        val project = currentProject ?: return onOpened(getString(R.string.no_project))
        lifecycleScope.launch {
            var key: String? = null
            var errorMessage: String? = null
            try {
                val canonical = withContext(Dispatchers.IO) {
                    if (allowExternal) editorFileAccess().hostFile(file.path) else file.canonicalFile.also {
                        require(StorageUtils.isWithin(project.root, it))
                    }
                }
                if (currentProject?.id != project.id) {
                    onOpened(getString(R.string.dsh_file_open_project_changed))
                    return@launch
                }
                editorSession.find(canonical)?.let { tab ->
                    val index = editorSession.tabs.indexOf(tab)
                    if (editorSession.activeIndex != index) selectTab(index)
                    target?.let { editor.post { moveToNavigationTarget(it) } }
                    onOpened(null)
                    return@launch
                }
                openingFiles[canonical.path]?.let { callbacks ->
                    callbacks.add(onOpened)
                    return@launch
                }
                key = canonical.path
                openingFiles[canonical.path] = mutableListOf(onOpened)
                when (val loaded = withContext(Dispatchers.IO) { EditorFileLoader.load(canonical) }) {
                    is EditorFileLoadResult.Text -> {
                        if (currentProject?.id != project.id) {
                            errorMessage = getString(R.string.dsh_file_open_project_changed)
                            return@launch
                        }
                        editorSession.find(canonical)?.let { existing ->
                            selectTab(editorSession.tabs.indexOf(existing))
                            target?.let { editor.post { moveToNavigationTarget(it) } }
                            return@launch
                        }
                        captureEditorState()
                        val tab = EditorTab(canonical, loaded.content, LanguageResolver.scopeFor(canonical)).apply {
                            lineEnding = loaded.lineEnding
                            diskSnapshot = loaded.snapshot
                        }
                        val index = editorSession.add(tab)
                        selectTab(index)
                        target?.let { editor.post { moveToNavigationTarget(it) } }
                    }
                    is EditorFileLoadResult.TooLarge -> errorMessage = getString(
                        R.string.file_too_large, StorageUtils.formatBytes(loaded.size), EditorFileLoader.MAX_EDITOR_BYTES / (1024 * 1024),
                    )
                    EditorFileLoadResult.Binary -> errorMessage = getString(R.string.binary_file_not_editable)
                    EditorFileLoadResult.InvalidUtf8 -> errorMessage = getString(R.string.invalid_utf8_file)
                    EditorFileLoadResult.ChangedDuringRead -> errorMessage = getString(R.string.file_changed_while_reading)
                    EditorFileLoadResult.Missing -> errorMessage = getString(R.string.file_missing)
                }
            } catch (error: CancellationException) {
                errorMessage = getString(R.string.dsh_file_open_cancelled)
                throw error
            } catch (_: IllegalArgumentException) {
                errorMessage = getString(R.string.dsh_file_access_denied)
            } catch (error: Exception) {
                errorMessage = error.message ?: getString(R.string.open_file_failed)
            } finally {
                val callbacks = key?.let { openingFiles.remove(it) }
                callbacks?.forEach { it(errorMessage) }
                if (errorMessage != null) {
                    if (key == null) onOpened(errorMessage)
                    toast(errorMessage)
                }
            }
        }
    }

    private fun externalSnapshot(tab: EditorTab): EditorFileSnapshot? {
        val file = tab.file ?: return null
        val current = EditorFileLoader.snapshot(file)
        return if (!tab.externalChangeAcknowledged && current != tab.diskSnapshot) current else null
    }

    private fun acknowledgeExternalChange(tab: EditorTab, observed: EditorFileSnapshot?) {
        tab.diskSnapshot = observed
        tab.externalChangeAcknowledged = true
        tab.lastObservedExternalSnapshot = null
    }

    private fun reloadEditorTab(tab: EditorTab, onFinished: () -> Unit) {
        val file = tab.file ?: return onFinished()
        lifecycleScope.launch {
            when (val loaded = withContext(Dispatchers.IO) { EditorFileLoader.load(file) }) {
                is EditorFileLoadResult.Text -> {
                    tab.text = loaded.content
                    tab.lineEnding = loaded.lineEnding
                    tab.diskSnapshot = loaded.snapshot
                    tab.lastObservedExternalSnapshot = null
                    tab.externalChangeAcknowledged = false
                    tab.dirty = false
                    if (editorSession.activeEditorTab === tab) showEditorTab(tab)
                    onFinished()
                }
                is EditorFileLoadResult.TooLarge -> {
                    toast(getString(R.string.file_too_large, StorageUtils.formatBytes(loaded.size), EditorFileLoader.MAX_EDITOR_BYTES / (1024 * 1024)))
                    onFinished()
                }
                EditorFileLoadResult.Binary -> {
                    toast(getString(R.string.binary_file_not_editable))
                    onFinished()
                }
                EditorFileLoadResult.InvalidUtf8 -> {
                    toast(getString(R.string.invalid_utf8_file))
                    onFinished()
                }
                EditorFileLoadResult.ChangedDuringRead -> {
                    toast(getString(R.string.file_changed_while_reading))
                    onFinished()
                }
                EditorFileLoadResult.Missing -> {
                    toast(getString(R.string.file_missing))
                    onFinished()
                }
            }
        }
    }

    private fun handleExternalChange(
        tab: EditorTab,
        observed: EditorFileSnapshot?,
        onContinue: () -> Unit,
        onKeep: (() -> Unit)? = null,
        onReload: (() -> Unit)? = null,
    ) {
        if (!tab.dirty) {
            if (observed == null) {
                toast(getString(R.string.external_file_deleted, tab.file?.name.orEmpty()))
                onContinue()
            } else {
                reloadEditorTab(tab, onContinue)
            }
            return
        }
        showExternalChangeDialog(
            tab = tab,
            observed = observed,
            onKeep = {
                acknowledgeExternalChange(tab, observed)
                (onKeep ?: onContinue)()
            },
            onReload = onReload ?: onContinue,
        )
    }

    private fun showExternalChangeDialog(
        tab: EditorTab,
        observed: EditorFileSnapshot?,
        onKeep: () -> Unit,
        onReload: () -> Unit,
    ) {
        val fileName = tab.file?.name.orEmpty()
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.external_file_changed_title, fileName))
            .setMessage(
                getString(
                    if (observed == null) R.string.external_file_deleted_message else R.string.external_file_changed_message,
                ),
            )
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.keep_editor_content) { _, _ -> onKeep() }
        if (observed != null) {
            builder.setPositiveButton(R.string.reload_file) { _, _ -> reloadEditorTab(tab, onReload) }
        }
        builder.show()
    }

    private fun moveToNavigationTarget(target: EditorNavigationTarget) {
        val activeFile = editorSession.activeEditorTab?.file ?: return
        if (runCatching { activeFile.canonicalFile != target.file.canonicalFile }.getOrDefault(true)) {
            android.util.Log.w(TAG, "Navigation target file is not active")
            return
        }
        val lastLine = (editor.lineCount - 1).coerceAtLeast(0)
        val startLine = target.startLine.coerceIn(0, lastLine)
        val startColumn = target.startColumn.coerceIn(0, editor.text.getColumnCount(startLine))
        val endLine = target.endLine.coerceIn(startLine, lastLine)
        val rawEndColumn = target.endColumn.coerceIn(0, editor.text.getColumnCount(endLine))
        val endColumn = if (endLine == startLine) rawEndColumn.coerceAtLeast(startColumn) else rawEndColumn
        editor.setSelectionRegion(startLine, startColumn, endLine, endColumn, true)
        editor.postOnAnimation { editor.ensurePositionVisible(startLine, startColumn, true) }
        android.util.Log.d(TAG, "Navigation target applied: $startLine:$startColumn")
    }


    private fun selectTab(index: Int) {
        if (index !in editorSession.tabs.indices) return
        if (editorSession.activeIndex != index) {
            captureEditorState()
            val outgoing = editorSession.activeEditorTab
            val observed = outgoing?.let(::externalSnapshot)
            if (outgoing != null && observed != null) {
                handleExternalChange(outgoing, observed, onContinue = { selectTabNow(index) })
                return
            }
        }
        selectTabNow(index)
    }

    private fun selectTabNow(index: Int) {
        if (index !in editorSession.tabs.indices) return
        editorSession.select(index)
        when (val tab = editorSession.activeTab) {
            is EditorTab -> showEditorTab(tab)
            is TerminalTab -> showTerminalTab(tab)
            is DshWebTab -> showDshTab(tab)
            null -> showEmptyEditor()
        }
        refreshTabs()
    }

    private fun showEditorTab(tab: EditorTab) {
        suppressEditorEvents = true
        editor.editable = true
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
        editor.editable = !tab.readOnly
        suppressEditorEvents = false
        updateEditorBreakpointHighlights()
        terminalView.visibility = View.GONE
        terminalKeyBar.visibility = View.GONE
        if (::dshWebView.isInitialized) {
            dshWebView.visibility = View.GONE
            updateDshLoadingIndicator()
        }
        editor.visibility = View.VISIBLE
        editorSearchController.setEditorAvailable(true)
        updateSymbolBarVisibility()
        welcomePage.visibility = View.GONE
        scheduleLsp(tab, language)
        updateSymbolNavigationButtons()
        updateEditorMenuState()
    }

    private fun showTerminalTab(tab: TerminalTab) {
        editor.clearFocus()
        editor.visibility = View.GONE
        if (::dshWebView.isInitialized) {
            dshWebView.visibility = View.GONE
            updateDshLoadingIndicator()
        }
        editorSearchController.setEditorAvailable(false)
        updateSymbolBarVisibility()
        welcomePage.visibility = View.GONE
        terminalView.visibility = View.VISIBLE
        editor.setBreakpointLines(emptyList())
        terminalKeyBar.visibility = View.VISIBLE
        ctrlPressed = false
        altPressed = false
        updateTerminalModifierButtons()
        terminalView.attachSession(tab.session)
        terminalView.onScreenUpdated()
        terminalView.requestFocus()
        scheduleLsp(null)
        updateSymbolNavigationButtons()
        updateEditorMenuState()
    }

    private fun setupDshWebView() {
        dshWebView.settings.javaScriptEnabled = true
        dshWebView.settings.domStorageEnabled = true
        dshWebView.settings.databaseEnabled = true
        dshWebView.settings.useWideViewPort = true
        dshWebView.settings.loadWithOverviewMode = true
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(dshWebView, "Love2DroidFiles", setOf(DshDaemon.DEFAULT_URL)) {
                    _, message, sourceOrigin, isMainFrame, reply ->
                if (!isMainFrame || sourceOrigin.toString() != DshDaemon.DEFAULT_URL) return@addWebMessageListener
                val data = message.data ?: return@addWebMessageListener
                if (data.length > 65536) return@addWebMessageListener
                val request = runCatching { JSONObject(data) }.getOrNull() ?: return@addWebMessageListener
                val id = request.optString("id")
                if (id.isBlank() || id.length > 64) return@addWebMessageListener
                val respond: (String?) -> Unit = { error ->
                    val response = JSONObject().put("id", id)
                    error?.let { response.put("error", it) }
                    reply.postMessage(response.toString())
                }
                lifecycleScope.launch {
                    val file = try {
                        withContext(Dispatchers.IO) { editorFileAccess().guestFile(request.getString("path")) }
                    } catch (error: CancellationException) {
                        respond(getString(R.string.dsh_file_open_cancelled))
                        throw error
                    } catch (_: Exception) {
                        respond(getString(R.string.dsh_file_access_denied))
                        return@launch
                    }
                    openFile(file, allowExternal = true, onOpened = respond)
                }
            }
        } else {
            toast(getString(R.string.dsh_file_bridge_unsupported))
        }
        dshWebView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                dshWebLoadState.start(url)
                updateDshLoadingIndicator()
            }

            override fun onPageFinished(view: WebView, url: String) {
                normalizeDshWebViewInsets(view)
                dshWebLoadState.finish(url, view.url)
                updateDshLoadingIndicator()
            }

            override fun onReceivedError(
                view: WebView,
                request: android.webkit.WebResourceRequest,
                error: android.webkit.WebResourceError,
            ) {
                if (request.isForMainFrame) {
                    loadedDshWebUrl = null
                    dshWebLoadState.stop()
                    updateDshLoadingIndicator()
                    toast(getString(R.string.dsh_page_failed, error.description))
                }
            }
        }
        dshWebView.webChromeClient = WebChromeClient()
    }

    /**
     * The activity already consumes the system-bar inset above the WebView.
     * dsh-mobile-ux also applies that inset to its frame when viewport-fit=cover
     * is enabled, which produces a second blank band only inside the app.
     */
    private fun normalizeDshWebViewInsets(view: WebView) {
        view.evaluateJavascript(DSH_WEBVIEW_SAFE_AREA_COMPAT_SCRIPT, null)
    }

    private fun updateDshLoadingIndicator() {
        dshLoadingIndicator.visibility = if (dshWebView.visibility == View.VISIBLE && dshWebLoadState.isLoading) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun applyDshWebUrl(url: String) {
        editorSession.tabs.filterIsInstance<DshWebTab>().forEach { it.url = url }
        val activeTab = editorSession.activeTab
        if (activeTab is DshWebTab && dshWebView.visibility == View.VISIBLE && loadedDshWebUrl != url) {
            loadedDshWebUrl = url
            dshWebLoadState.start(url)
            updateDshLoadingIndicator()
            dshWebView.loadUrl(url)
        }
    }

    private fun showDshTab(tab: DshWebTab) {
        editor.clearFocus()
        terminalView.clearFocus()
        editor.visibility = View.GONE
        terminalView.visibility = View.GONE
        terminalKeyBar.visibility = View.GONE
        editorSearchController.setEditorAvailable(false)
        updateSymbolBarVisibility()
        welcomePage.visibility = View.GONE

        dshWebView.visibility = View.VISIBLE
        dshWebView.requestFocus()
        val url = DshDaemon.currentWebUrl()
        if (url != null) {
            applyDshWebUrl(url)
        } else {
            tab.url = DshDaemon.DEFAULT_URL
            loadedDshWebUrl = null
            dshWebLoadState.waitForService()
            dshWebView.loadUrl("about:blank")
            if (!DshDaemon.ensureStarted(this)) {
                dshWebLoadState.stop()
                toast(getString(R.string.dsh_start_failed))
            }
        }
        updateDshLoadingIndicator()
        scheduleLsp(null)
        updateSymbolNavigationButtons()
        updateEditorMenuState()
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
        val lineEnding: EditorLineEnding = EditorLineEnding.LF,
        val diskSnapshot: EditorFileSnapshot? = null,
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
                    val externalPath = if (path == null) tab.file?.absolutePath else null
                    WorkspaceTabSnapshot(
                        type = WorkspaceTabType.EDITOR,
                        path = path,
                        externalPath = externalPath,
                        text = tab.text.takeIf { tab.file == null || tab.dirty },
                        dirty = tab.dirty,
                        selectionStart = tab.selectionStart,
                        selectionEnd = tab.selectionEnd,
                        scrollX = tab.scrollX,
                        scrollY = tab.scrollY,
                        lineEnding = tab.lineEnding.value,
                        diskLength = tab.diskSnapshot?.length,
                        diskLastModified = tab.diskSnapshot?.lastModified,
                    )
                }
                is TerminalTab -> WorkspaceTabSnapshot(
                    type = WorkspaceTabType.TERMINAL,
                    title = tab.title,
                    workingDirectory = tab.workingDirectory,
                    isOmp = tab.isOmp,
                )
                is DshWebTab -> WorkspaceTabSnapshot(
                    type = WorkspaceTabType.DSH,
                    title = tab.title,
                    url = DshDaemon.DEFAULT_URL,
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
                    if (state.type == WorkspaceTabType.TERMINAL || state.type == WorkspaceTabType.DSH) {
                        RestoredWorkspaceTab(index, state, null, null)
                    } else {
                        val file = runCatching {
                            editorFileAccess().workspaceFile(project.root, state.path, state.externalPath)
                        }.getOrNull()
                        val disk = file?.let { runCatching { EditorFileLoader.load(it) }.getOrNull() }
                        val diskText = disk as? EditorFileLoadResult.Text
                        val text = when {
                            state.dirty && state.text != null -> state.text
                            diskText != null -> diskText.content
                            file == null && state.path.isNullOrBlank() && state.externalPath == null -> state.text.orEmpty()
                            else -> null
                        }
                        if (text == null) {
                            null
                        } else {
                            val storedSnapshot = if (state.dirty && state.diskLength != null && state.diskLastModified != null) {
                                EditorFileSnapshot(state.diskLength, state.diskLastModified)
                            } else {
                                diskText?.snapshot ?: file?.let(EditorFileLoader::snapshot)
                            }
                            RestoredWorkspaceTab(
                                originalIndex = index,
                                state = state,
                                file = file,
                                text = text,
                                lineEnding = state.lineEnding?.let { value ->
                                    EditorLineEnding.entries.firstOrNull { it.value == value }
                                } ?: diskText?.lineEnding ?: EditorLineEnding.LF,
                                diskSnapshot = storedSnapshot,
                            )
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
                            lineEnding = restored.lineEnding
                            diskSnapshot = restored.diskSnapshot
                        })
                    }
                    WorkspaceTabType.TERMINAL -> {
                        val isOmp = restored.state.isOmp
                        if (isOmp && !ProotRuntime.isOmpReady(this@EditorActivity)) return@forEach
                        if (!isOmp && !ProotRuntime.isRootfsReady(this@EditorActivity)) return@forEach
                        val relativeDirectory = restored.state.workingDirectory
                        val terminalDirectory = when (relativeDirectory) {
                            null -> null
                            else -> runCatching {
                                StorageUtils.resolveChild(project.root, relativeDirectory)
                            }.getOrNull()?.takeIf { it.isDirectory }
                                ?: project.root
                        }
                        val startup = if (isOmp) ProotRuntime.ompStartupCommand(resume = true) else null
                        createTerminalTab(
                            startupCommand = startup,
                            isOmp = isOmp,
                            projectRoot = terminalDirectory,
                            title = restored.state.title,
                            selectAfterCreate = false,
                        ) ?: return@forEach
                    }
                    WorkspaceTabType.DSH -> {
                        if (!ProotRuntime.isDshReady(this@EditorActivity)) return@forEach
                        val title = restored.state.title ?: getString(R.string.dsh_tab_title)
                        val url = DshDaemon.currentWebUrl() ?: DshDaemon.DEFAULT_URL
                        val tab = DshWebTab(title = title, url = url)
                        editorSession.add(tab)
                    }
                }
                if (newIndex != null) {
                    restoredIndices[restored.originalIndex] = newIndex
                }
            }
            val active = restoredIndices[snapshot.activeTab] ?: restoredIndices.values.firstOrNull()
            if (active != null) selectTab(active) else showEmptyEditor()
            refreshTabs()
        }
    }

    private fun tabDisplayText(tab: WorkspaceTab): String {
        val name = when (tab) {
            is EditorTab -> tab.file?.name ?: getString(R.string.unnamed_file)
            is TerminalTab -> tab.title
            is DshWebTab -> tab.title
        }
        val shortenedName = if (name.length > MAX_TAB_NAME_CHARS) {
            name.take(MAX_TAB_NAME_CHARS) + "..."
        } else {
            name
        }
        return if ((tab as? EditorTab)?.dirty == true) {
            getString(R.string.dirty_tab_label, shortenedName)
        } else {
            shortenedName
        }
    }

    private fun updateTabLabel(tab: WorkspaceTab) {
        tabLabels[tab]?.text = tabDisplayText(tab)
    }

    private fun refreshTabs() {
        tabLabels.clear()
        tabContainer.removeAllViews()
        val colors = editor.colorScheme
        val tabBackground = colors.getColor(EditorColorScheme.WHOLE_BACKGROUND)
        tabContainer.setBackgroundColor(tabBackground)
        tabScroll.setBackgroundColor(tabBackground)
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
        val tabTextColor = colors.getColor(EditorColorScheme.TEXT_NORMAL)
        val tabIconTint = android.content.res.ColorStateList.valueOf(tabTextColor)
        val tabDividerColor = colors.getColor(EditorColorScheme.LINE_DIVIDER)
        val activeIndicatorColor = MaterialColors.getColor(
            tabContainer,
            androidx.appcompat.R.attr.colorPrimary,
        )
        editorSession.tabs.forEachIndexed { index, tab ->
            if (index > 0) {
                tabContainer.addView(View(this).apply {
                    setBackgroundColor(tabDividerColor)
                }, LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT))
            }
            val tabRoot = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = tabRipple?.constantState?.newDrawable()?.mutate()
                setOnClickListener {
                    val currentIndex = editorSession.tabs.indexOf(tab)
                    if (currentIndex < 0) return@setOnClickListener
                    if (currentIndex == editorSession.activeIndex) {
                        showTabMenu(currentIndex, this)
                    } else {
                        selectTab(currentIndex)
                    }
                }
            }
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            val label = TextView(this).apply {
                text = tabDisplayText(tab)
                setTextColor(tabTextColor)
                typeface = mapleTypeface
                textSize = 13f
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(dp(16), 0, dp(8), 0)
            }
            tabLabels[tab] = label
            val close = ImageButton(this).apply {
                setImageResource(R.drawable.ic_close)
                imageTintList = tabIconTint
                background = iconRipple?.constantState?.newDrawable()?.mutate()
                adjustViewBounds = true
                scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(3), dp(3), dp(3), dp(3))
                minimumWidth = 0
                minimumHeight = 0
                contentDescription = getString(
                    when (tab) {
                        is TerminalTab -> R.string.close_terminal
                        is DshWebTab -> R.string.close_terminal
                        else -> R.string.close_file
                    },
                )
                setOnClickListener { closeTab(tab) }
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
            imageTintList = tabIconTint
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

    private fun showTabMenu(index: Int, anchor: View) {
        val tabs = editorSession.tabs
        if (index !in tabs.indices) return
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, MENU_CLOSE_TAB, 0, R.string.close_tab)
        val closeOthers = menu.menu.add(0, MENU_CLOSE_OTHERS, 1, R.string.close_other_tabs)
        menu.menu.add(0, MENU_CLOSE_ALL, 2, R.string.close_all_tabs)
        val closeLeft = menu.menu.add(0, MENU_CLOSE_LEFT, 3, R.string.close_left_tabs)
        val closeRight = menu.menu.add(0, MENU_CLOSE_RIGHT, 4, R.string.close_right_tabs)
        closeOthers.isEnabled = tabs.size > 1
        closeLeft.isEnabled = index > 0
        closeRight.isEnabled = index < tabs.lastIndex
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_CLOSE_TAB -> closeTab(index)
                MENU_CLOSE_OTHERS -> closeTabs(tabs.filterIndexed { tabIndex, _ -> tabIndex != index })
                MENU_CLOSE_ALL -> closeTabs(tabs.toList())
                MENU_CLOSE_LEFT -> closeTabs(tabs.take(index))
                MENU_CLOSE_RIGHT -> closeTabs(tabs.drop(index + 1))
            }
            true
        }
        menu.show()
    }

    private fun closeTabs(tabs: List<WorkspaceTab>) {
        closeNextTab(tabs, 0)
    }

    private fun closeNextTab(tabs: List<WorkspaceTab>, index: Int) {
        if (index >= tabs.size) return
        val tab = tabs[index]
        if (!editorSession.tabs.contains(tab)) {
            closeNextTab(tabs, index + 1)
        } else {
            closeTab(tab) { closeNextTab(tabs, index + 1) }
        }
    }

    private fun closeTab(index: Int) {
        editorSession.tabs.getOrNull(index)?.let { closeTab(it) }
    }

    private fun closeTab(tab: WorkspaceTab, onClosed: () -> Unit = {}) {
        if (!editorSession.tabs.contains(tab)) {
            onClosed()
            return
        }
        when (tab) {
            is TerminalTab -> {
                tab.session.finishIfRunning()
                removeTabFor(tab, onClosed)
            }
            is DshWebTab -> {
                removeTabFor(tab, onClosed)
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
                        .setNeutralButton(R.string.discard_changes) { _, _ -> removeTabFor(tab, onClosed) }
                        .setPositiveButton(R.string.save_changes) { _, _ ->
                            if (tab.file == null) {
                                saveTabAs(tab) { removeTabFor(tab, onClosed) }
                            } else {
                                saveTabIfReady(
                                    tab,
                                    onSaved = { if (saveTabBlocking(tab)) removeTabFor(tab, onClosed) },
                                    onReload = { if (!tab.dirty) removeTabFor(tab, onClosed) },
                                )
                            }
                        }
                        .show()
                } else {
                    removeTabFor(tab, onClosed)
                }
            }
        }
    }

    private fun removeTabFor(tab: WorkspaceTab, onRemoved: () -> Unit = {}) {
        val index = editorSession.tabs.indexOf(tab)
        if (index >= 0) {
            removeTab(index, onRemoved)
        } else {
            onRemoved()
        }
    }

    private fun removeTab(index: Int, onRemoved: () -> Unit = {}) {
        if (index !in editorSession.tabs.indices) {
            onRemoved()
            return
        }
        captureEditorState()
        editorSession.remove(index)
        if (editorSession.activeIndex >= 0) selectTab(editorSession.activeIndex) else showEmptyEditor()
        refreshTabs()
        onRemoved()
    }


    private fun saveActiveDocument() {
        captureEditorState()
        val tab = editorSession.activeEditorTab ?: run {
            toast(getString(R.string.no_active_document))
            return
        }
        if (tab.file == null) {
            saveTabAs(tab)
        } else {
            saveTabIfReady(tab, onSaved = {
                if (saveTabBlocking(tab)) refreshTabs()
            })
        }
    }

    private fun saveActiveDocumentAs() {
        captureEditorState()
        editorSession.activeEditorTab?.let(::saveTabAs)
            ?: toast(getString(R.string.no_active_document))
    }

    private fun saveTabIfReady(
        tab: EditorTab,
        onSaved: () -> Unit,
        onReload: () -> Unit = {},
    ) {
        val observed = externalSnapshot(tab)
        if (observed == null) {
            onSaved()
        } else if (!tab.dirty) {
            reloadEditorTab(tab) { onSaved() }
        } else {
            handleExternalChange(
                tab = tab,
                observed = observed,
                onContinue = onSaved,
                onKeep = onSaved,
                onReload = onReload,
            )
        }
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
                StorageUtils.writeTextAtomic(target, EditorFileLoader.normalizeLineEndings(tab.text, tab.lineEnding))
                tab.file = target
                tab.languageScope = LanguageResolver.scopeFor(target)
                tab.diskSnapshot = EditorFileLoader.snapshot(target)
                tab.lastObservedExternalSnapshot = null
                tab.externalChangeAcknowledged = false
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
        if (runCatching { editorFileAccess().hostFile(file.path) }.isFailure) {
            toast(getString(R.string.dsh_file_access_denied))
            return false
        }
        val observed = externalSnapshot(tab)
        if (observed != null) {
            toast(getString(R.string.external_file_save_blocked, file.name))
            return false
        }
        val saved = runCatching {
            StorageUtils.writeTextAtomic(file, EditorFileLoader.normalizeLineEndings(tab.text, tab.lineEnding))
            tab.dirty = false
            tab.diskSnapshot = EditorFileLoader.snapshot(file)
            tab.lastObservedExternalSnapshot = null
            tab.externalChangeAcknowledged = false
        }.onFailure { toast(it.message ?: "保存失败") }.isSuccess
        if (saved) {
            lspController?.let { controller ->
                lifecycleScope.launch { controller.notifySaved(file) }
            }
        }
        return saved
    }

    private fun saveAllThen(requireNamed: Boolean = false, onSaved: () -> Unit) {
        captureEditorState()
        val editorTabs = editorSession.tabs.filterIsInstance<EditorTab>()
        if (requireNamed && editorTabs.any { it.dirty && it.file == null }) {
            toast(getString(R.string.unnamed_file_save_required))
            return
        }
        saveNextTab(editorTabs.filter { it.dirty && it.file != null }, 0, onSaved)
    }

    private fun saveNextTab(tabs: List<EditorTab>, index: Int, onSaved: () -> Unit) {
        if (index >= tabs.size) {
            refreshTabs()
            onSaved()
            return
        }
        val tab = tabs[index]
        val next = { saveNextTab(tabs, index + 1, onSaved) }
        val observed = externalSnapshot(tab)
        if (observed == null) {
            if (saveTabBlocking(tab)) next()
        } else {
            handleExternalChange(
                tab = tab,
                observed = observed,
                onContinue = next,
                onKeep = { if (saveTabBlocking(tab)) next() },
                onReload = { if (!tab.dirty) next() },
            )
        }
    }

    private fun playCurrentProject() {
        val project = currentProject ?: run {
            openProjectManagerIfNeeded(force = true)
            return
        }
        saveAllThen(requireNamed = true) {
            if (currentProject?.id != project.id) return@saveAllThen
            lifecycleScope.launch {
                try {
                    val packageFile = withContext(Dispatchers.IO) {
                        ProjectValidator.validate(project)?.let { throw IOException(it) }
                        LovePackageBuilder.build(project, cacheDir)
                    }
                    val breakpoints = currentBreakpoints.sortedWith(
                        compareBy(ProjectBreakpoint::file, ProjectBreakpoint::line),
                    )
                    val uri = FileProvider.getUriForFile(this@EditorActivity, "$packageName.fileprovider", packageFile)
                    startActivity(
                        Intent(this@EditorActivity, top.wsdx233.love2droid.runtime.LoveGameActivity::class.java)
                            .setData(uri)
                            .putExtra(
                                top.wsdx233.love2droid.runtime.LoveGameActivity.EXTRA_DEBUG_PROJECT_ID,
                                project.id,
                            )
                            .putStringArrayListExtra(
                                top.wsdx233.love2droid.runtime.LoveGameActivity.EXTRA_DEBUG_BREAKPOINT_FILES,
                                ArrayList(breakpoints.map(ProjectBreakpoint::file)),
                            )
                            .putIntegerArrayListExtra(
                                top.wsdx233.love2droid.runtime.LoveGameActivity.EXTRA_DEBUG_BREAKPOINT_LINES,
                                ArrayList(breakpoints.map(ProjectBreakpoint::line)),
                            )
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    )
                } catch (error: Exception) {
                    toast(error.message ?: "无法启动项目")
                }
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
            listOf(
                "quietlight" to "textmate/quietlight.json",
                "darcula" to "textmate/darcula.json",
            ).forEach { (id, path) ->
                providers.tryGetInputStream(path)?.use { stream ->
                    themes.loadTheme(
                        ThemeModel(
                            IThemeSource.fromInputStream(stream, path, null),
                            id,
                        ).apply { isDark = id == "darcula" },
                    )
                } ?: error("缺少 $path")
            }
            GrammarRegistry.getInstance().loadGrammars("textmate/languages.json")
            requireNotNull(GrammarRegistry.getInstance().findGrammar("source.lua")) { "Lua grammar 未注册" }
            textMateReady = true
            applyEditorTheme()
        }.onFailure { error ->
            textMateReady = false
            toast(getString(R.string.syntax_highlighting_failed, error.message ?: error.javaClass.simpleName))
        }
    }

    private fun applyEditorTheme() {
        if (!textMateReady) return
        val themeId = settings.editorThemeMode.resolveEditorThemeId(isSystemEditorThemeDark())
        if (appliedEditorThemeId == themeId) return
        runCatching {
            val themes = ThemeRegistry.getInstance()
            themes.setTheme(themeId)
            editor.colorScheme = TextMateColorScheme.create(themes)
            appliedEditorThemeId = themeId
            applyEditorSurfaceColors()
        }.onFailure { error ->
            toast(getString(R.string.syntax_highlighting_failed, error.message ?: error.javaClass.simpleName))
        }
    }

    private fun isSystemEditorThemeDark(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun saveAllOnPause() {
        captureEditorState()
        editorSession.tabs.filterIsInstance<EditorTab>().forEach { tab ->
            val observed = externalSnapshot(tab)
            if (observed != null) {
                tab.lastObservedExternalSnapshot = observed
            } else if (tab.dirty) {
                saveTabBlocking(tab)
            }
        }
    }

    private fun refreshOpenEditorFiles() {
        editorSession.tabs.filterIsInstance<EditorTab>().toList().forEach { tab ->
            val observed = externalSnapshot(tab) ?: return@forEach
            if (tab.dirty) {
                if (tab.lastObservedExternalSnapshot != observed && tab === editorSession.activeEditorTab) {
                    tab.lastObservedExternalSnapshot = observed
                    handleExternalChange(tab, observed, onContinue = {})
                }
            } else {
                tab.lastObservedExternalSnapshot = observed
                handleExternalChange(tab, observed, onContinue = {})
            }
        }
    }
    override fun onDestroy() {
        persistWorkspace()
        directoryRefreshHandler.removeCallbacks(directoryRefreshRunnable)
        directoryObserver?.stopWatching()
        directoryObserver = null
        workspaceRestoreJob?.cancel()
        lspJob?.cancel()
        symbolNavigationJob?.cancel()
        editorSearchController.dispose()
        lspController?.close()
        dshWebUrlObserver?.invoke()
        dshWebUrlObserver = null
        finishTerminalTabs()
        try {
            dshWebView.destroy()
        } catch (_: Throwable) {
        }
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
            lspController?.setHoverInfoEnabled(settings.editorHoverInfo)
            updateSymbolBarVisibility()
            updateEditorMenuState()
            applyEditorTheme()
            refreshOpenEditorFiles()
            refreshFileList()
            if (settings.dshBackgroundEnabled) {
                DshDaemon.ensureStarted(this)
            }
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
        private const val MENU_IMPORT = 13
        private const val MENU_EXPORT = 14
        private const val MENU_CLOSE_TAB = 15
        private const val MENU_CLOSE_OTHERS = 16
        private const val MENU_CLOSE_ALL = 17
        private const val MENU_CLOSE_LEFT = 18
        private const val MENU_CLOSE_RIGHT = 19
        private const val MAX_TAB_NAME_CHARS = 15
        private const val TERMINAL_KEY_COLOR = 0xFF424242.toInt()
        private const val TERMINAL_MODIFIER_COLOR = 0xFF5C6BC0.toInt()
        private val DSH_WEBVIEW_SAFE_AREA_COMPAT_SCRIPT = """
            (() => {
                const id = 'love2droid-dsh-webview-safe-area';
                let style = document.getElementById(id);
                if (style === null) {
                    style = document.createElement('style');
                    style.id = id;
                    document.head.appendChild(style);
                }
                style.textContent = `
                    @media (max-width: 1023px) {
                        [data-mobile-ux="frame"],
                        [data-mobile-ux="frame"] > :first-child,
                        [data-mobile-nav="frame"],
                        [data-mobile-nav="frame"] > :first-child {
                            padding-top: 0 !important;
                        }
                    }
                `;
            })();
        """.trimIndent()
    }
}
