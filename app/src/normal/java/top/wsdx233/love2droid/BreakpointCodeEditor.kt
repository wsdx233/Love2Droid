package top.wsdx233.love2droid

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import io.github.rosemoe.sora.lang.analysis.StyleUpdateRange
import io.github.rosemoe.sora.lang.styling.Styles
import io.github.rosemoe.sora.lang.styling.color.ConstColor
import io.github.rosemoe.sora.lang.styling.line.LineGutterBackground
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion
import io.github.rosemoe.sora.widget.getComponent

class BreakpointCodeEditor @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0,
) : CodeEditor(context, attrs, defStyleAttr, defStyleRes) {
    private var breakpointLines: Set<Int> = emptySet()
    private var styledLines: Set<Int> = emptySet()
    private var completionSelectedByHardwareTab = false

    init {
        // Keep Enter as a newline for IME input. Hardware completion acceptance is
        // handled explicitly below, after a hardware Tab has moved the selection.
        props.selectCompletionItemOnEnterForSoftKbd = false
        // On an indentation-only line, backspace should remove one indentation unit,
        // not the whole line.
        props.deleteEmptyLineFast = false
        props.deleteMultiSpaces = -1
        // A fast second keystroke must invalidate the previous LSP request instead
        // of being suppressed by Sora's debounce window.
        props.cancelCompletionNs = 0L
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val hardware = event.deviceId != KeyCharacterMap.VIRTUAL_KEYBOARD &&
            event.isFromSource(InputDevice.SOURCE_KEYBOARD)
        val completion = getComponent<EditorAutoCompletion>()
        if (hardware && keyCode == KeyEvent.KEYCODE_TAB && completion.isShowing) {
            completion.moveDown()
            completionSelectedByHardwareTab = true
            return true
        }
        if (hardware && keyCode == KeyEvent.KEYCODE_ENTER && completion.isShowing) {
            if (completionSelectedByHardwareTab && completion.select()) {
                completionSelectedByHardwareTab = false
                return true
            }
            completionSelectedByHardwareTab = false
            completion.hide()
        } else if (keyCode != KeyEvent.KEYCODE_TAB) {
            completionSelectedByHardwareTab = false
        }
        return super.onKeyDown(keyCode, event)
    }

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
