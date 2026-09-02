package top.wsdx233.love2droid

import android.graphics.Color
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors

internal class SymbolNavigationAdapter(
    private val items: List<SymbolNavigationItem>,
    private val onClick: (EditorNavigationTarget) -> Unit,
) : RecyclerView.Adapter<SymbolNavigationAdapter.Holder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val context = parent.context
        val density = context.resources.displayMetrics.density
        val paddingHorizontal = (20 * density).toInt()
        val paddingVertical = (12 * density).toInt()
        val selectableBackground = context.obtainStyledAttributes(
            intArrayOf(android.R.attr.selectableItemBackground),
        ).let { attributes ->
            val drawable = attributes.getDrawable(0)
            attributes.recycle()
            drawable
        }
        val row = LinearLayout(context).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            orientation = LinearLayout.VERTICAL
            minimumHeight = (64 * density).toInt()
            setPadding(paddingHorizontal, paddingVertical, paddingHorizontal, paddingVertical)
            background = selectableBackground
            isClickable = true
            isFocusable = true
        }
        val path = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        val detail = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(
                MaterialColors.getColor(
                    context,
                    com.google.android.material.R.attr.colorOnSurfaceVariant,
                    Color.GRAY,
                ),
            )
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        row.addView(path, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(detail, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return Holder(row, path, detail)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    internal inner class Holder(
        itemView: View,
        private val path: TextView,
        private val detail: TextView,
    ) : RecyclerView.ViewHolder(itemView) {
        fun bind(item: SymbolNavigationItem) {
            path.text = item.relativePath
            detail.text = itemView.context.getString(
                R.string.symbol_location_line,
                item.target.startLine + 1,
                item.preview,
            )
            itemView.setOnClickListener { onClick(item.target) }
        }
    }
}
