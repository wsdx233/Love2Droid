package top.wsdx233.love2droid

import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import kotlin.math.abs
import kotlin.math.sign

data class BrowserItem(
    val file: File,
    val relativePath: String,
    val directory: Boolean,
    val childCount: Int,
    val parentNavigation: Boolean = false,
)

class FileBrowserAdapter(
    private val onClick: (BrowserItem) -> Unit,
    private val onLongClick: (BrowserItem, View) -> Unit,
    private val onSwipe: (BrowserItem) -> Unit,
) : RecyclerView.Adapter<FileBrowserAdapter.BrowserHolder>() {
    private var items: List<BrowserItem> = emptyList()
    private var selectedPaths: Set<String> = emptySet()

    fun submitItems(newItems: List<BrowserItem>, selected: Set<String>) {
        val oldItems = items
        val oldSelected = selectedPaths
        items = newItems.toList()
        selectedPaths = selected.toSet()
        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldItems.size
            override fun getNewListSize(): Int = items.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return oldItems[oldItemPosition].relativePath == items[newItemPosition].relativePath
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return oldItems[oldItemPosition] == items[newItemPosition]
            }
        }).dispatchUpdatesTo(this)
        items.forEachIndexed { index, item ->
            if (oldSelected.contains(item.relativePath) != selectedPaths.contains(item.relativePath) &&
                oldItems.any { it.relativePath == item.relativePath }
            ) {
                notifyItemChanged(index)
            }
        }
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): BrowserHolder {
        val view = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_browser, parent, false)
        return BrowserHolder(view)
    }

    override fun onBindViewHolder(holder: BrowserHolder, position: Int) {
        holder.bind(items[position], selectedPaths.contains(items[position].relativePath))
    }

    override fun getItemCount(): Int = items.size

    inner class BrowserHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = view.findViewById(R.id.browser_icon)
        private val name: TextView = view.findViewById(R.id.browser_name)
        private val summary: TextView = view.findViewById(R.id.browser_summary)
        private val density = view.resources.displayMetrics.density
        private val touchSlop = ViewConfiguration.get(view.context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f

        private var swiping = false
        fun bind(item: BrowserItem, selected: Boolean) {
            val context = itemView.context
            icon.setImageDrawable(
                ContextCompat.getDrawable(
                    context,
                    when {
                        item.parentNavigation -> R.drawable.ic_arrow_back
                        item.directory -> R.drawable.ic_folder
                        else -> R.drawable.ic_file
                    },
                ),
            )
            name.text = if (item.parentNavigation) ".." else item.file.name
            summary.text = when {
                item.parentNavigation -> context.getString(R.string.parent_directory)
                item.directory -> "${item.childCount} 项"
                else -> "${LanguageResolver.displayName(item.file)} · ${StorageUtils.formatBytes(item.file.length())}"
            }
            itemView.setBackgroundColor(
                ContextCompat.getColor(
                    context,
                    if (selected) R.color.browser_selected else android.R.color.transparent,
                ),
            )
            itemView.setOnClickListener { onClick(item) }
            if (item.parentNavigation) {
                itemView.setOnLongClickListener(null)
                itemView.setOnTouchListener(null)
            } else {
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
                            itemView.parent?.requestDisallowInterceptTouchEvent(true)
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
                                itemView.parent?.requestDisallowInterceptTouchEvent(true)
                            }
                            if (swiping) {
                                val limit = MAX_SWIPE_OFFSET_DP * density
                                val resistedOffset = abs(dx).coerceAtMost(limit) * dx.sign
                                itemView.translationX = resistedOffset
                                true
                            } else {
                                if (abs(dy) > touchSlop) {
                                    itemView.parent?.requestDisallowInterceptTouchEvent(false)
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

    override fun onViewRecycled(holder: BrowserHolder) {
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
