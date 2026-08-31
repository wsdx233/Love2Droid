package top.wsdx233.love2droid

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProjectManagerActivity : AppCompatActivity() {
    private lateinit var repository: ProjectRepository
    private lateinit var adapter: ProjectAdapter
    private lateinit var empty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_project_manager)
        repository = ProjectRepository(this)
        empty = findViewById(R.id.project_empty)

        val root = findViewById<View>(R.id.project_root)
        val topInset = findViewById<View>(R.id.project_top_inset)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            topInset.layoutParams = topInset.layoutParams.apply { height = systemBars.top }
            root.setPadding(root.paddingLeft, root.paddingTop, root.paddingRight, systemBars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)

        findViewById<MaterialToolbar>(R.id.project_toolbar).apply {
            navigationIcon = ContextCompat.getDrawable(this@ProjectManagerActivity, R.drawable.ic_arrow_back)
            setNavigationOnClickListener { finish() }
        }
        adapter = ProjectAdapter(
            onClick = ::returnProject,
            onLongClick = ::showProjectMenu,
        )
        findViewById<RecyclerView>(R.id.project_list).apply {
            layoutManager = LinearLayoutManager(this@ProjectManagerActivity)
            adapter = this@ProjectManagerActivity.adapter
        }
        findViewById<View>(R.id.new_project_button).setOnClickListener { showCreateDialog() }
        refresh()
    }


    private fun refresh() {
        lifecycleScope.launch {
            val projects = withContext(Dispatchers.IO) { repository.listProjects() }
            adapter.submitProjects(projects)
            empty.visibility = if (projects.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun returnProject(project: Project) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_PROJECT_ID, project.id))
        finish()
    }

    private fun showCreateDialog() {
        val form = formView()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.new_project)
            .setView(form)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val values = form.tag as ProjectForm
                try {
                    val project = repository.createProject(
                        values.name.text.toString(),
                        values.id.text.toString(),
                        values.description.text.toString(),
                    )
                    returnProject(project)
                } catch (error: Exception) {
                    showError(error.message ?: "创建项目失败")
                }
            }
            .show()
    }

    private fun showProjectMenu(project: Project, anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, MENU_RENAME, 0, R.string.rename)
            menu.add(0, MENU_DELETE, 1, R.string.delete)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    MENU_RENAME -> showRenameDialog(project)
                    MENU_DELETE -> confirmDelete(project)
                }
                true
            }
            show()
        }
    }

    private fun showRenameDialog(project: Project) {
        val form = formView(project.displayName, project.id, project.description)
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
        container.addView(nameInput, LinearLayout.LayoutParams(-1, -2))
        container.addView(idInput, LinearLayout.LayoutParams(-1, -2))
        container.addView(descriptionInput, LinearLayout.LayoutParams(-1, -2))
        container.tag = ProjectForm(nameInput, idInput, descriptionInput)
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
    )

    companion object {
        const val EXTRA_PROJECT_ID = "project_id"
        private const val MENU_RENAME = 1
        private const val MENU_DELETE = 2
    }
}
