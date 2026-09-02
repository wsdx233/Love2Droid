package top.wsdx233.love2droid

import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors

internal class GitStatusAdapter(
    private val onClick: (GitStatusEntry) -> Unit,
) : RecyclerView.Adapter<GitStatusAdapter.Holder>() {
    private var entries: List<GitStatusEntry> = emptyList()

    fun submitEntries(value: List<GitStatusEntry>) {
        entries = value
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val views = createGitRow(parent)
        return Holder(views.root, views.title, views.detail)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(entries[position])
    override fun getItemCount(): Int = entries.size

    internal inner class Holder(
        itemView: View,
        private val title: TextView,
        private val detail: TextView,
    ) : RecyclerView.ViewHolder(itemView) {
        fun bind(entry: GitStatusEntry) {
            title.text = entry.relativePath
            detail.text = itemView.context.getString(
                R.string.git_status_detail,
                statusLabel(entry),
                entry.originalPath?.let { itemView.context.getString(R.string.git_renamed_from, it) }.orEmpty(),
            ).trim()
            itemView.setOnClickListener { onClick(entry) }
        }

        private fun statusLabel(entry: GitStatusEntry): String {
            val context = itemView.context
            if (entry.indexStatus == '?' && entry.workTreeStatus == '?') return context.getString(R.string.git_untracked)
            val labels = buildList {
                if (entry.indexStatus != ' ') add(context.getString(R.string.git_staged_status, entry.indexStatus.toGitStatusName(context)))
                if (entry.workTreeStatus != ' ') add(context.getString(R.string.git_unstaged_status, entry.workTreeStatus.toGitStatusName(context)))
            }
            return labels.joinToString(" · ")
        }
    }
}

internal class GitHistoryAdapter(
    private val onClick: (GitCommitEntry) -> Unit,
) : RecyclerView.Adapter<GitHistoryAdapter.Holder>() {
    private var entries: List<GitCommitEntry> = emptyList()

    fun submitEntries(value: List<GitCommitEntry>) {
        entries = value
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val views = createGitRow(parent, withGraph = true)
        return Holder(views.root, views.graph, views.title, views.detail)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(entries[position])
    override fun getItemCount(): Int = entries.size

    internal inner class Holder(
        itemView: View,
        private val graph: TextView?,
        private val title: TextView,
        private val detail: TextView,
    ) : RecyclerView.ViewHolder(itemView) {
        fun bind(entry: GitCommitEntry) {
            graph?.text = entry.graph.ifBlank { "•" }
            title.text = entry.subject
            val relativeTime = DateUtils.getRelativeTimeSpanString(
                entry.timestampSeconds * 1000L,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
            )
            detail.text = itemView.context.getString(
                R.string.git_commit_summary,
                entry.hash.take(8),
                entry.author,
                relativeTime,
            )
            itemView.setOnClickListener { onClick(entry) }
        }
    }
}

private data class GitRowViews(
    val root: LinearLayout,
    val graph: TextView?,
    val title: TextView,
    val detail: TextView,
)

private fun createGitRow(parent: ViewGroup, withGraph: Boolean = false): GitRowViews {
    val context = parent.context
    val density = context.resources.displayMetrics.density
    val root = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        minimumHeight = (68 * density).toInt()
        setPadding((16 * density).toInt(), (10 * density).toInt(), (16 * density).toInt(), (10 * density).toInt())
        background = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).let {
            val value = it.getDrawable(0)
            it.recycle()
            value
        }
        isClickable = true
        isFocusable = true
    }
    val graph = if (withGraph) TextView(context).apply {
        typeface = Typeface.MONOSPACE
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        gravity = android.view.Gravity.CENTER_VERTICAL
        maxLines = 1
        setTextColor(MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, Color.BLUE))
    } else null
    graph?.let { root.addView(it, LinearLayout.LayoutParams((54 * density).toInt(), ViewGroup.LayoutParams.MATCH_PARENT)) }
    val labels = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    val title = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    val detail = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY))
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }
    labels.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    labels.addView(detail, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    root.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    return GitRowViews(root, graph, title, detail)
}

private fun Char.toGitStatusName(context: android.content.Context): String = when (this) {
    'M' -> context.getString(R.string.git_modified)
    'A' -> context.getString(R.string.git_added)
    'D' -> context.getString(R.string.git_deleted)
    'R' -> context.getString(R.string.git_renamed)
    'C' -> context.getString(R.string.git_copied)
    'U' -> context.getString(R.string.git_conflicted)
    '?' -> context.getString(R.string.git_untracked)
    else -> toString()
}
