package top.wsdx233.love2droid

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.MaterialColors
import java.io.File
import java.text.DateFormat
import java.util.Date

class ProjectAdapter(
    private val iconProvider: (Project) -> File?,
    private val onClick: (Project) -> Unit,
    private val onLongClick: (Project, View) -> Unit,
) : RecyclerView.Adapter<ProjectAdapter.ProjectHolder>() {
    private var projects: List<Project> = emptyList()
    private val selectedIds = mutableSetOf<String>()
    var isSelectionMode: Boolean = false
        private set

    fun submitProjects(value: List<Project>) {
        projects = value
        // 移除不再存在的已选项
        val currentIds = value.map { it.id }.toSet()
        selectedIds.retainAll(currentIds)
        notifyDataSetChanged()
    }

    fun setSelectionMode(enabled: Boolean) {
        if (isSelectionMode != enabled) {
            isSelectionMode = enabled
            if (!enabled) {
                selectedIds.clear()
            }
            notifyDataSetChanged()
        }
    }

    fun toggleSelection(project: Project) {
        if (selectedIds.contains(project.id)) {
            selectedIds.remove(project.id)
        } else {
            selectedIds.add(project.id)
        }
        notifyDataSetChanged()
    }

    fun selectAll() {
        selectedIds.clear()
        selectedIds.addAll(projects.map { it.id })
        notifyDataSetChanged()
    }

    fun clearSelection() {
        selectedIds.clear()
        notifyDataSetChanged()
    }

    fun getSelectedProjects(): List<Project> {
        return projects.filter { selectedIds.contains(it.id) }
    }

    fun getSelectedCount(): Int = selectedIds.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProjectHolder {
        return ProjectHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_project, parent, false),
        )
    }

    override fun onBindViewHolder(holder: ProjectHolder, position: Int) {
        holder.bind(projects[position])
    }

    override fun getItemCount(): Int = projects.size

    inner class ProjectHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val card: MaterialCardView = view.findViewById(R.id.project_card)
        private val icon: ImageView = view.findViewById(R.id.project_icon)
        private val name: TextView = view.findViewById(R.id.project_name)
        private val summary: TextView = view.findViewById(R.id.project_summary)
        private val groupBadge: TextView = view.findViewById(R.id.project_group_badge)
        private val checkBox: MaterialCheckBox = view.findViewById(R.id.project_checkbox)
        private val surfaceColor = MaterialColors.getColor(view, com.google.android.material.R.attr.colorSurface)
        private val selectedColor = MaterialColors.getColor(view, com.google.android.material.R.attr.colorSecondaryContainer)
        private val outlineColor = MaterialColors.getColor(view, com.google.android.material.R.attr.colorOutlineVariant)
        private val selectedOutlineColor = MaterialColors.getColor(view, androidx.appcompat.R.attr.colorPrimary)

        fun bind(project: Project) {
            name.text = project.displayName
            summary.text = buildString {
                if (project.description.isNotBlank()) {
                    append(project.description)
                    append(" · ")
                }
                append(project.id)
                if (project.lastOpened > 0L) {
                    append(" · ")
                    append(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(project.lastOpened)))
                }
            }

            // 分组角标展示
            if (project.group.isNotBlank()) {
                groupBadge.visibility = View.VISIBLE
                groupBadge.text = project.group
            } else {
                groupBadge.visibility = View.GONE
            }

            // 加载项目图标
            val iconFile = iconProvider(project)
            if (iconFile != null && iconFile.exists()) {
                val bitmap = BitmapFactory.decodeFile(iconFile.absolutePath)
                if (bitmap != null) {
                    icon.setImageBitmap(bitmap)
                    icon.scaleType = ImageView.ScaleType.CENTER_CROP
                } else {
                    icon.setImageResource(R.drawable.folder_rounded)
                    icon.scaleType = ImageView.ScaleType.CENTER_INSIDE
                }
            } else {
                icon.setImageResource(R.drawable.folder_rounded)
                icon.scaleType = ImageView.ScaleType.CENTER_INSIDE
            }

            // 多选状态
            val isSelected = isSelectionMode && selectedIds.contains(project.id)
            checkBox.visibility = if (isSelectionMode) View.VISIBLE else View.GONE
            checkBox.isChecked = isSelected
            card.strokeColor = if (isSelected) selectedOutlineColor else outlineColor
            card.setCardBackgroundColor(if (isSelected) selectedColor else surfaceColor)

            itemView.setOnClickListener {
                if (isSelectionMode) {
                    toggleSelection(project)
                }
                onClick(project)
            }
            itemView.setOnLongClickListener {
                onLongClick(project, itemView)
                true
            }
        }
    }
}
