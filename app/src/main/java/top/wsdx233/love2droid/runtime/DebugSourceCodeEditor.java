package top.wsdx233.love2droid.runtime;

import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.rosemoe.sora.lang.analysis.StyleUpdateRange;
import io.github.rosemoe.sora.lang.styling.Styles;
import io.github.rosemoe.sora.lang.styling.color.ConstColor;
import io.github.rosemoe.sora.lang.styling.line.LineBackground;
import io.github.rosemoe.sora.lang.styling.line.LineGutterBackground;
import io.github.rosemoe.sora.widget.CodeEditor;

final class DebugSourceCodeEditor extends CodeEditor {
    private int activeLine = -1;
    private int styledLine = -1;

    DebugSourceCodeEditor(Context context) {
        super(context);
    }

    DebugSourceCodeEditor(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    void setActiveLine(int line) {
        activeLine = line;
        Styles styles = getStyles();
        if (styles != null) {
            applyActiveLine(styles);
            super.setStyles(styles);
        } else {
            invalidate();
        }
        if (line >= 0) {
            post(() -> ensurePositionVisible(line, 0, true));
        }
    }

    @Override
    public void setStyles(@Nullable Styles styles) {
        if (styles != null) {
            applyActiveLine(styles);
        }
        super.setStyles(styles);
    }

    @Override
    public void updateStyles(@NonNull Styles styles, @Nullable StyleUpdateRange range) {
        applyActiveLine(styles);
        super.updateStyles(styles, range);
        invalidate();
    }

    private void applyActiveLine(Styles styles) {
        if (styledLine >= 0) {
            styles.eraseLineStyle(styledLine, LineBackground.class);
            styles.eraseLineStyle(styledLine, LineGutterBackground.class);
        }
        if (activeLine >= 0) {
            styles.eraseLineStyle(activeLine, LineBackground.class);
            styles.eraseLineStyle(activeLine, LineGutterBackground.class);
            styles.addLineStyle(new LineBackground(activeLine, new ConstColor(Color.rgb(255, 236, 179))));
            styles.addLineStyle(new LineGutterBackground(activeLine, new ConstColor(Color.rgb(255, 183, 77))));
        }
        styledLine = activeLine;
    }
}
