package top.wsdx233.love2droid

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors

internal class GitStatusAdapter(
    private val onDiff: (GitStatusEntry, Boolean) -> Unit,
    private val onStage: (GitStatusEntry) -> Unit,
    private val onUnstage: (GitStatusEntry) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private sealed interface Item {
        data class Section(val titleRes: Int, val count: Int) : Item
        data class File(val entry: GitStatusEntry, val staged: Boolean) : Item
    }

    private var items: List<Item> = emptyList()

    fun submitEntries(value: List<GitStatusEntry>) {
        val staged = value.filter(GitStatusEntry::isStaged)
        val changes = value.filter(GitStatusEntry::hasWorkTreeChanges)
        items = buildList {
            if (staged.isNotEmpty()) {
                add(Item.Section(R.string.git_staged_section, staged.size))
                staged.forEach { add(Item.File(it, staged = true)) }
            }
            if (changes.isNotEmpty()) {
                add(Item.Section(R.string.git_changes_section, changes.size))
                changes.forEach { add(Item.File(it, staged = false)) }
            }
        }
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is Item.Section -> VIEW_TYPE_SECTION
        is Item.File -> VIEW_TYPE_FILE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = when (viewType) {
        VIEW_TYPE_SECTION -> SectionHolder(createSectionView(parent))
        else -> FileHolder(createFileView(parent))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is Item.Section -> (holder as SectionHolder).bind(item)
            is Item.File -> (holder as FileHolder).bind(item)
        }
    }

    override fun getItemCount(): Int = items.size

    private inner class SectionHolder(private val title: TextView) : RecyclerView.ViewHolder(title) {
        fun bind(item: Item.Section) {
            title.text = itemView.context.getString(item.titleRes, item.count)
        }
    }

    private inner class FileHolder(private val views: GitFileRowViews) : RecyclerView.ViewHolder(views.root) {
        fun bind(item: Item.File) {
            val entry = item.entry
            val context = itemView.context
            views.marker.text = if (entry.isUntracked) "?" else {
                if (item.staged) entry.indexStatus else entry.workTreeStatus
            }.toString()
            views.marker.setTextColor(
                if (item.staged) Color.rgb(46, 125, 50) else Color.rgb(198, 40, 40),
            )
            views.title.text = entry.relativePath
            views.detail.text = statusLabel(context, entry, item.staged)
            views.root.setOnClickListener { onDiff(entry, item.staged) }
            views.diffButton.setOnClickListener { onDiff(entry, item.staged) }
            views.actionButton.setImageResource(if (item.staged) R.drawable.ic_arrow_up else R.drawable.ic_arrow_down)
            views.actionButton.contentDescription = context.getString(
                if (item.staged) R.string.git_unstage else R.string.git_stage,
            )
            views.actionButton.setOnClickListener {
                if (item.staged) onUnstage(entry) else onStage(entry)
            }
        }
    }

    private fun statusLabel(context: android.content.Context, entry: GitStatusEntry, staged: Boolean): String {
        val status = if (staged) entry.indexStatus else entry.workTreeStatus
        val label = if (status == '?' || entry.isUntracked) {
            context.getString(R.string.git_untracked)
        } else {
            status.toGitStatusName(context)
        }
        val original = entry.originalPath?.let { context.getString(R.string.git_renamed_from, it) }.orEmpty()
        return listOf(label, original).filter(String::isNotBlank).joinToString(" · ")
    }

    private companion object {
        const val VIEW_TYPE_SECTION = 0
        const val VIEW_TYPE_FILE = 1
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

internal class GitDiffAdapter : RecyclerView.Adapter<GitDiffAdapter.Holder>() {
    private var lines: List<GitDiffLine> = emptyList()

    fun submitText(value: String) {
        lines = GitDiffParser.parse(value)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(createDiffRow(parent))
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(lines[position])
    override fun getItemCount(): Int = lines.size

    internal class Holder(private val views: GitDiffRowViews) : RecyclerView.ViewHolder(views.root) {
        fun bind(line: GitDiffLine) {
            val context = itemView.context
            views.gutter.text = buildString {
                append(line.oldLineNumber?.toString().orEmpty().padStart(LINE_NUMBER_WIDTH))
                append(' ')
                append(line.newLineNumber?.toString().orEmpty().padStart(LINE_NUMBER_WIDTH))
            }
            views.content.text = line.text.ifEmpty { " " }
            val base = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurface, Color.WHITE)
            val foreground = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
            when (line.kind) {
                GitDiffLineKind.ADDITION -> {
                    views.root.setBackgroundColor(blend(base, Color.rgb(46, 125, 50), 0.20f))
                    views.gutter.setTextColor(Color.rgb(46, 125, 50))
                    views.content.setTextColor(foreground)
                }
                GitDiffLineKind.REMOVAL -> {
                    views.root.setBackgroundColor(blend(base, Color.rgb(198, 40, 40), 0.20f))
                    views.gutter.setTextColor(Color.rgb(198, 40, 40))
                    views.content.setTextColor(foreground)
                }
                GitDiffLineKind.HUNK -> {
                    views.root.setBackgroundColor(blend(base, MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, Color.BLUE), 0.14f))
                    views.gutter.setTextColor(MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, Color.BLUE))
                    views.content.setTextColor(foreground)
                }
                GitDiffLineKind.CONTEXT, GitDiffLineKind.METADATA -> {
                    views.root.setBackgroundColor(base)
                    views.gutter.setTextColor(MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY))
                    views.content.setTextColor(
                        if (line.kind == GitDiffLineKind.METADATA) {
                            MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY)
                        } else {
                            foreground
                        },
                    )
                }
            }
        }

        private fun blend(base: Int, overlay: Int, amount: Float): Int {
            val inverse = 1f - amount
            return Color.rgb(
                (Color.red(base) * inverse + Color.red(overlay) * amount).toInt(),
                (Color.green(base) * inverse + Color.green(overlay) * amount).toInt(),
                (Color.blue(base) * inverse + Color.blue(overlay) * amount).toInt(),
            )
        }

        private companion object {
            const val LINE_NUMBER_WIDTH = 4
        }
    }
}

private data class GitFileRowViews(
    val root: LinearLayout,
    val marker: TextView,
    val title: TextView,
    val detail: TextView,
    val diffButton: ImageButton,
    val actionButton: ImageButton,
)

internal data class GitDiffRowViews(
    val root: LinearLayout,
    val gutter: TextView,
    val content: TextView,
)

private data class GitRowViews(
    val root: LinearLayout,
    val graph: TextView?,
    val title: TextView,
    val detail: TextView,
)

private fun createSectionView(parent: ViewGroup): TextView {
    val context = parent.context
    val density = context.resources.displayMetrics.density
    return TextView(context).apply {
        setTypeface(typeface, Typeface.BOLD)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY))
        setBackgroundColor(MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurfaceVariant, Color.LTGRAY))
        setPadding((20 * density).toInt(), (9 * density).toInt(), (16 * density).toInt(), (9 * density).toInt())
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}

private fun createFileView(parent: ViewGroup): GitFileRowViews {
    val context = parent.context
    val density = context.resources.displayMetrics.density
    val root = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        minimumHeight = (60 * density).toInt()
        setPadding((12 * density).toInt(), (7 * density).toInt(), (8 * density).toInt(), (7 * density).toInt())
        background = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).let {
            val value = it.getDrawable(0)
            it.recycle()
            value
        }
        isClickable = true
        isFocusable = true
    }
    val marker = TextView(context).apply {
        typeface = Typeface.MONOSPACE
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        gravity = android.view.Gravity.CENTER
    }
    root.addView(marker, LinearLayout.LayoutParams((28 * density).toInt(), ViewGroup.LayoutParams.MATCH_PARENT))
    val labels = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    val title = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.MIDDLE
    }
    val detail = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY))
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    labels.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    labels.addView(detail, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    root.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    val diffButton = createIconButton(parent, R.drawable.ic_chevron_right, R.string.git_view_diff)
    val actionButton = createIconButton(parent, R.drawable.ic_arrow_down, R.string.git_stage)
    root.addView(diffButton, LinearLayout.LayoutParams((44 * density).toInt(), (44 * density).toInt()))
    root.addView(actionButton, LinearLayout.LayoutParams((44 * density).toInt(), (44 * density).toInt()))
    return GitFileRowViews(root, marker, title, detail, diffButton, actionButton)
}

private fun createDiffRow(parent: ViewGroup): GitDiffRowViews {
    val context = parent.context
    val density = context.resources.displayMetrics.density
    val root = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.TOP
        setPadding(0, (1 * density).toInt(), 0, (1 * density).toInt())
    }
    val gutter = TextView(context).apply {
        typeface = Typeface.MONOSPACE
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        gravity = android.view.Gravity.TOP or android.view.Gravity.END
        setPadding((4 * density).toInt(), (3 * density).toInt(), (6 * density).toInt(), (3 * density).toInt())
    }
    val content = TextView(context).apply {
        typeface = Typeface.MONOSPACE
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding((8 * density).toInt(), (3 * density).toInt(), (8 * density).toInt(), (3 * density).toInt())
        setTextIsSelectable(true)
    }
    root.addView(gutter, LinearLayout.LayoutParams((70 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
    root.addView(content, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    return GitDiffRowViews(root, gutter, content)
}

private fun createIconButton(parent: ViewGroup, iconRes: Int, descriptionRes: Int): ImageButton {
    val context = parent.context
    return ImageButton(context).apply {
        setImageResource(iconRes)
        imageTintList = ColorStateList.valueOf(
            MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY),
        )
        contentDescription = context.getString(descriptionRes)
        setPadding(0, 0, 0, 0)
        background = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless)).let {
            val value = it.getDrawable(0)
            it.recycle()
            value
        }
        isFocusable = true
        isClickable = true
    }
}

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
