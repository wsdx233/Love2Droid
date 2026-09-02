package top.wsdx233.love2droid

import android.graphics.Color
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors

internal class ProjectSearchAdapter(
    private val onClick: (ProjectSearchResult) -> Unit,
) : RecyclerView.Adapter<ProjectSearchAdapter.Holder>() {
    private var items: List<ProjectSearchResult> = emptyList()

    fun submitItems(updated: List<ProjectSearchResult>) {
        val previous = items
        items = updated
        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = previous.size
            override fun getNewListSize(): Int = updated.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val old = previous[oldItemPosition]
                val new = updated[newItemPosition]
                return old.mode == new.mode && old.relativePath == new.relativePath &&
                    old.target.startLine == new.target.startLine && old.target.startColumn == new.target.startColumn
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                previous[oldItemPosition] == updated[newItemPosition]
        }).dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val context = parent.context
        val density = context.resources.displayMetrics.density
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            minimumHeight = (72 * density).toInt()
            setPadding((20 * density).toInt(), (10 * density).toInt(), (20 * density).toInt(), (10 * density).toInt())
            background = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).let {
                val value = it.getDrawable(0)
                it.recycle()
                value
            }
            isClickable = true
            isFocusable = true
        }
        val title = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val path = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        val preview = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        row.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(path, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return Holder(row, title, path, preview)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    internal inner class Holder(
        itemView: View,
        private val title: TextView,
        private val path: TextView,
        private val preview: TextView,
    ) : RecyclerView.ViewHolder(itemView) {
        fun bind(item: ProjectSearchResult) {
            title.text = item.title
            path.text = if (item.mode == ProjectSearchMode.FILES) {
                item.relativePath
            } else {
                itemView.context.getString(
                    R.string.project_search_result_location,
                    item.relativePath,
                    item.target.startLine + 1,
                )
            }
            preview.text = when (item.symbolSource) {
                ProjectSymbolSource.SEMANTIC -> itemView.context.getString(R.string.symbol_source_semantic, item.preview)
                ProjectSymbolSource.LOCAL -> itemView.context.getString(R.string.symbol_source_local, item.preview)
                null -> item.preview
            }
            preview.visibility = if (preview.text.isNullOrBlank() || preview.text == path.text) View.GONE else View.VISIBLE
            itemView.setOnClickListener { onClick(item) }
        }
    }
}
