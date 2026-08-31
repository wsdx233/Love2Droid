package top.wsdx233.love2droid

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.lang.EmptyLanguage
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
import kotlinx.coroutines.Dispatchers
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
    private lateinit var treeAdapter: FileTreeAdapter

    private val projectRepository by lazy { ProjectRepository(this) }
    private val editorSession = EditorSession()
    private val selectedPaths = linkedSetOf<String>()
    private val expandedPaths = linkedSetOf<String>("")
    private var currentProject: Project? = null
    private var suppressEditorEvents = false
    private var selectionMode = false
    private var clipboardFiles: List<File> = emptyList()
    private var clipboardIsCut = false
    private var openedManagerForEmptyState = false
    private lateinit var editorTopInset: View
    private lateinit var drawerTopInset: View
    private var textMateReady = false

    private val projectManagerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val id = result.data?.getStringExtra(ProjectManagerActivity.EXTRA_PROJECT_ID)
            if (id != null) projectRepository.findProject(id)?.let(::switchProject)
        }
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
        welcomePage = findViewById(R.id.welcome_page)
        drawerProjectTitle = findViewById(R.id.drawer_project_title)
        drawerProjectPath = findViewById(R.id.drawer_project_path)
        findViewById<View>(R.id.welcome_open_file).setOnClickListener {
            drawer.openDrawer(GravityCompat.START)
        }
        findViewById<View>(R.id.welcome_new_file).setOnClickListener { newDocument() }

        setupWindowInsets()
        toolbar.navigationIcon = ContextCompat.getDrawable(this, R.drawable.ic_menu)
        toolbar.navigationContentDescription = getString(R.string.file_tree)
        toolbar.setNavigationOnClickListener { drawer.openDrawer(GravityCompat.START) }
        toolbar.inflateMenu(R.menu.editor_menu)
        toolbar.setOnMenuItemClickListener(::onToolbarItemSelected)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
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
        setupEditorInput()
        treeAdapter = FileTreeAdapter(
            onClick = ::onTreeItemClicked,
            onLongClick = ::showTreeMenu,
            onSwipe = ::toggleSelection,
        )
        findViewById<RecyclerView>(R.id.file_tree).apply {
            layoutManager = LinearLayoutManager(this@EditorActivity)
            adapter = treeAdapter
            setHasFixedSize(true)
        }

        setupTextMate()
        editor.subscribeAlways<ContentChangeEvent> {
            if (!suppressEditorEvents) {
                editorSession.activeTab?.let { tab ->
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
    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(drawer) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            editorTopInset.layoutParams = editorTopInset.layoutParams.apply { height = systemBars.top }
            drawerTopInset.layoutParams = drawerTopInset.layoutParams.apply { height = systemBars.top }
            val bottomInset = maxOf(systemBars.bottom, ime.bottom)
            (symbolScroll.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                if (params.bottomMargin != bottomInset) {
                    params.bottomMargin = bottomInset
                    symbolScroll.layoutParams = params
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
                typeface = Typeface.MONOSPACE
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

    private fun setupEditorInput() {
        editor.isFocusableInTouchMode = true
        editor.setInputType(
            InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
        )
        editor.props.allowFullscreen = false
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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
                editor.isWordwrap = !editor.isWordwrap
                item.isChecked = editor.isWordwrap
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
        currentProject = projectRepository.markOpened(project)
        editorSession.clear()
        selectedPaths.clear()
        expandedPaths.clear()
        expandedPaths += ""
        selectionMode = false
        toolbar.title = currentProject?.displayName.orEmpty()
        drawerProjectTitle.text = currentProject?.displayName
        drawerProjectPath.text = currentProject?.root?.path
        refreshTabs()
        showEmptyEditor()
        refreshTree()
    }

    private fun showEmptyEditor() {
        editor.clearFocus()
        editor.visibility = View.GONE
        symbolScroll.visibility = View.GONE
        welcomePage.visibility = View.VISIBLE
    }

    private fun refreshTree() {
        val project = currentProject ?: run {
            treeAdapter.submitItems(emptyList(), selectedPaths)
            return
        }
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { flattenTree(project.root) }
            if (currentProject?.id == project.id) {
                treeAdapter.submitItems(items, selectedPaths)
            }
        }
    }

    private fun flattenTree(root: File): List<TreeItem> {
        val result = mutableListOf<TreeItem>()
        fun append(directory: File, relativeDirectory: String, depth: Int) {
            val children = directory.listFiles()
                ?.asSequence()
                ?.filter { it.name != StorageUtils.METADATA_FILE }
                ?.filter { StorageUtils.isWithin(root, it) }
                ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
                ?.toList()
                ?: return
            children.forEach { child ->
                val relative = if (relativeDirectory.isBlank()) child.name else "$relativeDirectory/${child.name}"
                val childCount = if (child.isDirectory) child.listFiles()?.count { it.name != StorageUtils.METADATA_FILE } ?: 0 else 0
                result += TreeItem(child, relative, depth, child.isDirectory, childCount)
                if (child.isDirectory && expandedPaths.contains(relative)) {
                    append(child, relative, depth + 1)
                }
            }
        }
        append(root, "", 0)
        return result
    }

    private fun onTreeItemClicked(item: TreeItem) {
        if (selectionMode) {
            toggleSelection(item)
            return
        }
        if (item.directory) {
            if (!expandedPaths.add(item.relativePath)) expandedPaths.remove(item.relativePath)
            refreshTree()
        } else {
            openFile(item.file)
            drawer.closeDrawer(GravityCompat.START)
        }
    }

    private fun toggleSelection(item: TreeItem) {
        selectionMode = true
        if (!selectedPaths.add(item.relativePath)) selectedPaths.remove(item.relativePath)
        if (selectedPaths.isEmpty()) selectionMode = false
        toolbar.title = if (selectionMode) "已选择 ${selectedPaths.size} 项" else currentProject?.displayName.orEmpty()
        treeAdapter.submitItems(flattenTree(currentProject?.root ?: return), selectedPaths)
    }

    private fun showTreeMenu(item: TreeItem, anchor: View) {
        selectionMode = true
        val menu = PopupMenu(this, anchor)
        if (!item.directory) {
            menu.menu.add(0, MENU_OPEN, 0, "打开")
        } else {
            menu.menu.add(0, MENU_NEW_FILE, 0, R.string.new_file)
            menu.menu.add(0, MENU_NEW_FOLDER, 1, R.string.new_folder)
            if (clipboardFiles.isNotEmpty()) menu.menu.add(0, MENU_PASTE, 2, R.string.paste)
        }
        menu.menu.add(0, MENU_RENAME, 3, R.string.rename)
        menu.menu.add(0, MENU_COPY, 4, R.string.copy)
        menu.menu.add(0, MENU_CUT, 5, R.string.cut)
        menu.menu.add(0, MENU_DELETE, 6, R.string.delete)
        menu.menu.add(0, MENU_DETAILS, 7, R.string.details)
        if (selectedPaths.size == 2) menu.menu.add(0, MENU_RANGE, 8, R.string.select_range)
        if (selectionMode) menu.menu.add(0, MENU_CLEAR_SELECTION, 9, R.string.clear_selection)
        menu.setOnMenuItemClickListener { selected ->
            when (selected.itemId) {
                MENU_OPEN -> openFile(item.file)
                MENU_NEW_FILE -> createChild(item.file, false)
                MENU_NEW_FOLDER -> createChild(item.file, true)
                MENU_PASTE -> pasteInto(if (item.directory) item.file else item.file.parentFile)
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
        selectionMode = false
        updateSelectionUi()
    }

    private fun updateSelectionUi() {
        toolbar.title = if (selectionMode) "已选择 ${selectedPaths.size} 项" else currentProject?.displayName.orEmpty()
        currentProject?.root?.let { treeAdapter.submitItems(flattenTree(it), selectedPaths) }
    }

    private fun selectRange() {
        val project = currentProject ?: return
        if (selectedPaths.size != 2) return
        val paths = selectedPaths.toList()
        val parentA = paths[0].substringBeforeLast('/', "")
        val parentB = paths[1].substringBeforeLast('/', "")
        if (parentA != parentB) {
            toast("区间选择需要位于同一目录")
            return
        }
        val siblings = flattenTree(project.root)
            .filter { it.relativePath.substringBeforeLast('/', "") == parentA }
        val first = siblings.indexOfFirst { it.relativePath == paths[0] }
        val second = siblings.indexOfFirst { it.relativePath == paths[1] }
        if (first < 0 || second < 0) return
        selectedPaths.clear()
        siblings.subList(minOf(first, second), maxOf(first, second) + 1).forEach { selectedPaths += it.relativePath }
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
                refreshTree()
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
            refreshTree()
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
        if (targetDirectory == null || !targetDirectory.isDirectory || !StorageUtils.isWithin(project.root, targetDirectory)) return
        try {
            clipboardFiles.forEach { source ->
                if (!source.exists() || !StorageUtils.isWithin(project.root, source)) return@forEach
                val target = File(targetDirectory, source.name)
                require(!target.exists()) { "目标已存在：${source.name}" }
                require(StorageUtils.isWithin(project.root, target))
                StorageUtils.copyRecursively(source, target)
                if (clipboardIsCut) StorageUtils.deleteRecursively(source)
            }
            clipboardFiles = emptyList()
            refreshTree()
            toast("粘贴完成")
        } catch (error: Exception) {
            toast(error.message ?: "粘贴失败")
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
                runCatching { files.forEach(StorageUtils::deleteRecursively) }
                    .onSuccess { clearSelection(); refreshTree() }
                    .onFailure { toast(it.message ?: "删除失败") }
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

    private fun openFile(file: File) {
        if (!file.isFile) return
        if (file.length() > MAX_EDITOR_BYTES) {
            toast("文件过大，暂不载入编辑器")
            return
        }
        editorSession.find(file)?.let { tab ->
            selectTab(editorSession.tabs.indexOf(tab))
            return
        }
        lifecycleScope.launch {
            try {
                val text = withContext(Dispatchers.IO) { file.readText(Charsets.UTF_8) }
                captureEditorState()
                val tab = EditorTab(file, text, LanguageResolver.scopeFor(file))
                val index = editorSession.add(tab)
                selectTab(index)
            } catch (error: Exception) {
                toast(error.message ?: "无法打开文件")
            }
        }
    }

    private fun selectTab(index: Int) {
        if (editorSession.activeIndex != index) captureEditorState()
        editorSession.select(index)
        val tab = editorSession.activeTab ?: run {
            showEmptyEditor()
            return
        }
        suppressEditorEvents = true
        setEditorLanguage(tab.languageScope)
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
        editor.visibility = View.VISIBLE
        symbolScroll.visibility = View.VISIBLE
        welcomePage.visibility = View.GONE
        refreshTabs()
    }

    private fun captureEditorState() {
        val tab = editorSession.activeTab ?: return
        if (suppressEditorEvents) return
        tab.text = editor.text.toString()
        tab.selectionStart = editor.cursor.left
        tab.selectionEnd = editor.cursor.right
        tab.scrollX = editor.offsetX
        tab.scrollY = editor.offsetY
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
                val name = tab.file?.name ?: getString(R.string.unnamed_file)
                val shortenedName = if (name.length > MAX_TAB_NAME_CHARS) {
                    name.take(MAX_TAB_NAME_CHARS) + "..."
                } else {
                    name
                }
                text = if (tab.dirty) getString(R.string.dirty_tab_label, shortenedName) else shortenedName
                setTextColor(if (index == editorSession.activeIndex) Color.WHITE else Color.LTGRAY)
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
                contentDescription = getString(R.string.close_file)
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
        val tab = editorSession.tabs.getOrNull(index) ?: return
        if (tab.dirty) {
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.unsaved_file_title, tab.file?.name ?: getString(R.string.unnamed_file)))
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

    private fun removeTab(index: Int) {
        captureEditorState()
        editorSession.remove(index)
        if (editorSession.activeIndex >= 0) selectTab(editorSession.activeIndex) else showEmptyEditor()
        refreshTabs()
    }

    private fun saveActiveDocument() {
        captureEditorState()
        val tab = editorSession.activeTab ?: run {
            toast(getString(R.string.no_active_document))
            return
        }
        if (tab.file == null) saveTabAs(tab) else saveTabBlocking(tab)
    }

    private fun saveActiveDocumentAs() {
        captureEditorState()
        editorSession.activeTab?.let(::saveTabAs) ?: toast(getString(R.string.no_active_document))
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
                if (editorSession.activeTab === tab) setEditorLanguage(tab.languageScope)
            }.onSuccess {
                refreshTabs()
                refreshTree()
                onSaved()
            }.onFailure { toast(it.message ?: "保存失败") }
        }
    }

    private fun saveTabBlocking(tab: EditorTab): Boolean {
        val file = tab.file ?: return false
        return runCatching {
            StorageUtils.writeTextAtomic(file, tab.text)
            tab.dirty = false
        }.onFailure { toast(it.message ?: "保存失败") }.isSuccess
    }

    private fun saveAllBlocking(requireNamed: Boolean = false): Boolean {
        captureEditorState()
        if (requireNamed && editorSession.tabs.any { it.dirty && it.file == null }) {
            toast("存在未命名未保存文件，请先另存为")
            return false
        }
        return editorSession.tabs.filter { it.file != null }.all(::saveTabBlocking)
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

    private fun setEditorLanguage(scope: String?) {
        val language = if (scope == null || !textMateReady) {
            EmptyLanguage()
        } else {
            runCatching { TextMateLanguage.create(scope, true) }
                .onFailure { toast(getString(R.string.syntax_highlighting_failed, scope)) }
                .getOrElse { EmptyLanguage() }
        }
        editor.setEditorLanguage(language)
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
        super.onPause()
    }


    companion object {
        private const val MAX_EDITOR_BYTES = 5L * 1024 * 1024
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
        private const val MAX_TAB_NAME_CHARS = 15
    }
}
