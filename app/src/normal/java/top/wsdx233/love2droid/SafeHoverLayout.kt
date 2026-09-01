package top.wsdx233.love2droid
import android.graphics.Color
import android.graphics.drawable.ColorDrawable

import android.text.Selection
import android.text.Spannable
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import io.github.rosemoe.sora.lsp.editor.hover.DefaultHoverLayout
import io.github.rosemoe.sora.lsp.editor.hover.HoverLayout
import io.github.rosemoe.sora.lsp.editor.hover.HoverWindow
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import org.eclipse.lsp4j.Hover
import java.net.URI

internal class SafeHoverLayout(
    private val onFileLink: (String) -> Unit,
) : HoverLayout {
    private val delegate = DefaultHoverLayout()

    override fun attach(window: HoverWindow) {
        delegate.attach(window)
        val popup = window.getPopup()
        popup.isFocusable = false
        popup.isOutsideTouchable = true
        popup.isTouchable = true
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.setTouchInterceptor { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                popup.dismiss()
                true
            } else {
                false
            }
        }
    }

    override fun createView(inflater: LayoutInflater): View {
        return delegate.createView(inflater).also { root ->
            root.findViewById<TextView>(io.github.rosemoe.sora.lsp.R.id.hover_text).movementMethod =
                SafeFileLinkMovementMethod(onFileLink)
        }
    }

    override fun applyColorScheme(colorScheme: EditorColorScheme, typeface: android.graphics.Typeface) {
        delegate.applyColorScheme(colorScheme, typeface)
    }

    override fun renderHover(hover: Hover) {
        delegate.renderHover(hover)
    }

    override fun onTextSizeChanged(oldSize: Float, newSize: Float) {
        delegate.onTextSizeChanged(oldSize, newSize)
    }
}

private class SafeFileLinkMovementMethod(
    private val onFileLink: (String) -> Unit,
) : LinkMovementMethod() {
    override fun onTouchEvent(widget: TextView, buffer: Spannable, event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val link = findUrlSpan(widget, buffer, event)
            if (link != null && isFileUri(link.url)) {
                Selection.removeSelection(buffer)
                onFileLink(link.url)
                return true
            }
        }
        return super.onTouchEvent(widget, buffer, event)
    }

    private fun findUrlSpan(widget: TextView, buffer: Spannable, event: MotionEvent): URLSpan? {
        val layout = widget.layout ?: return null
        val x = event.x.toInt() - widget.totalPaddingLeft + widget.scrollX
        val y = event.y.toInt() - widget.totalPaddingTop + widget.scrollY
        if (y < 0 || y > layout.height) return null
        val line = layout.getLineForVertical(y)
        if (x < layout.getLineLeft(line) || x > layout.getLineRight(line)) return null
        val offset = layout.getOffsetForHorizontal(line, x.toFloat())
        return buffer.getSpans(offset, offset, URLSpan::class.java).firstOrNull()
    }

    private fun isFileUri(value: String): Boolean {
        return runCatching { URI(value).scheme.equals("file", ignoreCase = true) }.getOrDefault(false)
    }
}
