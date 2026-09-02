package top.wsdx233.love2droid.runtime;

import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.Spannable;
import android.text.TextWatcher;
import android.text.method.ScrollingMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.EditText;

import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import org.eclipse.tm4e.core.registry.IThemeSource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme;
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage;
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry;
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry;
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry;
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel;
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver;

import top.wsdx233.love2droid.R;
final class GameDebugOverlay extends FrameLayout {
    private static final int BUBBLE_SIZE_DP = 56;
    private static final int EDGE_MARGIN_DP = 16;
    private static final int DRAG_THRESHOLD_DP = 8;
    private static final int PANEL_MAX_WIDTH_DP = 520;
    private static final int PANEL_MIN_WIDTH_DP = 360;
    private static final int PANEL_PORTION = 78;
    private static final int COLOR_INK = Color.rgb(32, 29, 36);
    private static final int COLOR_MUTED = Color.rgb(94, 89, 99);
    private static final int COLOR_PRIMARY = Color.rgb(103, 80, 164);
    private static final int COLOR_PRIMARY_CONTAINER = Color.rgb(237, 230, 255);
    private static final int COLOR_SURFACE = Color.rgb(255, 255, 255);
    private static final int COLOR_CODE_SURFACE = Color.rgb(248, 246, 253);
    private static final Pattern LUA_KEYWORDS = Pattern.compile("\\b(local|return|function|end|if|then|else|for|while|do|and|or|not|true|false|nil)\\b");
    private static final Pattern LUA_NUMBERS = Pattern.compile("\\b(?:0x[0-9a-fA-F]+|\\d+(?:\\.\\d+)?)\\b");
    private static final Pattern LUA_STRINGS = Pattern.compile("(?:\\\"(?:\\\\.|[^\\\"])*\\\"|'(?:\\\\.|[^'])*')");
    private static final Pattern LUA_COMMENTS = Pattern.compile("--[^\\n]*");

    private final LoveGameActivity activity;
    private final Uri gameUri;
    private final ImageButton bubble;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService nativeExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean refreshInFlight = new AtomicBoolean(false);
    private final List<TextView> tabButtons = new ArrayList<>();
    private final List<WatchEntry> watches = new ArrayList<>();

    private final Map<Integer, String> watchValues = new HashMap<>();

    private FrameLayout scrim;
    private LinearLayout panel;
    private ImageButton pauseResumeButton;
    private LinearLayout hud;
    private FrameLayout content;
    private TextView logsText;
    private TextView stackText;
    private TextView sourceLocation;
    private DebugSourceCodeEditor sourceEditor;
    private LinearLayout watchRows;
    private EditText logSearch;
    private EditText watchInput;
    private EditText replInput;
    private ScrollView logsScroll;
    private boolean logsAutoFollow = true;
    private int activeTab;
    private String currentFilter = "all";
    private String latestLogs = "";
    private String latestState = "";
    private String loadedSource = "";
    private String requestedSource = "";
    private boolean loadedSourceAvailable;
    private volatile boolean detached;
    private boolean debugPaused;
    private boolean panelOpen;
    private boolean panelExpanded;
    private boolean moved;
    private float downRawX;
    private float downRawY;
    private float downX;
    private float downY;
    private int nextWatchId = 1;
    private int sourceLoadGeneration;
    private int lastOrientation;

    GameDebugOverlay(LoveGameActivity activity, Uri gameUri) {
        super(activity);
        this.activity = activity;
        this.gameUri = gameUri;
        lastOrientation = getResources().getConfiguration().orientation;
        setClipToPadding(false);
        setFocusable(false);

        bubble = new ImageButton(activity);
        bubble.setImageDrawable(ContextCompat.getDrawable(activity, R.drawable.ic_terminal));
        bubble.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
        bubble.setBackground(roundDrawable(Color.argb(220, 0, 0, 0), dp(18), Color.argb(80, 255, 255, 255), dp(1)));
        bubble.setElevation(dp(8));
        bubble.setPadding(dp(14), dp(14), dp(14), dp(14));
        bubble.setContentDescription(activity.getString(R.string.debug_open_menu));
        bubble.setOnTouchListener(this::onBubbleTouch);
        addView(bubble, bubbleLayoutParams());
        hud = buildHud();
        FrameLayout.LayoutParams hudParams = new FrameLayout.LayoutParams(WRAP, WRAP);
        hudParams.gravity = Gravity.TOP | Gravity.START;
        hudParams.setMargins(dp(16), dp(16), dp(16), 0);
        addView(hud, hudParams);
    }

    void attach() {
        activity.addContentView(this, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));
        post(this::placeBubbleInitially);
        mainHandler.post(refreshRunnable);
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        int orientation = getResources().getConfiguration().orientation;
        if (orientation != lastOrientation && oldWidth > 0 && oldHeight > 0) {
            int bubbleWidth = bubble.getWidth() > 0 ? bubble.getWidth() : dp(BUBBLE_SIZE_DP);
            int bubbleHeight = bubble.getHeight() > 0 ? bubble.getHeight() : dp(BUBBLE_SIZE_DP);
            float y = GameDebugBubblePosition.mapVerticalPosition(
                bubble.getY(), oldHeight, height, bubbleHeight);
            moveBubble(GameDebugBubblePosition.dockedRightX(
                width, bubbleWidth, dp(EDGE_MARGIN_DP)), y);
        } else {
            constrainBubbleToWindow();
        }
        lastOrientation = orientation;
        if (panelOpen)
            updatePanelLayout(false);
    }

    void constrainBubbleToWindow() {
        if (getWidth() > 0 && getHeight() > 0)
            post(() -> moveBubble(bubble.getX(), bubble.getY()));
    }

    void detach() {
        detached = true;
        mainHandler.removeCallbacks(refreshRunnable);
        nativeExecutor.shutdownNow();
        if (sourceEditor != null)
            sourceEditor.release();
        ViewParent parent = getParent();
        if (parent instanceof ViewGroup)
            ((ViewGroup) parent).removeView(this);
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
                    showPanel();
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
        int bubbleWidth = bubble.getWidth() > 0 ? bubble.getWidth() : dp(BUBBLE_SIZE_DP);
        moveBubble(GameDebugBubblePosition.dockedRightX(
                getWidth(), bubbleWidth, dp(EDGE_MARGIN_DP)),
            (getHeight() - dp(BUBBLE_SIZE_DP)) / 2f);
    }

    private void moveBubble(float x, float y) {
        float maxX = Math.max(0, getWidth() - bubble.getWidth());
        float maxY = Math.max(0, getHeight() - bubble.getHeight());
        bubble.setX(Math.max(0, Math.min(x, maxX)));
        bubble.setY(Math.max(0, Math.min(y, maxY)));
    }

    private void showPanel() {
        if (panelOpen) {
            showTab(activeTab);
            return;
        }
        panelOpen = true;
        panelExpanded = false;
        if (scrim == null) {
            scrim = new FrameLayout(activity);
            scrim.setBackgroundColor(Color.argb(138, 0, 0, 0));
            scrim.setClickable(true);
            scrim.setOnClickListener(view -> hidePanel());
            addView(scrim, new FrameLayout.LayoutParams(MATCH, MATCH));
            panel = buildPanel();
            addView(panel, panelLayoutParams());
        }
        bubble.setVisibility(INVISIBLE);
        scrim.setVisibility(VISIBLE);
        panel.setVisibility(VISIBLE);
        updatePanelLayout(false);
        panel.post(() -> {
            boolean landscape = isLandscape();
            panel.setTranslationX(landscape ? -panel.getWidth() - dp(24) : 0);
            panel.setTranslationY(landscape ? 0 : panel.getHeight() + dp(24));
            panel.animate().translationX(0).translationY(0)
                .setDuration(280L).start();
        });
        showTab(activeTab);
        beginNativeRefresh();
    }

    private void hidePanel() {
        if (!panelOpen || panel == null)
            return;
        panelOpen = false;
        boolean landscape = isLandscape();
        panel.animate()
            .translationX(landscape ? -panel.getWidth() - dp(24) : 0)
            .translationY(landscape ? 0 : panel.getHeight() + dp(24))
            .setDuration(220L)
            .withEndAction(() -> {
                panel.setVisibility(GONE);
                scrim.setVisibility(GONE);
                bubble.setVisibility(VISIBLE);
            }).start();
        activity.getWindow().getDecorView().clearFocus();
    }

    private LinearLayout buildHud() {
        LinearLayout view = new LinearLayout(activity);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setPadding(dp(14), dp(10), dp(14), dp(10));
        view.setBackground(roundDrawable(Color.argb(220, 0, 0, 0), dp(16), Color.argb(80, 255, 255, 255), dp(1)));
        view.setElevation(dp(6));
        view.setVisibility(GONE);
        return view;
    }

    private LinearLayout buildPanel() {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, 0, 0, 0);
        root.setBackground(panelBackground(isLandscape()));
        root.setElevation(dp(12));
        root.setClickable(true);
        root.setFocusable(true);
        root.setOnClickListener(view -> { });

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(20), dp(12), dp(12), 0);
        header.setBackgroundColor(COLOR_SURFACE);
        if (!isLandscape()) {
            View handle = new View(activity);
            handle.setBackground(roundDrawable(Color.rgb(120, 116, 126), dp(3), Color.TRANSPARENT, 0));
            LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(dp(32), dp(4));
            handleParams.gravity = Gravity.CENTER_HORIZONTAL;
            handleParams.bottomMargin = dp(10);
            header.addView(handle, handleParams);
        }

        LinearLayout titleRow = new LinearLayout(activity);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(s(R.string.debug_panel_title), 17, COLOR_INK, Typeface.DEFAULT_BOLD);
        titleRow.addView(title, weightParams(0, 1));
        pauseResumeButton = iconButton(R.drawable.ic_pause, R.string.debug_pause);
        pauseResumeButton.setOnClickListener(view -> {
            if (debugPaused)
                activity.nativeResumeDebug();
            else
                activity.nativePauseDebug();
        });
        titleRow.addView(pauseResumeButton, iconParams());
        ImageButton fullscreen = iconButton(R.drawable.ic_fullscreen, R.string.debug_fullscreen);
        fullscreen.setOnClickListener(view -> {
            panelExpanded = !panelExpanded;
            fullscreen.setImageResource(panelExpanded ? R.drawable.ic_fullscreen_exit : R.drawable.ic_fullscreen);
            fullscreen.setContentDescription(s(panelExpanded ? R.string.debug_exit_fullscreen : R.string.debug_fullscreen));
            updatePanelLayout(true);
        });
        titleRow.addView(fullscreen, iconParams());
        ImageButton exit = iconButton(R.drawable.ic_exit, R.string.debug_exit_game);
        exit.setOnClickListener(view -> activity.finish());
        titleRow.addView(exit, iconParams());
        ImageButton close = iconButton(R.drawable.ic_close, R.string.debug_close);
        close.setOnClickListener(view -> hidePanel());
        titleRow.addView(close, iconParams());
        header.addView(titleRow, new LinearLayout.LayoutParams(MATCH, dp(64)));

        LinearLayout tabs = new LinearLayout(activity);
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        tabs.setPadding(0, dp(4), 0, dp(4));
        addTab(tabs, R.drawable.ic_terminal, R.string.debug_tab_console, 0);
        addTab(tabs, R.drawable.ic_visibility, R.string.debug_tab_watch, 1);
        addTab(tabs, R.drawable.ic_breakpoint, R.string.debug_tab_breakpoints, 2);
        header.addView(tabs, new LinearLayout.LayoutParams(MATCH, dp(64)));
        root.addView(header, new LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT));

        content = new FrameLayout(activity);
        content.setBackgroundColor(COLOR_SURFACE);
        content.addView(buildLogsPage(), fillParams());
        content.addView(buildWatchPage(), fillParams());
        content.addView(buildBreakpointPage(), fillParams());
        root.addView(content, verticalWeightParams());
        return root;
    }

    private View buildLogsPage() {
        LinearLayout page = page();
        LinearLayout searchRow = new LinearLayout(activity);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setPadding(dp(16), dp(8), dp(12), dp(2));
        logSearch = edit(s(R.string.debug_log_search), false);
        searchRow.addView(logSearch, weightParams(0, 1));
        ImageButton clear = iconButton(R.drawable.ic_delete, R.string.debug_clear_logs);
        clear.setOnClickListener(view -> {
            activity.nativeClearDebugLogs();
            latestLogs = "";
            renderLogs();
        });
        searchRow.addView(clear, iconParams());
        ImageButton follow = iconButton(R.drawable.ic_lock, R.string.debug_lock_logs);
        follow.setOnClickListener(view -> {
            logsAutoFollow = !logsAutoFollow;
            follow.setImageResource(logsAutoFollow ? R.drawable.ic_lock : R.drawable.ic_lock_open);
            follow.setContentDescription(s(logsAutoFollow ? R.string.debug_lock_logs : R.string.debug_unlock_logs));
        });
        searchRow.addView(follow, iconParams());
        page.addView(searchRow, new LinearLayout.LayoutParams(MATCH, dp(68)));

        HorizontalScrollView filters = new HorizontalScrollView(activity);
        filters.setHorizontalScrollBarEnabled(false);
        LinearLayout filterRow = new LinearLayout(activity);
        filterRow.setPadding(dp(16), 0, dp(16), dp(2));
        addFilter(filterRow, R.string.debug_filter_all, "all");
        addFilter(filterRow, R.string.debug_filter_info, "info");
        addFilter(filterRow, R.string.debug_filter_warn, "warn");
        addFilter(filterRow, R.string.debug_filter_error, "error");
        filters.addView(filterRow, new HorizontalScrollView.LayoutParams(WRAP, MATCH));
        page.addView(filters, new LinearLayout.LayoutParams(MATCH, dp(52)));

        logsScroll = new ScrollView(activity);
        logsScroll.setFillViewport(true);
        logsText = text("", 13, COLOR_INK, Typeface.MONOSPACE);
        logsText.setTextIsSelectable(true);
        logsText.setMovementMethod(ScrollingMovementMethod.getInstance());
        logsText.setPadding(dp(16), dp(8), dp(16), dp(16));
        logsScroll.addView(logsText, new ScrollView.LayoutParams(MATCH, WRAP));
        page.addView(logsScroll, verticalWeightParams());
        page.addView(buildConsoleControls(), new LinearLayout.LayoutParams(MATCH, WRAP));
        logSearch.addTextChangedListener(simpleWatcher(this::renderLogs));
        return page;
    }

    private View buildConsoleControls() {
        LinearLayout controls = new LinearLayout(activity);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(0, dp(2), 0, dp(8));

        LinearLayout runRow = new LinearLayout(activity);
        runRow.setGravity(Gravity.CENTER_VERTICAL);
        runRow.setPadding(dp(16), dp(6), dp(12), 0);
        replInput = edit(s(R.string.debug_repl_hint), true);
        replInput.setGravity(Gravity.TOP | Gravity.START);
        replInput.setTypeface(Typeface.MONOSPACE);
        replInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        replInput.setMinHeight(dp(84));
        replInput.setPadding(dp(14), dp(10), dp(14), dp(10));
        runRow.addView(replInput, weightParams(0, 1));
        ImageButton run = iconButton(R.drawable.ic_play, R.string.debug_execute);
        run.setBackground(roundDrawable(COLOR_PRIMARY, dp(18), COLOR_PRIMARY, 0));
        run.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
        runRow.addView(run, iconParams());
        controls.addView(runRow, new LinearLayout.LayoutParams(MATCH, dp(102)));
        run.setOnClickListener(view -> submitRepl());
        replInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable editable) { highlightLua(editable); }
        });
        return controls;
    }

    private View buildWatchPage() {
        LinearLayout page = page();
        LinearLayout addRow = new LinearLayout(activity);
        addRow.setGravity(Gravity.CENTER_VERTICAL);
        addRow.setPadding(dp(16), dp(12), dp(12), dp(4));
        watchInput = edit(s(R.string.debug_watch_hint), false);
        addRow.addView(watchInput, weightParams(0, 1));
        ImageButton add = iconButton(R.drawable.ic_add, R.string.debug_add_watch);
        add.setOnClickListener(view -> addWatch());
        addRow.addView(add, iconParams());
        page.addView(addRow, new LinearLayout.LayoutParams(MATCH, dp(72)));
        ScrollView scroll = new ScrollView(activity);
        watchRows = new LinearLayout(activity);
        watchRows.setOrientation(LinearLayout.VERTICAL);
        watchRows.setPadding(dp(16), dp(4), dp(16), dp(24));
        scroll.addView(watchRows, new ScrollView.LayoutParams(MATCH, WRAP));
        page.addView(scroll, verticalWeightParams());
        return page;
    }

    private View buildBreakpointPage() {
        LinearLayout page = page();
        page.addView(buildControlBar(), new LinearLayout.LayoutParams(MATCH, dp(70)));

        sourceLocation = text(s(R.string.debug_source_waiting), 14, COLOR_MUTED, Typeface.MONOSPACE);
        sourceLocation.setPadding(dp(20), dp(4), dp(20), dp(4));
        page.addView(sourceLocation, new LinearLayout.LayoutParams(MATCH, dp(48)));

        sourceEditor = new DebugSourceCodeEditor(activity);
        sourceEditor.setEditable(false);
        sourceEditor.setSoftKeyboardEnabled(false);
        sourceEditor.setTextSize(14);
        sourceEditor.setLineNumberEnabled(true);
        sourceEditor.setHighlightCurrentLine(false);
        sourceEditor.setWordwrap(false);
        sourceEditor.setTypefaceText(Typeface.MONOSPACE);
        sourceEditor.setTypefaceLineNumber(Typeface.MONOSPACE);
        sourceEditor.getProps().drawCustomLineBgOnCurrentLine = true;
        setupSourceHighlighting();
        sourceEditor.setText(s(R.string.debug_source_waiting));
        page.addView(sourceEditor, verticalWeightParams());

        ScrollView stackScroll = new ScrollView(activity);
        stackText = text(s(R.string.debug_stack_empty), 13, COLOR_INK, Typeface.MONOSPACE);
        stackText.setPadding(dp(20), dp(8), dp(20), dp(16));
        stackScroll.addView(stackText, new ScrollView.LayoutParams(MATCH, WRAP));
        page.addView(stackScroll, new LinearLayout.LayoutParams(MATCH, dp(104)));
        return page;
    }

    private void setupSourceHighlighting() {
        try {
            FileProviderRegistry providers = FileProviderRegistry.getInstance();
            providers.addFileProvider(new AssetsFileResolver(activity.getAssets()));
            ThemeRegistry themes = ThemeRegistry.getInstance();
            if (themes.findThemeByFileName("quietlight") == null) {
                String path = "textmate/quietlight.json";
                try (InputStream stream = providers.tryGetInputStream(path)) {
                    if (stream == null)
                        throw new IllegalStateException("Missing quietlight theme");
                    ThemeModel theme = new ThemeModel(IThemeSource.fromInputStream(stream, path, null), "quietlight");
                    theme.setDark(false);
                    themes.loadTheme(theme);
                }
            }
            themes.setTheme("quietlight");
            GrammarRegistry grammars = GrammarRegistry.getInstance();
            if (grammars.findGrammar("source.lua") == null)
                grammars.loadGrammars("textmate/languages.json");
            sourceEditor.setColorScheme(TextMateColorScheme.create(themes));
            sourceEditor.setEditorLanguage(TextMateLanguage.create("source.lua", false));
        } catch (Exception error) {
            sourceEditor.setEditorLanguage(new io.github.rosemoe.sora.lang.EmptyLanguage());
        }
    }

    private View buildControlBar() {
        HorizontalScrollView scroll = new HorizontalScrollView(activity);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(activity);
        row.setPadding(dp(12), dp(2), dp(12), dp(2));
        addControl(row, R.drawable.ic_play, R.string.debug_resume, () -> activity.nativeResumeDebug());
        addControl(row, R.drawable.ic_step_over, R.string.debug_step_over, () -> activity.nativeStepDebug(0));
        addControl(row, R.drawable.ic_step_into, R.string.debug_step_into, () -> activity.nativeStepDebug(1));
        addControl(row, R.drawable.ic_step_out, R.string.debug_step_out, () -> activity.nativeStepDebug(2));
        addControl(row, R.drawable.ic_pause, R.string.debug_pause, () -> activity.nativePauseDebug());
        scroll.addView(row, new HorizontalScrollView.LayoutParams(WRAP, MATCH));
        return scroll;
    }


    private void addTab(LinearLayout tabs, int icon, int label, int index) {
        TextView tab = text(s(label), 12, COLOR_MUTED, Typeface.DEFAULT);
        Drawable drawable = ContextCompat.getDrawable(activity, icon);
        if (drawable != null) {
            drawable.setColorFilter(COLOR_MUTED, PorterDuff.Mode.SRC_IN);
            tab.setCompoundDrawablesWithIntrinsicBounds(null, drawable, null, null);
        }
        tab.setCompoundDrawablePadding(dp(1));
        tab.setSingleLine(true);
        tab.setGravity(Gravity.CENTER);
        tab.setPadding(dp(2), dp(2), dp(2), dp(2));
        tab.setOnClickListener(view -> showTab(index));
        tabButtons.add(tab);
        tabs.addView(tab, weightParams(0, 1));
    }

    private void showTab(int index) {
        activeTab = Math.max(0, Math.min(2, index));
        if (content != null) {
            for (int i = 0; i < content.getChildCount(); ++i)
                content.getChildAt(i).setVisibility(i == activeTab ? VISIBLE : GONE);
        }
        for (int i = 0; i < tabButtons.size(); ++i) {
            TextView tab = tabButtons.get(i);
            boolean selected = i == activeTab;
            int color = selected ? COLOR_PRIMARY : COLOR_MUTED;
            tab.setTextColor(color);
            Drawable[] drawables = tab.getCompoundDrawables();
            if (drawables.length > 1 && drawables[1] != null)
                drawables[1].setColorFilter(color, PorterDuff.Mode.SRC_IN);
            tab.setBackground(roundDrawable(selected ? COLOR_PRIMARY_CONTAINER : Color.TRANSPARENT, dp(18), Color.TRANSPARENT, 0));
        }
        beginNativeRefresh();
    }

    private void addFilter(LinearLayout row, int label, String filter) {
        TextView chip = actionText(s(label), 0, false);
        chip.setOnClickListener(view -> {
            currentFilter = filter;
            for (int i = 0; i < row.getChildCount(); ++i) {
                View child = row.getChildAt(i);
                if (child instanceof TextView)
                    child.setBackground(roundDrawable(child == view ? COLOR_PRIMARY_CONTAINER : Color.TRANSPARENT, dp(18), Color.TRANSPARENT, 0));
            }
            renderLogs();
        });
        chip.setBackground(roundDrawable("all".equals(filter) ? COLOR_PRIMARY_CONTAINER : Color.TRANSPARENT, dp(18), Color.TRANSPARENT, 0));
        row.addView(chip, new LinearLayout.LayoutParams(WRAP, dp(48)));
    }

    private void addControl(LinearLayout row, int icon, int label, Runnable action) {
        TextView button = actionText(s(label), icon, false);
        button.setOnClickListener(view -> action.run());
        row.addView(button, new LinearLayout.LayoutParams(WRAP, dp(58)));
    }



    private void addWatch() {
        String expression = watchInput == null ? "" : watchInput.getText().toString().trim();
        if (expression.isEmpty()) {
            watchInput.setError(s(R.string.debug_watch_empty));
            return;
        }
        if (expression.length() > 2048) {
            watchInput.setError(s(R.string.debug_watch_too_long));
            return;
        }
        WatchEntry entry = new WatchEntry(nextWatchId++, expression);
        watches.add(entry);
        watchInput.setText("");
        renderWatchRows();
    }

    private void renderWatchRows() {
        if (watchRows == null) return;
        watchRows.removeAllViews();
        for (WatchEntry entry : watches) {
            LinearLayout card = new LinearLayout(activity);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(16), dp(10), dp(8), dp(10));
            card.setBackground(roundDrawable(Color.WHITE, dp(16), Color.rgb(220, 215, 225), dp(1)));
            LinearLayout top = new LinearLayout(activity);
            top.setGravity(Gravity.CENTER_VERTICAL);
            TextView expression = text(entry.expression, 14, COLOR_PRIMARY, Typeface.MONOSPACE);
            top.addView(expression, weightParams(0, 1));
            ImageButton pin = iconButton(R.drawable.ic_push_pin, entry.pinned ? R.string.debug_unpin_watch : R.string.debug_pin_watch);
            pin.setColorFilter(entry.pinned ? COLOR_PRIMARY : COLOR_MUTED, PorterDuff.Mode.SRC_IN);
            pin.setOnClickListener(view -> {
                entry.pinned = !entry.pinned;
                renderWatchRows();
                renderHud();
            });
            top.addView(pin, iconParams());
            ImageButton remove = iconButton(R.drawable.ic_close, R.string.debug_remove_watch);
            remove.setOnClickListener(view -> {
                watches.remove(entry);
                watchValues.remove(entry.id);
                renderWatchRows();
            });
            top.addView(remove, iconParams());
            card.addView(top, new LinearLayout.LayoutParams(MATCH, dp(58)));
            entry.valueView = text(watchValues.getOrDefault(entry.id, s(R.string.debug_watch_waiting)), 15, COLOR_INK, Typeface.MONOSPACE);
            entry.valueView.setPadding(0, 0, 0, dp(4));
            card.addView(entry.valueView, new LinearLayout.LayoutParams(MATCH, WRAP));
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(MATCH, WRAP);
            cardParams.setMargins(0, 0, 0, dp(10));
            watchRows.addView(card, cardParams);
        }
        if (watches.isEmpty()) {
            TextView empty = text(s(R.string.debug_watch_empty_list), 14, COLOR_MUTED, Typeface.DEFAULT);
            empty.setPadding(dp(4), dp(24), dp(4), dp(24));
            watchRows.addView(empty, new LinearLayout.LayoutParams(MATCH, WRAP));
        }
        renderHud();
    }

    private void renderHud() {
        if (hud == null) return;
        hud.removeAllViews();
        boolean hasPinned = false;
        for (WatchEntry entry : watches) {
            if (!entry.pinned) continue;
            hasPinned = true;
            String value = watchValues.getOrDefault(entry.id, s(R.string.debug_watch_waiting));
            TextView row = text(entry.expression + "  " + value, 13, Color.WHITE, Typeface.MONOSPACE);
            hud.addView(row, new LinearLayout.LayoutParams(WRAP, dp(32)));
        }
        hud.setVisibility(hasPinned ? VISIBLE : GONE);
    }




    private void submitRepl() {
        if (replInput == null) return;
        String code = replInput.getText().toString().trim();
        if (code.isEmpty()) {
            replInput.setError(s(R.string.debug_code_empty));
            return;
        }
        activity.nativeQueueDebugCode(code.getBytes(StandardCharsets.UTF_8));
        Toast.makeText(activity, R.string.debug_code_submitted, Toast.LENGTH_SHORT).show();
        beginNativeRefresh();
    }

    private void beginNativeRefresh() {
        if (detached || !panelOpen || !refreshInFlight.compareAndSet(false, true)) return;
        nativeExecutor.execute(() -> {
            byte[] logBytes = activity.nativeGetDebugLogs();
            byte[] stateBytes = activity.nativeGetDebugState();
            String logs = logBytes == null ? "" : new String(logBytes, StandardCharsets.UTF_8);
            String state = stateBytes == null ? "" : new String(stateBytes, StandardCharsets.UTF_8);
            mainHandler.post(() -> {
                if (detached) {
                    refreshInFlight.set(false);
                    return;
                }
                refreshInFlight.set(false);
                latestLogs = logs;
                latestState = state;
                parseWatchValues(logs);
                renderLogs();
                renderWatchRows();
                renderHud();
                renderState(state);
                if (panelOpen && activeTab == 1)
                    requestWatchValues();
            });
        });
    }
    private void requestWatchValues() {
        for (WatchEntry entry : watches) {
            String marker = "__L2D_WATCH_" + entry.id + "__";
            String code = "local __ok,__value=pcall(function() return (" + entry.expression + ") end); if __ok then print(\"" + marker + "\" .. tostring(__value)) else print(\"" + marker + "<error> \" .. tostring(__value)) end";
            activity.nativeQueueDebugCode(code.getBytes(StandardCharsets.UTF_8));
        }
    }


    private void parseWatchValues(String logs) {
        for (String line : logs.split("\\n")) {
            int markerStart = line.indexOf("__L2D_WATCH_");
            if (markerStart < 0) continue;
            int markerEnd = line.indexOf("__", markerStart + 12);
            if (markerEnd < 0) continue;
            try {
                int id = Integer.parseInt(line.substring(markerStart + 12, markerEnd));
                String value = line.substring(markerEnd + 2).trim();
                watchValues.put(id, value);
            } catch (NumberFormatException ignored) { }
        }
    }

    private void renderLogs() {
        if (logsText == null) return;
        String query = logSearch == null ? "" : logSearch.getText().toString().trim().toLowerCase();
        StringBuilder visible = new StringBuilder();
        for (String line : latestLogs.split("\\n")) {
            if (line.contains("__L2D_WATCH_")) continue;
            String lower = line.toLowerCase();
            String level = line.startsWith("!") || lower.contains("error") ? "error" : lower.contains("warn") ? "warn" : "info";
            if (!"all".equals(currentFilter) && !currentFilter.equals(level)) continue;
            if (!query.isEmpty() && !lower.contains(query)) continue;
            if (visible.length() > 0) visible.append('\n');
            visible.append(line);
        }
        if (visible.length() == 0)
            visible.append(s(R.string.debug_logs_empty));
        String visibleText = visible.toString();
        Spannable styled = new Spannable.Factory().newSpannable(visibleText);
        int offset = 0;
        for (String line : visibleText.split("\\n", -1)) {
            int color = line.startsWith("!") ? Color.rgb(186, 26, 26) : line.startsWith(">") ? COLOR_PRIMARY : COLOR_INK;
            styled.setSpan(new ForegroundColorSpan(color), offset, offset + line.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            offset += line.length() + 1;
        }
        if (!visibleText.contentEquals(logsText.getText()))
            logsText.setText(styled);
        if (logsScroll != null && logsAutoFollow) {
            logsScroll.post(() -> logsScroll.scrollTo(
                logsScroll.getScrollX(),
                Math.max(0, logsText.getHeight() - logsScroll.getHeight())
            ));
        }
    }


    private void renderState(String state) {
        if (stackText == null) return;
        debugPaused = state.contains("paused=1");
        if (pauseResumeButton != null) {
            pauseResumeButton.setImageResource(debugPaused ? R.drawable.ic_play : R.drawable.ic_pause);
            pauseResumeButton.setContentDescription(s(debugPaused ? R.string.debug_resume : R.string.debug_pause));
        }
        String stack = stateValue(state, "stack").replace("|", "\n");
        String reason = stateValue(state, "reason");
        if (stack.isEmpty()) stack = s(R.string.debug_stack_empty);
        stackText.setText(reason.isEmpty() ? stack : s(R.string.debug_stack_reason, reason) + "\n" + stack);

        String source = stateValue(state, "source");
        int line = positiveInt(stateValue(state, "line"));
        if (!debugPaused || source.isEmpty() || line <= 0) {
            showSourcePlaceholder(R.string.debug_source_waiting);
            return;
        }
        sourceLocation.setText(s(R.string.debug_source_location, source, line));
        if (source.equals(loadedSource)) {
            sourceEditor.setActiveLine(loadedSourceAvailable
                ? Math.min(line - 1, sourceEditor.getLineCount() - 1) : -1);
        } else if (!source.equals(requestedSource)) {
            requestSource(source);
        }
    }

    private void requestSource(String source) {
        requestedSource = source;
        int generation = ++sourceLoadGeneration;
        sourceEditor.setActiveLine(-1);
        sourceEditor.setText(s(R.string.debug_source_loading));
        if (gameUri == null) {
            loadedSource = source;
            loadedSourceAvailable = false;
            requestedSource = "";
            sourceEditor.setText(s(R.string.debug_source_unavailable));
            return;
        }
        nativeExecutor.execute(() -> {
            String code = null;
            try {
                InputStream input = activity.getContentResolver().openInputStream(gameUri);
                code = DebugSourceArchive.readSource(input, source);
            } catch (Exception ignored) { }
            String loadedCode = code;
            mainHandler.post(() -> {
                if (detached || generation != sourceLoadGeneration) return;
                loadedSource = source;
                loadedSourceAvailable = loadedCode != null;
                requestedSource = "";
                String currentSource = stateValue(latestState, "source");
                int currentLine = positiveInt(stateValue(latestState, "line"));
                if (!debugPaused || !source.equals(currentSource)) return;
                sourceEditor.setText(loadedCode == null ? s(R.string.debug_source_unavailable) : loadedCode);
                if (loadedCode != null && currentLine > 0)
                    sourceEditor.setActiveLine(Math.min(currentLine - 1, sourceEditor.getLineCount() - 1));
            });
        });
    }

    private void showSourcePlaceholder(int message) {
        if (!loadedSource.isEmpty() || !requestedSource.isEmpty()) {
            ++sourceLoadGeneration;
            loadedSource = "";
            loadedSourceAvailable = false;
            requestedSource = "";
            sourceEditor.setActiveLine(-1);
            sourceEditor.setText(s(message));
        }
        sourceLocation.setText(s(message));
    }

    private int positiveInt(String value) {
        try {
            return Math.max(0, Integer.parseInt(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }



    private String stateValue(String state, String key) {
        String prefix = key + "=";
        for (String line : state.split("\\n"))
            if (line.startsWith(prefix)) return line.substring(prefix.length());
        return "";
    }

    private void highlightLua(Editable editable) {
        ForegroundColorSpan[] spans = editable.getSpans(0, editable.length(), ForegroundColorSpan.class);
        for (ForegroundColorSpan span : spans) editable.removeSpan(span);
        applySpans(editable, LUA_COMMENTS, Color.rgb(108, 105, 115));
        applySpans(editable, LUA_STRINGS, Color.rgb(148, 70, 40));
        applySpans(editable, LUA_KEYWORDS, COLOR_PRIMARY);
        applySpans(editable, LUA_NUMBERS, Color.rgb(0, 105, 92));
    }

    private void applySpans(Editable editable, Pattern pattern, int color) {
        Matcher matcher = pattern.matcher(editable.toString());
        while (matcher.find())
            editable.setSpan(new ForegroundColorSpan(color), matcher.start(), matcher.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private TextWatcher simpleWatcher(Runnable action) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { action.run(); }
            @Override public void afterTextChanged(Editable editable) { }
        };
    }

    private LinearLayout page() {
        LinearLayout page = new LinearLayout(activity);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(COLOR_SURFACE);
        return page;
    }

    private EditText edit(String hint, boolean multiline) {
        EditText edit = new EditText(activity);
        edit.setHint(hint);
        edit.setHintTextColor(Color.rgb(120, 116, 126));
        edit.setTextColor(COLOR_INK);
        edit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        edit.setSingleLine(!multiline);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | (multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS : InputType.TYPE_TEXT_VARIATION_NORMAL));
        edit.setPadding(dp(16), dp(4), dp(16), dp(4));
        edit.setBackground(roundDrawable(COLOR_CODE_SURFACE, dp(14), Color.rgb(210, 205, 216), dp(1)));
        edit.setOnFocusChangeListener((view, focused) -> view.setBackground(roundDrawable(COLOR_CODE_SURFACE, dp(14), focused ? COLOR_PRIMARY : Color.rgb(210, 205, 216), dp(focused ? 2 : 1))));
        return edit;
    }

    private TextView text(String value, int size, int color, Typeface typeface) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        view.setTextColor(color);
        view.setTypeface(typeface);
        view.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        return view;
    }

    private TextView actionText(String value, int icon, boolean filled) {
        TextView view = text(value, 14, filled ? Color.WHITE : COLOR_INK, Typeface.DEFAULT);
        view.setGravity(Gravity.CENTER);
        view.setCompoundDrawablePadding(dp(8));
        if (icon != 0) {
            Drawable drawable = ContextCompat.getDrawable(activity, icon);
            if (drawable != null) {
                drawable.setColorFilter(filled ? Color.WHITE : COLOR_INK, PorterDuff.Mode.SRC_IN);
                view.setCompoundDrawablesWithIntrinsicBounds(drawable, null, null, null);
            }
        }
        view.setPadding(dp(14), 0, dp(14), 0);
        view.setBackground(roundDrawable(filled ? COLOR_PRIMARY : Color.TRANSPARENT, dp(18), filled ? COLOR_PRIMARY : Color.rgb(225, 220, 230), filled ? 0 : dp(1)));
        return view;
    }


    private ImageButton iconButton(int icon, int description) {
        ImageButton button = new ImageButton(activity);
        button.setImageResource(icon);
        button.setColorFilter(COLOR_INK, PorterDuff.Mode.SRC_IN);
        button.setContentDescription(s(description));
        button.setPadding(dp(14), dp(14), dp(14), dp(14));
        TypedValue value = new TypedValue();
        if (activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, value, true))
            button.setBackgroundResource(value.resourceId);
        return button;
    }

    private Drawable roundDrawable(int fill, int radius, int stroke, int strokeWidth) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(radius);
        if (strokeWidth > 0) drawable.setStroke(strokeWidth, stroke);
        return drawable;
    }

    private FrameLayout.LayoutParams panelLayoutParams() {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(WRAP, WRAP);
        params.gravity = Gravity.TOP | Gravity.START;
        return params;
    }

    private Drawable panelBackground(boolean landscape) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.WHITE);
        float radius = dp(24);
        drawable.setCornerRadii(landscape
            ? new float[]{0, 0, radius, radius, radius, radius, 0, 0}
            : new float[]{radius, radius, radius, radius, 0, 0, 0, 0});
        return drawable;
    }
    private void updatePanelLayout(boolean animate) {
        if (panel == null || getWidth() <= 0 || getHeight() <= 0) return;
        boolean landscape = isLandscape();
        int width = landscape ? Math.min(dp(PANEL_MAX_WIDTH_DP), Math.max(dp(PANEL_MIN_WIDTH_DP), getWidth() * 44 / 100)) : getWidth();
        width = Math.min(width, Math.max(dp(280), getWidth() - dp(12)));
        int height = landscape || panelExpanded ? getHeight() : Math.max(dp(320), getHeight() * PANEL_PORTION / 100);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height);
        params.gravity = landscape ? Gravity.TOP | Gravity.START : Gravity.BOTTOM | Gravity.START;
        panel.setLayoutParams(params);
        panel.setBackground(panelBackground(landscape));
        if (!animate) {
            panel.setTranslationX(0);
            panel.setTranslationY(0);
        }
    }

    private boolean isLandscape() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    private FrameLayout.LayoutParams bubbleLayoutParams() {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(BUBBLE_SIZE_DP), dp(BUBBLE_SIZE_DP));
        params.gravity = Gravity.TOP | Gravity.START;
        return params;
    }

    private LinearLayout.LayoutParams iconParams() {
        return new LinearLayout.LayoutParams(dp(56), dp(56));
    }

    private FrameLayout.LayoutParams fillParams() {
        return new FrameLayout.LayoutParams(MATCH, MATCH);
    }

    private LinearLayout.LayoutParams verticalWeightParams() {
        return new LinearLayout.LayoutParams(MATCH, 0, 1);
    }

    private LinearLayout.LayoutParams weightParams(int size, float weight) {
        return new LinearLayout.LayoutParams(size, size == 0 ? MATCH : size, weight);
    }

    private String s(int id) { return activity.getString(id); }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private String s(int id, Object... arguments) { return activity.getString(id, arguments); }
    private final Runnable refreshRunnable = new Runnable() {
        @Override public void run() {
            beginNativeRefresh();
            mainHandler.postDelayed(this, 700L);
        }
    };

    private final class WatchEntry {
        final int id;
        final String expression;
        boolean pinned;
        TextView valueView;
        WatchEntry(int id, String expression) { this.id = id; this.expression = expression; }
    }

}
