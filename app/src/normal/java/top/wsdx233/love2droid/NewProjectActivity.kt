package top.wsdx233.love2droid

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NewProjectActivity : AppCompatActivity() {
    private lateinit var templateCards: Map<ProjectTemplate, MaterialCardView>
    private lateinit var nameField: TextInputLayout
    private lateinit var idField: TextInputLayout
    private lateinit var descriptionField: TextInputLayout
    private lateinit var groupField: TextInputLayout
    private lateinit var createButton: MaterialButton
    private lateinit var progress: View
    private lateinit var errorMessage: TextView
    private var selectedTemplate = ProjectTemplate.BASIC
    private var creating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_new_project)

        val root = findViewById<View>(R.id.new_project_root)
        val topInset = findViewById<View>(R.id.new_project_top_inset)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()).bottom
            topInset.layoutParams = topInset.layoutParams.apply { height = bars.top }
            root.setPadding(bars.left, 0, bars.right, bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        findViewById<MaterialToolbar>(R.id.new_project_toolbar).apply {
            setNavigationIcon(R.drawable.ic_arrow_back)
            setNavigationContentDescription(R.string.back)
            setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
        onBackPressedDispatcher.addCallback(this) {
            if (!creating) finish()
        }

        nameField = findViewById(R.id.new_project_name_field)
        idField = findViewById(R.id.new_project_id_field)
        descriptionField = findViewById(R.id.new_project_description_field)
        groupField = findViewById(R.id.new_project_group_field)
        createButton = findViewById(R.id.new_project_create)
        progress = findViewById(R.id.new_project_progress)
        errorMessage = findViewById(R.id.new_project_error)
        templateCards = mapOf(
            ProjectTemplate.EMPTY to findViewById(R.id.template_empty_card),
            ProjectTemplate.BASIC to findViewById(R.id.template_basic_card),
        )
        for ((template, card) in templateCards) {
            card.setOnClickListener { selectTemplate(template) }
        }
        val restoredTemplate = savedInstanceState?.getString(STATE_TEMPLATE)
        selectTemplate(ProjectTemplate.entries.firstOrNull { it.name == restoredTemplate } ?: ProjectTemplate.BASIC)
        if (savedInstanceState == null) {
            findViewById<TextInputEditText>(R.id.new_project_group).setText(intent.getStringExtra(EXTRA_GROUP).orEmpty())
        }
        createButton.setOnClickListener { createProject() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_TEMPLATE, selectedTemplate.name)
        super.onSaveInstanceState(outState)
    }

    private fun selectTemplate(template: ProjectTemplate) {
        selectedTemplate = template
        for ((option, card) in templateCards) {
            val selected = option == template
            card.isChecked = selected
            card.strokeColor = MaterialColors.getColor(
                card,
                if (selected) androidx.appcompat.R.attr.colorPrimary
                else com.google.android.material.R.attr.colorOutlineVariant,
            )
            card.strokeWidth = ((if (selected) 2 else 1) * resources.displayMetrics.density).toInt()
        }
    }

    private fun createProject() {
        if (creating) return
        val name = nameField.editText!!.text.toString().trim()
        val id = idField.editText!!.text.toString().trim()
        nameField.error = if (name.isEmpty()) getString(R.string.project_name_required) else null
        idField.error = if (id.isNotEmpty() && !ProjectRepository.isSafeProjectId(id)) {
            getString(R.string.project_id_invalid)
        } else null
        errorMessage.visibility = View.GONE
        if (nameField.error != null) {
            nameField.editText!!.requestFocus()
            return
        }
        if (idField.error != null) {
            idField.editText!!.requestFocus()
            return
        }
        val description = descriptionField.editText!!.text.toString()
        val group = groupField.editText!!.text.toString().trim()
        val template = selectedTemplate
        WindowCompat.getInsetsController(window, createButton).hide(WindowInsetsCompat.Type.ime())
        setCreating(true)
        lifecycleScope.launch {
            try {
                val project = withContext(Dispatchers.IO) {
                    ProjectRepository(this@NewProjectActivity).createProject(name, id, description, template, group)
                }
                setResult(RESULT_OK, Intent().putExtra(ProjectManagerActivity.EXTRA_PROJECT_ID, project.id))
                finish()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                errorMessage.text = getString(
                    R.string.project_create_failed,
                    error.message ?: error.javaClass.simpleName,
                )
                errorMessage.visibility = View.VISIBLE
            } finally {
                setCreating(false)
            }
        }
    }

    private fun setCreating(value: Boolean) {
        creating = value
        createButton.isEnabled = !value
        createButton.setText(if (value) R.string.project_creating else R.string.create_project)
        progress.visibility = if (value) View.VISIBLE else View.INVISIBLE
        templateCards.values.forEach { it.isEnabled = !value }
        nameField.isEnabled = !value
        idField.isEnabled = !value
        descriptionField.isEnabled = !value
        groupField.isEnabled = !value
    }

    companion object {
        const val EXTRA_GROUP = "group"
        private const val STATE_TEMPLATE = "project_template"
    }
}
