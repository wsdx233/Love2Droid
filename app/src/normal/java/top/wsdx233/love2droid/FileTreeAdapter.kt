package top.wsdx233.love2droid

import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import kotlin.math.abs

 data class TreeItem(
    val file: File,
    val relativePath: String,
    val depth: Int,
    val directory: Boolean,
    val childCount: Int,
)

class FileTreeAdapter(
    private val onClick: (TreeItem) -> Unit,
    private val onLongClick: (TreeItem, View) -> Unit,
    private val onSwipe: (TreeItem) -> Unit,
) : RecyclerView.Adapter<FileTreeAdapter.TreeHolder>() {
    private var items: List<TreeItem> = emptyList()
    private var selectedPaths: Set<String> = emptySet()

    fun submitItems(newItems: List<TreeItem>, selected: Set<String>) {
        items = newItems
        selectedPaths = selected
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): TreeHolder {
        val view = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_tree, parent, false)
        return TreeHolder(view)
    }

    override fun onBindViewHolder(holder: TreeHolder, position: Int) {
        holder.bind(items[position], selectedPaths.contains(items[position].relativePath))
    }

    override fun getItemCount(): Int = items.size

    inner class TreeHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = view.findViewById(R.id.tree_icon)
        private val name: TextView = view.findViewById(R.id.tree_name)
        private val summary: TextView = view.findViewById(R.id.tree_summary)
        private var downX = 0f
        private var downY = 0f

        fun bind(item: TreeItem, selected: Boolean) {
            val context = itemView.context
            icon.setImageDrawable(
                ContextCompat.getDrawable(
                    context,
                    if (item.directory) R.drawable.ic_folder else R.drawable.ic_file,
                ),
            )
            name.text = item.file.name
            summary.text = if (item.directory) {
                "${item.childCount} 项"
            } else {
                "${LanguageResolver.displayName(item.file)} · ${StorageUtils.formatBytes(item.file.length())}"
            }
            itemView.setPadding(item.depth * (context.resources.displayMetrics.density * 18).toInt(), 0, 8, 0)
            itemView.setBackgroundColor(
                ContextCompat.getColor(
                    context,
                    if (selected) R.color.tree_selected else android.R.color.transparent,
                ),
            )
            itemView.setOnClickListener { onClick(item) }
            itemView.setOnLongClickListener {
                onLongClick(item, itemView)
                true
            }
            itemView.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.x
                        downY = event.y
                        false
                    }
                    MotionEvent.ACTION_UP -> {
                        val dx = event.x - downX
                        val dy = event.y - downY
                        if (abs(dx) > 48 * context.resources.displayMetrics.density && abs(dx) > abs(dy) * 1.4f) {
                            onSwipe(item)
                            true
                        } else {
                            false
                        }
                    }
                    else -> false
                }
            }
        }
    }
}
