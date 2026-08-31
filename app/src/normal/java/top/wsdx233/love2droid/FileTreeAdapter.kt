package top.wsdx233.love2droid

import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import kotlin.math.abs
import kotlin.math.sign

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
        private val density = view.resources.displayMetrics.density
        private val touchSlop = ViewConfiguration.get(view.context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f

        private var swiping = false
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
                val dx = event.x - downX
                val dy = event.y - downY
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        itemView.animate().cancel()
                        itemView.translationX = 0f
                        downX = event.x
                        downY = event.y
                        swiping = false
                        itemView.parent.requestDisallowInterceptTouchEvent(true)
                        false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!swiping &&
                            abs(dx) > touchSlop &&
                            isHorizontalSwipe(dx, dy)
                        ) {
                            swiping = true
                            itemView.cancelLongPress()
                            itemView.isPressed = false
                            itemView.parent.requestDisallowInterceptTouchEvent(true)
                        }
                        if (swiping) {
                            val limit = MAX_SWIPE_OFFSET_DP * density
                            val resistedOffset = abs(dx).coerceAtMost(limit) * dx.sign
                            itemView.translationX = resistedOffset
                            true
                        } else {
                            if (abs(dy) > touchSlop) {
                                itemView.parent.requestDisallowInterceptTouchEvent(false)
                            }
                            false
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        val completed = swiping &&
                            abs(dx) >= SWIPE_TRIGGER_DP * density &&
                            isHorizontalSwipe(dx, dy)
                        val handled = swiping
                        finishSwipe()
                        if (completed) onSwipe(item)
                        handled
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        val handled = swiping
                        finishSwipe()
                        handled
                    }
                    else -> false
                }
            }
        }

        private fun isHorizontalSwipe(dx: Float, dy: Float): Boolean {
            return abs(dx) > abs(dy) * HORIZONTAL_DRIFT_RATIO
        }

        private fun finishSwipe() {
            swiping = false
            itemView.parent?.requestDisallowInterceptTouchEvent(false)
            itemView.isPressed = false
            itemView.animate()
                .translationX(0f)
                .setDuration(SWIPE_RETURN_DURATION_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    override fun onViewRecycled(holder: TreeHolder) {
        holder.itemView.animate().cancel()
        holder.itemView.translationX = 0f
        super.onViewRecycled(holder)
    }

    private companion object {
        const val SWIPE_TRIGGER_DP = 48f
        const val MAX_SWIPE_OFFSET_DP = 64f
        const val HORIZONTAL_DRIFT_RATIO = 0.7f
        const val SWIPE_RETURN_DURATION_MS = 90L
    }
}
