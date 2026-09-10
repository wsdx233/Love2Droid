package top.wsdx233.love2droid

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ProjectManagerActivity : AppCompatActivity() {
    private lateinit var repository: ProjectRepository
    private lateinit var adapter: ProjectAdapter
    private lateinit var emptyContainer: View
    private lateinit var toolbar: MaterialToolbar
    private lateinit var chipGroup: ChipGroup

    private var allProjects: List<Project> = emptyList()
    private var currentGroupFilter: String? = null // null means "全部"
    private var pendingLoveExport: Project? = null
    private var pendingBatchExportProjects: List<Project> = emptyList()
    private var batchExportDirectoryUri: Uri? = null

    private val newProjectLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data?.getStringExtra(EXTRA_PROJECT_ID) != null) {
            setResult(RESULT_OK, result.data)
            finish()
        }
    }

    private val loveImportLauncher = registerForActivityResult(
        DocumentsUiOpenDocumentContract(),
    ) { uri ->
        if (uri != null) importLoveProject(uri)
    }

    private val loveExportLauncher = registerForActivityResult(
        DocumentsUiCreateDocumentContract("application/zip"),
    ) { uri ->
        val project = pendingLoveExport
        pendingLoveExport = null
        if (uri != null && project != null) exportLoveProject(uri, project)
    }

    private val batchExportTreeLauncher = registerForActivityResult(
        DocumentsUiOpenDocumentTreeContract(),
    ) { treeUri ->
        if (treeUri != null && pendingBatchExportProjects.isNotEmpty()) {
            executeBatchExport(treeUri, pendingBatchExportProjects)
        }
        pendingBatchExportProjects = emptyList()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_project_manager)
        AppUpdateUi.bind(this)
        repository = ProjectRepository(this)
        emptyContainer = findViewById(R.id.project_empty_container)
        toolbar = findViewById(R.id.project_toolbar)
        chipGroup = findViewById(R.id.group_chip_group)

        val root = findViewById<View>(R.id.project_root)
        val topInset = findViewById<View>(R.id.project_top_inset)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            topInset.layoutParams = topInset.layoutParams.apply { height = systemBars.top }
            root.setPadding(root.paddingLeft, root.paddingTop, root.paddingRight, systemBars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)

        setupToolbar()

        adapter = ProjectAdapter(
            iconProvider = { project -> repository.projectIcon(project) },
            onClick = { project ->
                if (adapter.isSelectionMode) {
                    updateSelectionTitle()
                } else {
                    returnProject(project)
                }
            },
            onLongClick = { project, anchor ->
                if (adapter.isSelectionMode) {
                    adapter.toggleSelection(project)
                    updateSelectionTitle()
                } else {
                    showProjectMenu(project, anchor)
                }
            },
        )

        findViewById<RecyclerView>(R.id.project_list).apply {
            layoutManager = LinearLayoutManager(this@ProjectManagerActivity)
            adapter = this@ProjectManagerActivity.adapter
        }

        refresh()
    }

    private fun setupToolbar() {
        toolbar.apply {
            navigationIcon = ContextCompat.getDrawable(this@ProjectManagerActivity, R.drawable.ic_arrow_back)
            setNavigationOnClickListener {
                if (adapter.isSelectionMode) {
                    exitSelectionMode()
                } else {
                    finish()
                }
            }
            menu.clear()
            inflateMenu(R.menu.project_manager_menu)
            tintMenuIcons()
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_new_project -> {
                        openNewProject()
                        true
                    }
                    R.id.action_import_love -> {
                        loveImportLauncher.launch(arrayOf("application/zip", "application/x-love", "application/octet-stream", "*/*"))
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun setupSelectionToolbar() {
        toolbar.apply {
            navigationIcon = ContextCompat.getDrawable(this@ProjectManagerActivity, R.drawable.close_rounded)
            setNavigationOnClickListener { exitSelectionMode() }
            menu.clear()
            inflateMenu(R.menu.project_manager_selection_menu)
            tintMenuIcons()
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_batch_move -> {
                        val selected = adapter.getSelectedProjects()
                        if (selected.isNotEmpty()) {
                            showBatchMoveDialog(selected)
                        }
                        true
                    }
                    R.id.action_batch_export -> {
                        val selected = adapter.getSelectedProjects()
                        if (selected.isNotEmpty()) {
                            startBatchExport(selected)
                        }
                        true
                    }
                    R.id.action_batch_delete -> {
                        val selected = adapter.getSelectedProjects()
                        if (selected.isNotEmpty()) {
                            confirmBatchDelete(selected)
                        }
                        true
                    }
                    R.id.action_select_all -> {
                        if (adapter.getSelectedCount() == filteredProjects().size) {
                            adapter.clearSelection()
                        } else {
                            adapter.selectAll()
                        }
                        updateSelectionTitle()
                        true
                    }
                    else -> false
                }
            }
        }
        updateSelectionTitle()
    }

    private fun tintMenuIcons() {
        val foreground = ContextCompat.getColor(this, R.color.action_bar_foreground)
        for (i in 0 until toolbar.menu.size()) {
            toolbar.menu.getItem(i).icon?.mutate()?.setTint(foreground)
        }
    }

    private fun enterSelectionMode() {
        if (!adapter.isSelectionMode) {
            adapter.setSelectionMode(true)
            setupSelectionToolbar()
        }
    }

    private fun exitSelectionMode() {
        if (adapter.isSelectionMode) {
            adapter.setSelectionMode(false)
            setupToolbar()
            toolbar.title = getString(R.string.project_manager)
        }
    }

    private fun updateSelectionTitle() {
        val count = adapter.getSelectedCount()
        toolbar.title = getString(R.string.selected_count, count)
    }

    override fun onBackPressed() {
        if (adapter.isSelectionMode) {
            exitSelectionMode()
        } else {
            super.onBackPressed()
        }
    }

    private fun refresh() {
        lifecycleScope.launch {
            allProjects = withContext(Dispatchers.IO) { repository.listProjects() }
            val groups = withContext(Dispatchers.IO) { repository.listGroups() }
            renderGroupChips(groups)
            applyCurrentFilter()
        }
    }

    private fun filteredProjects(): List<Project> {
        val filter = currentGroupFilter
        return if (filter == null) {
            allProjects
        } else if (filter == UNGROUPED_SPECIAL_KEY) {
            allProjects.filter { it.group.isBlank() }
        } else {
            allProjects.filter { it.group.equals(filter, ignoreCase = true) }
        }
    }

    private fun applyCurrentFilter() {
        val filtered = filteredProjects()
        adapter.submitProjects(filtered)
        emptyContainer.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        if (adapter.isSelectionMode) {
            updateSelectionTitle()
        }
    }

    private fun renderGroupChips(groups: List<String>) {
        chipGroup.removeAllViews()

        // 1. "全部" Chip
        val allChip = createFilterChip(getString(R.string.group_all), currentGroupFilter == null) {
            currentGroupFilter = null
            applyCurrentFilter()
        }
        chipGroup.addView(allChip)

        // 2. 各自定义及已有分组
        for (group in groups) {
            val isChecked = currentGroupFilter.equals(group, ignoreCase = true)
            val groupChip = createFilterChip(group, isChecked) {
                currentGroupFilter = group
                applyCurrentFilter()
            }
            chipGroup.addView(groupChip)
        }

        // 3. "未分组" Chip
        val ungroupedChecked = currentGroupFilter == UNGROUPED_SPECIAL_KEY
        val ungroupedChip = createFilterChip(getString(R.string.group_ungrouped), ungroupedChecked) {
            currentGroupFilter = UNGROUPED_SPECIAL_KEY
            applyCurrentFilter()
        }
        chipGroup.addView(ungroupedChip)

        // 4. "+ 新建分组" Action Chip
        val addGroupChip = Chip(this).apply {
            text = getString(R.string.new_group)
            chipIcon = ContextCompat.getDrawable(this@ProjectManagerActivity, R.drawable.create_new_folder_rounded)
            val foreground = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSecondaryContainer)
            chipIconTint = ColorStateList.valueOf(foreground)
            setTextColor(foreground)
            chipBackgroundColor = ColorStateList.valueOf(
                MaterialColors.getColor(this, com.google.android.material.R.attr.colorSecondaryContainer),
            )
            chipStrokeColor = ColorStateList.valueOf(
                MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant),
            )
            chipStrokeWidth = 2f
            isCheckable = false
            setOnClickListener { showCreateGroupDialog() }
        }
        chipGroup.addView(addGroupChip)
    }

    private fun createFilterChip(label: String, isChecked: Boolean, onClick: () -> Unit): Chip {
        return Chip(this).apply {
            text = label
            isCheckable = true
            this.isChecked = isChecked
            chipStrokeWidth = 1f
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            chipBackgroundColor = ColorStateList(states, intArrayOf(
                MaterialColors.getColor(this, com.google.android.material.R.attr.colorSecondaryContainer),
                MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainerLow),
            ))
            setTextColor(ColorStateList(states, intArrayOf(
                MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSecondaryContainer),
                MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant),
            )))
            chipStrokeColor = ColorStateList.valueOf(
                MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant),
            )
            setOnClickListener {
                onClick()
                // 保持当前分组被选中，颜色由 checked 状态自动更新。
                for (i in 0 until chipGroup.childCount) {
                    val child = chipGroup.getChildAt(i) as? Chip ?: continue
                    if (child.isCheckable) {
                        child.isChecked = child == this
                    }
                }
            }
        }
    }

    private fun showCreateGroupDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.group_name_hint)
            setSingleLine(true)
        }
        val container = LinearLayout(this).apply {
            setPadding(48, 16, 48, 0)
            addView(input, LinearLayout.LayoutParams(-1, -2))
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.new_group)
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank()) {
                    repository.addGroup(name)
                    currentGroupFilter = name
                    refresh()
                }
            }
            .show()
    }

    private fun returnProject(project: Project) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_PROJECT_ID, project.id))
        finish()
    }

    private fun openNewProject() {
        val defaultGroup = when (currentGroupFilter) {
            null, UNGROUPED_SPECIAL_KEY -> ""
            else -> currentGroupFilter ?: ""
        }
        newProjectLauncher.launch(
            Intent(this, NewProjectActivity::class.java)
                .putExtra(NewProjectActivity.EXTRA_GROUP, defaultGroup),
        )
    }

    private fun showProjectMenu(project: Project, anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, MENU_EXPORT_LOVE, 0, R.string.export_love_project)
            menu.add(0, MENU_SET_GROUP, 1, R.string.set_group)
            menu.add(0, MENU_RENAME, 2, R.string.rename)
            menu.add(0, MENU_DELETE, 3, R.string.delete)
            menu.add(0, MENU_MULTI_SELECT, 4, R.string.batch_actions)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    MENU_EXPORT_LOVE -> startLoveExport(project)
                    MENU_SET_GROUP -> showSingleProjectGroupDialog(project)
                    MENU_RENAME -> showRenameDialog(project)
                    MENU_DELETE -> confirmDelete(project)
                    MENU_MULTI_SELECT -> {
                        enterSelectionMode()
                        adapter.toggleSelection(project)
                        updateSelectionTitle()
                    }
                }
                true
            }
            show()
        }
    }

    private fun showSingleProjectGroupDialog(project: Project) {
        val groups = repository.listGroups()
        val options = mutableListOf(getString(R.string.group_ungrouped))
        options.addAll(groups)
        options.add(getString(R.string.new_group))

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.set_group)
            .setItems(options.toTypedArray()) { _, which ->
                when {
                    which == 0 -> {
                        repository.updateProjectGroup(project, "")
                        refresh()
                    }
                    which == options.size - 1 -> {
                        showCreateGroupForProjectDialog { newGroupName ->
                            repository.updateProjectGroup(project, newGroupName)
                            refresh()
                        }
                    }
                    else -> {
                        val target = groups[which - 1]
                        repository.updateProjectGroup(project, target)
                        refresh()
                    }
                }
            }
            .show()
    }

    private fun showBatchMoveDialog(projects: List<Project>) {
        val groups = repository.listGroups()
        val options = mutableListOf(getString(R.string.group_ungrouped))
        options.addAll(groups)
        options.add(getString(R.string.new_group))

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.move_to_group)
            .setItems(options.toTypedArray()) { _, which ->
                when {
                    which == 0 -> {
                        projects.forEach { repository.updateProjectGroup(it, "") }
                        exitSelectionMode()
                        refresh()
                    }
                    which == options.size - 1 -> {
                        showCreateGroupForProjectDialog { newGroupName ->
                            projects.forEach { repository.updateProjectGroup(it, newGroupName) }
                            exitSelectionMode()
                            refresh()
                        }
                    }
                    else -> {
                        val target = groups[which - 1]
                        projects.forEach { repository.updateProjectGroup(it, target) }
                        exitSelectionMode()
                        refresh()
                    }
                }
            }
            .show()
    }

    private fun showCreateGroupForProjectDialog(onCreated: (String) -> Unit) {
        val input = EditText(this).apply {
            hint = getString(R.string.group_name_hint)
            setSingleLine(true)
        }
        val container = LinearLayout(this).apply {
            setPadding(48, 16, 48, 0)
            addView(input, LinearLayout.LayoutParams(-1, -2))
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.new_group)
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank()) {
                    repository.addGroup(name)
                    onCreated(name)
                }
            }
            .show()
    }

    private fun confirmBatchDelete(projects: List<Project>) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.batch_delete)
            .setMessage(getString(R.string.batch_delete_confirm, projects.size))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        for (project in projects) {
                            runCatching { repository.deleteProject(project) }
                        }
                    }
                    exitSelectionMode()
                    refresh()
                    Toast.makeText(this@ProjectManagerActivity, getString(R.string.batch_delete_success, projects.size), Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun startBatchExport(projects: List<Project>) {
        pendingBatchExportProjects = projects
        batchExportTreeLauncher.launch(null)
    }

    private fun executeBatchExport(treeUri: Uri, projects: List<Project>) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val docFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(this@ProjectManagerActivity, treeUri)
                        ?: throw java.io.IOException("无法访问目标目录")
                    for (project in projects) {
                        if (!project.root.isDirectory) continue
                        val filename = "${project.id}.love"
                        val targetFile = docFile.createFile("application/zip", filename)
                            ?: throw java.io.IOException("无法创建文件 $filename")
                        contentResolver.openOutputStream(targetFile.uri, "w")?.use { output ->
                            LoveArchiveTransfer.export(project, output)
                        } ?: throw java.io.IOException("无法写入文件 $filename")
                    }
                }
                Toast.makeText(this@ProjectManagerActivity, R.string.batch_export_success, Toast.LENGTH_SHORT).show()
                exitSelectionMode()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                showError(getString(R.string.love_export_failed, error.message ?: error.javaClass.simpleName))
            }
        }
    }

    private fun startLoveExport(project: Project) {
        if (!project.root.isDirectory) {
            showError(getString(R.string.love_export_failed, "项目目录不存在"))
            return
        }
        pendingLoveExport = project
        loveExportLauncher.launch("${project.id}.love")
    }

    private fun importLoveProject(uri: Uri) {
        val targetGroup = when (currentGroupFilter) {
            null, UNGROUPED_SPECIAL_KEY -> ""
            else -> currentGroupFilter ?: ""
        }
        lifecycleScope.launch {
            try {
                val project = withContext(Dispatchers.IO) {
                    val name = contentResolver.query(
                        uri,
                        arrayOf(OpenableColumns.DISPLAY_NAME),
                        null,
                        null,
                        null,
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    } ?: uri.lastPathSegment ?: "imported.love"
                    val input = contentResolver.openInputStream(uri)
                        ?: throw java.io.IOException("无法读取压缩包")
                    input.use { repository.importLoveArchive(it, name, group = targetGroup) }
                }
                returnProject(project)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val detail = if (error.message?.contains("main.lua") == true) {
                    getString(R.string.love_archive_invalid)
                } else {
                    error.message ?: error.javaClass.simpleName
                }
                showError(getString(R.string.love_import_failed, detail))
            }
        }
    }

    private fun exportLoveProject(uri: Uri, project: Project) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val output = contentResolver.openOutputStream(uri, "w")
                        ?: throw java.io.IOException("无法打开导出文件")
                    output.use { LoveArchiveTransfer.export(project, it) }
                }
                Toast.makeText(this@ProjectManagerActivity, R.string.love_export_success, Toast.LENGTH_SHORT).show()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                showError(getString(R.string.love_export_failed, error.message ?: error.javaClass.simpleName))
            }
        }
    }

    private fun showRenameDialog(project: Project) {
        val form = formView(project.displayName, project.id, project.description, project.group)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename)
            .setView(form)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val values = form.tag as ProjectForm
                try {
                    repository.renameProject(
                        project,
                        values.name.text.toString(),
                        values.id.text.toString(),
                    )
                    val newGroup = values.group.text.toString().trim()
                    if (newGroup != project.group) {
                        repository.updateProjectGroup(project.copy(id = values.id.text.toString()), newGroup)
                    }
                    refresh()
                } catch (error: Exception) {
                    showError(error.message ?: "重命名失败")
                }
            }
            .show()
    }

    private fun confirmDelete(project: Project) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirm_delete)
            .setMessage("${project.displayName}\n${getString(R.string.delete_warning)}")
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                runCatching { repository.deleteProject(project) }
                    .onSuccess { refresh() }
                    .onFailure { showError(it.message ?: "删除失败") }
            }
            .show()
    }

    private fun formView(
        name: String = "",
        id: String = "",
        description: String = "",
        group: String = "",
    ): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 0, 48, 0)
        }
        val nameInput = EditText(this).apply {
            hint = getString(R.string.project_name)
            setSingleLine(true)
            setText(name)
        }
        val idInput = EditText(this).apply {
            hint = getString(R.string.project_id)
            setSingleLine(true)
            setText(id)
        }
        val descriptionInput = EditText(this).apply {
            hint = getString(R.string.project_description)
            setSingleLine(true)
            setText(description)
        }
        val groupInput = EditText(this).apply {
            hint = getString(R.string.project_group)
            setSingleLine(true)
            setText(group)
        }
        container.addView(nameInput, LinearLayout.LayoutParams(-1, -2))
        container.addView(idInput, LinearLayout.LayoutParams(-1, -2))
        container.addView(descriptionInput, LinearLayout.LayoutParams(-1, -2))
        container.addView(groupInput, LinearLayout.LayoutParams(-1, -2))
        container.tag = ProjectForm(nameInput, idInput, descriptionInput, groupInput)
        return container
    }

    private fun showError(message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("操作失败")
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private data class ProjectForm(
        val name: EditText,
        val id: EditText,
        val description: EditText,
        val group: EditText,
    )

    companion object {
        const val EXTRA_PROJECT_ID = "project_id"
        private const val MENU_EXPORT_LOVE = 3
        private const val MENU_SET_GROUP = 4
        private const val MENU_RENAME = 1
        private const val MENU_DELETE = 2
        private const val MENU_MULTI_SELECT = 5

        private const val UNGROUPED_SPECIAL_KEY = "__ungrouped__"
    }
}
