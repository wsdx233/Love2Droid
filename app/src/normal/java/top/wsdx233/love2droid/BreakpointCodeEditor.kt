package top.wsdx233.love2droid

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import io.github.rosemoe.sora.lang.analysis.StyleUpdateRange
import io.github.rosemoe.sora.lang.styling.Styles
import io.github.rosemoe.sora.lang.styling.color.ConstColor
import io.github.rosemoe.sora.lang.styling.line.LineGutterBackground
import io.github.rosemoe.sora.widget.CodeEditor

class BreakpointCodeEditor @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0,
) : CodeEditor(context, attrs, defStyleAttr, defStyleRes) {
    private var breakpointLines: Set<Int> = emptySet()
    private var styledLines: Set<Int> = emptySet()

    fun setBreakpointLines(lines: Collection<Int>) {
        breakpointLines = lines.asSequence().filter { it >= 0 }.toSortedSet()
        getStyles()?.let { styles ->
            applyBreakpointStyles(styles)
            super.setStyles(styles)
        } ?: invalidate()
    }

    override fun setStyles(styles: Styles?) {
        styles?.let(::applyBreakpointStyles)
        super.setStyles(styles)
    }

    override fun updateStyles(styles: Styles, range: StyleUpdateRange?) {
        applyBreakpointStyles(styles)
        super.updateStyles(styles, range)
        invalidate()
    }

    private fun applyBreakpointStyles(styles: Styles) {
        (styledLines + breakpointLines).forEach { line ->
            styles.eraseLineStyle(line, LineGutterBackground::class.java)
        }
        val color = if (colorScheme.isDark) {
            Color.rgb(183, 28, 28)
        } else {
            Color.rgb(255, 205, 210)
        }
        breakpointLines.forEach { line ->
            styles.addLineStyle(LineGutterBackground(line, ConstColor(color)))
        }
        styles.finishBuilding()
        styledLines = breakpointLines
    }
}
