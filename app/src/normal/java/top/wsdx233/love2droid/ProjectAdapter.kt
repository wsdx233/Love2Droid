package top.wsdx233.love2droid

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.DateFormat
import java.util.Date

class ProjectAdapter(
    private val onClick: (Project) -> Unit,
    private val onLongClick: (Project, View) -> Unit,
) : RecyclerView.Adapter<ProjectAdapter.ProjectHolder>() {
    private var projects: List<Project> = emptyList()

    fun submitProjects(value: List<Project>) {
        projects = value
        notifyDataSetChanged()
    }

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
        private val name: TextView = view.findViewById(R.id.project_name)
        private val summary: TextView = view.findViewById(R.id.project_summary)

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
            itemView.setOnClickListener { onClick(project) }
            itemView.setOnLongClickListener {
                onLongClick(project, itemView)
                true
            }
        }
    }
}
