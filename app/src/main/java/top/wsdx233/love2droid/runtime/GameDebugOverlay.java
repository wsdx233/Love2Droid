package top.wsdx233.love2droid.runtime;

import androidx.appcompat.app.AlertDialog;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import android.view.ContextThemeWrapper;

import java.nio.charset.StandardCharsets;

import top.wsdx233.love2droid.R;
final class GameDebugOverlay extends FrameLayout {
    private static final int BUBBLE_SIZE_DP = 56;
    private static final int EDGE_MARGIN_DP = 16;
    private static final int DRAG_THRESHOLD_DP = 8;

    private final LoveGameActivity activity;
    private final ImageButton bubble;
    private float downRawX;
    private float downRawY;
    private float downX;
    private float downY;
    private boolean moved;

    GameDebugOverlay(LoveGameActivity activity) {
        super(activity);
        this.activity = activity;
        setClipChildren(false);
        setClipToPadding(false);
        setFocusable(false);

        bubble = new ImageButton(activity);
        bubble.setImageDrawable(ContextCompat.getDrawable(activity, R.drawable.ic_bug_report));
        bubble.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
        bubble.setBackground(circleBackground());
        bubble.setElevation(dp(8));
        bubble.setPadding(dp(14), dp(14), dp(14), dp(14));
        bubble.setContentDescription(activity.getString(R.string.debug_open_menu));
        bubble.setOnTouchListener(this::onBubbleTouch);
        addView(bubble, bubbleLayoutParams());
    }

    void attach() {
        activity.addContentView(this, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));
        post(this::placeBubbleInitially);
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        constrainBubbleToWindow();
    }

    void constrainBubbleToWindow() {
        if (getWidth() > 0 && getHeight() > 0) {
            post(() -> moveBubble(bubble.getX(), bubble.getY()));
        }
    }

    void detach() {
        ((ViewGroup) getParent()).removeView(this);
    }

    private boolean onBubbleTouch(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = event.getRawX();
                downRawY = event.getRawY();
                downX = bubble.getX();
                downY = bubble.getY();
                moved = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - downRawX;
                float dy = event.getRawY() - downRawY;
                if (!moved && Math.hypot(dx, dy) >= dp(DRAG_THRESHOLD_DP))
                    moved = true;
                if (moved)
                    moveBubble(downX + dx, downY + dy);
                return true;
            case MotionEvent.ACTION_UP:
                if (!moved)
                    showMenu();
                else
                    moveBubble(bubble.getX(), bubble.getY());
                return true;
            case MotionEvent.ACTION_CANCEL:
                return true;
            default:
                return false;
        }
    }

    private void placeBubbleInitially() {
        moveBubble(getWidth() - dp(BUBBLE_SIZE_DP + EDGE_MARGIN_DP),
            (getHeight() - dp(BUBBLE_SIZE_DP)) / 2f);
    }

    private void moveBubble(float x, float y) {
        float maxX = Math.max(0, getWidth() - bubble.getWidth());
        float maxY = Math.max(0, getHeight() - bubble.getHeight());
        bubble.setX(Math.max(0, Math.min(x, maxX)));
        bubble.setY(Math.max(0, Math.min(y, maxY)));
    }

    private void showMenu() {
        dialogBuilder()
            .setTitle(R.string.debug_menu_title)
            .setItems(new CharSequence[]{
                activity.getString(R.string.debug_exit),
                activity.getString(R.string.debug_logs),
                activity.getString(R.string.debug_execute_code),
            }, (dialog, which) -> {
                switch (which) {
                    case 0:
                        activity.finish();
                        break;
                    case 1:
                        showLogs();
                        break;
                    case 2:
                        showCodeEditor();
                        break;
                    default:
                        break;
                }
            })
            .show();
    }

    private void showLogs() {
        byte[] bytes = activity.nativeGetDebugLogs();
        String logs = bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
        TextView text = new TextView(activity);
        text.setText(logs.isEmpty() ? activity.getString(R.string.debug_logs_empty) : logs);
        text.setTextColor(Color.WHITE);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextIsSelectable(true);
        text.setMovementMethod(ScrollingMovementMethod.getInstance());
        text.setPadding(dp(12), dp(12), dp(12), dp(12));

        ScrollView scroll = new ScrollView(activity);
        scroll.setBackgroundColor(Color.rgb(30, 30, 34));
        scroll.addView(text, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        dialogBuilder()
            .setTitle(R.string.debug_logs)
            .setView(scroll)
            .setPositiveButton(R.string.debug_close, null)
            .show();
    }

    private void showCodeEditor() {
        EditText input = new EditText(activity);
        input.setSingleLine(false);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setMinLines(5);
        input.setMaxLines(12);
        input.setInputType(InputType.TYPE_CLASS_TEXT
            | InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setHint(R.string.debug_code_hint);
        input.setPadding(dp(12), dp(8), dp(12), dp(8));

        FrameLayout container = new FrameLayout(activity);
        container.addView(input, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        AlertDialog dialog = dialogBuilder()
            .setTitle(R.string.debug_execute_code)
            .setView(container)
            .setNegativeButton(R.string.debug_close, null)
            .setPositiveButton(R.string.debug_execute, null)
            .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(ignoredClick -> {
            String code = input.getText().toString();
            if (code.trim().isEmpty()) {
                input.setError(activity.getString(R.string.debug_code_empty));
                return;
            }
            activity.nativeQueueDebugCode(code.getBytes(StandardCharsets.UTF_8));
            dialog.dismiss();
            Toast.makeText(activity, R.string.debug_code_submitted, Toast.LENGTH_SHORT).show();
        }));
        dialog.show();
    }

    private MaterialAlertDialogBuilder dialogBuilder() {
        return new MaterialAlertDialogBuilder(new ContextThemeWrapper(
            activity, R.style.Theme_Love2Droid_DebugDialog));
    }

    private FrameLayout.LayoutParams bubbleLayoutParams() {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
            dp(BUBBLE_SIZE_DP),
            dp(BUBBLE_SIZE_DP)
        );
        params.gravity = Gravity.TOP | Gravity.START;
        return params;
    }

    private android.graphics.drawable.Drawable circleBackground() {
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
        drawable.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        drawable.setColor(Color.rgb(103, 80, 164));
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
