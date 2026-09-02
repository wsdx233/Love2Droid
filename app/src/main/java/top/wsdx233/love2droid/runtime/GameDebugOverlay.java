package top.wsdx233.love2droid.runtime;

import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
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
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.navigation.NavigationBarView;
import com.google.android.material.navigationrail.NavigationRailView;
import com.google.android.material.tabs.TabLayout;

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
    private static final int TAB_CONSOLE_ID = 0x4c324401;
    private static final int TAB_WATCH_ID = 0x4c324402;
    private static final int TAB_BREAKPOINTS_ID = 0x4c324403;
    private static final int LOG_TOOL_NONE = 0;
    private static final int LOG_TOOL_SEARCH = 1;
    private static final int LOG_TOOL_FILTER = 2;
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
    private final List<WatchEntry> watches = new ArrayList<>();
    private final DebugWatchStore watchStore;

    private final Map<Integer, String> watchValues = new HashMap<>();
    private ImageButton fullscreenButton;

    private FrameLayout scrim;
    private LinearLayout panel;
    private LinearLayout panelHeader;
    private LinearLayout panelBody;
    private ImageButton pauseResumeButton;
    private LinearLayout hud;
    private FrameLayout content;
    private TabLayout tabLayout;
    private NavigationRailView navigationRail;
    private LinearLayout logToolPanel;
    private View logSearchPanel;
    private View logFilterPanel;
    private ImageButton logSearchToggle;
    private ImageButton logFilterToggle;
    private ImageButton logFollowButton;
    private ImageButton replExpandButton;
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
    private boolean syncingTabSelection;
    private boolean replExpanded;
    private int visibleLogTool;
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

    GameDebugOverlay(LoveGameActivity activity, Uri gameUri, String projectId) {
        super(activity);
        this.activity = activity;
        this.gameUri = gameUri;
        watchStore = new DebugWatchStore(activity, projectId);
        lastOrientation = getResources().getConfiguration().orientation;
        setClipToPadding(false);
        setFocusable(false);

        bubble = new ImageButton(activity);
        bubble.setImageDrawable(ContextCompat.getDrawable(activity, R.drawable.ic_build));
        bubble.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
        bubble.setBackground(rippleBackground(
            roundDrawable(Color.argb(220, 0, 0, 0), dp(18), Color.argb(80, 255, 255, 255), dp(1)),
            dp(18), Color.argb(80, 255, 255, 255)));
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
        restoreWatches();
        renderHud();
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
        boolean orientationChanged = orientation != lastOrientation;
        if (orientationChanged && oldWidth > 0 && oldHeight > 0) {
            int bubbleWidth = bubble.getWidth() > 0 ? bubble.getWidth() : dp(BUBBLE_SIZE_DP);
            int bubbleHeight = bubble.getHeight() > 0 ? bubble.getHeight() : dp(BUBBLE_SIZE_DP);
            float y = GameDebugBubblePosition.mapVerticalPosition(
                bubble.getY(), oldHeight, height, bubbleHeight);
            moveBubble(GameDebugBubblePosition.dockedRightX(
                width, bubbleWidth, dp(EDGE_MARGIN_DP)), y);
            if (panel != null)
                configurePanelStructure(panel, orientation == Configuration.ORIENTATION_LANDSCAPE);
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
        view.getBackground().setHotspot(event.getX(), event.getY());
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                view.setPressed(true);
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
                if (moved) {
                    view.setPressed(false);
                    moveBubble(downX + dx, downY + dy);
                }
                return true;
            case MotionEvent.ACTION_UP:
                view.setPressed(false);
                if (!moved)
                    showPanel();
                else
                    moveBubble(bubble.getX(), bubble.getY());
                return true;
            case MotionEvent.ACTION_CANCEL:
                view.setPressed(false);
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
        updateFullscreenButton();
        if (scrim == null) {
            scrim = new FrameLayout(activity);
            scrim.setBackgroundColor(Color.argb(138, 0, 0, 0));
            scrim.setClickable(true);
            scrim.setOnClickListener(view -> hidePanel());
            scrim.setAlpha(0f);
            addView(scrim, new FrameLayout.LayoutParams(MATCH, MATCH));
            panel = buildPanel();
            panel.setVisibility(INVISIBLE);
            addView(panel, panelLayoutParams());
        }

        panel.animate().cancel();
        scrim.animate().cancel();
        updatePanelLayout(false);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) panel.getLayoutParams();
        boolean landscape = isLandscape();
        panel.setTranslationX(landscape ? -params.width - dp(24) : 0);
        panel.setTranslationY(landscape ? 0 : params.height + dp(24));
        scrim.setAlpha(0f);
        bubble.setVisibility(INVISIBLE);
        scrim.setVisibility(VISIBLE);
        panel.setVisibility(VISIBLE);
        scrim.animate().alpha(1f).setDuration(180L).start();
        panel.animate().translationX(0).translationY(0)
            .setDuration(280L).start();
        showTab(activeTab);
        beginNativeRefresh();
    }

    private void hidePanel() {
        if (!panelOpen || panel == null)
            return;
        panelOpen = false;
        boolean landscape = isLandscape();
        panel.animate().cancel();
        scrim.animate().cancel();
        scrim.animate().alpha(0f).setDuration(180L).start();
        panel.animate()
            .translationX(landscape ? -panel.getWidth() - dp(24) : 0)
            .translationY(landscape ? 0 : panel.getHeight() + dp(24))
            .setDuration(220L)
            .withEndAction(() -> {
                panel.setVisibility(INVISIBLE);
                scrim.setVisibility(INVISIBLE);
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
        root.setPadding(0, 0, 0, 0);
        root.setBackground(panelBackground(isLandscape()));
        root.setElevation(dp(12));
        root.setClickable(true);
        root.setFocusable(true);
        root.setOnClickListener(view -> { });

        panelHeader = buildPanelHeader();
        tabLayout = buildTabLayout();
        navigationRail = buildNavigationRail();
        panelBody = new LinearLayout(activity);
        panelBody.setOrientation(LinearLayout.VERTICAL);
        panelBody.setBackgroundColor(COLOR_SURFACE);

        content = new FrameLayout(activity);
        content.setBackgroundColor(COLOR_SURFACE);
        content.addView(buildLogsPage(), fillParams());
        content.addView(buildWatchPage(), fillParams());
        content.addView(buildBreakpointPage(), fillParams());
        configurePanelStructure(root, isLandscape());
        showTab(activeTab);
        return root;
    }

    private LinearLayout buildPanelHeader() {
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(20), dp(10), dp(12), 0);
        header.setBackgroundColor(COLOR_SURFACE);

        View handle = new View(activity);
        handle.setTag("debug_panel_handle");
        handle.setBackground(roundDrawable(Color.rgb(120, 116, 126), dp(3), Color.TRANSPARENT, 0));
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(dp(32), dp(4));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        handleParams.bottomMargin = dp(8);
        header.addView(handle, handleParams);

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
        fullscreenButton = iconButton(R.drawable.ic_open_in_full, R.string.debug_fullscreen);
        fullscreenButton.setOnClickListener(view -> {
            panelExpanded = !panelExpanded;
            updateFullscreenButton();
            updatePanelLayout(true);
        });
        titleRow.addView(fullscreenButton, iconParams());
        ImageButton exit = iconButton(R.drawable.ic_exit, R.string.debug_exit_game);
        exit.setOnClickListener(view -> activity.finish());
        titleRow.addView(exit, iconParams());
        ImageButton close = iconButton(R.drawable.ic_close, R.string.debug_close);
        close.setOnClickListener(view -> hidePanel());
        titleRow.addView(close, iconParams());
        header.addView(titleRow, new LinearLayout.LayoutParams(MATCH, dp(60)));
        return header;
    }

    private TabLayout buildTabLayout() {
        TabLayout tabs = new TabLayout(activity);
        tabs.setBackgroundColor(COLOR_SURFACE);
        tabs.setTabMode(TabLayout.MODE_FIXED);
        tabs.setTabGravity(TabLayout.GRAVITY_FILL);
        tabs.setSelectedTabIndicatorColor(COLOR_PRIMARY);
        tabs.setTabIconTint(tabIconColors());
        tabs.setTabRippleColor(ColorStateList.valueOf(Color.argb(28, 103, 80, 164)));
        tabs.addTab(tabs.newTab().setIcon(R.drawable.ic_terminal).setContentDescription(R.string.debug_tab_console));
        tabs.addTab(tabs.newTab().setIcon(R.drawable.ic_visibility).setContentDescription(R.string.debug_tab_watch));
        tabs.addTab(tabs.newTab().setIcon(R.drawable.ic_bug_report).setContentDescription(R.string.debug_tab_breakpoints));
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                if (!syncingTabSelection)
                    showTab(tab.getPosition());
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) { }
            @Override public void onTabReselected(TabLayout.Tab tab) { }
        });
        return tabs;
    }

    private NavigationRailView buildNavigationRail() {
        NavigationRailView rail = new NavigationRailView(activity);
        rail.setBackgroundColor(COLOR_SURFACE);
        rail.setLabelVisibilityMode(NavigationBarView.LABEL_VISIBILITY_UNLABELED);
        rail.setItemIconTintList(navigationIconColors());
        rail.setItemRippleColor(ColorStateList.valueOf(Color.argb(28, 103, 80, 164)));
        rail.setItemActiveIndicatorColor(ColorStateList.valueOf(COLOR_PRIMARY_CONTAINER));
        rail.setItemActiveIndicatorEnabled(true);
        rail.setMenuGravity(Gravity.TOP);
        rail.setPadding(0, dp(12), 0, 0);
        rail.getMenu().add(0, TAB_CONSOLE_ID, 0, R.string.debug_tab_console).setIcon(R.drawable.ic_terminal);
        rail.getMenu().add(0, TAB_WATCH_ID, 1, R.string.debug_tab_watch).setIcon(R.drawable.ic_visibility);
        rail.getMenu().add(0, TAB_BREAKPOINTS_ID, 2, R.string.debug_tab_breakpoints).setIcon(R.drawable.ic_bug_report);
        rail.setOnItemSelectedListener(item -> {
            if (!syncingTabSelection)
                showTab(tabIndexForId(item.getItemId()));
            return true;
        });
        return rail;
    }

    private void configurePanelStructure(LinearLayout root, boolean landscape) {
        root.removeAllViews();
        panelBody.removeAllViews();
        View handle = panelHeader.findViewWithTag("debug_panel_handle");
        if (handle != null)
            handle.setVisibility(landscape ? GONE : VISIBLE);
        if (landscape) {
            root.setOrientation(LinearLayout.HORIZONTAL);
            panelBody.addView(panelHeader, new LinearLayout.LayoutParams(MATCH, WRAP));
            panelBody.addView(content, verticalWeightParams());
            root.addView(navigationRail, new LinearLayout.LayoutParams(dp(76), MATCH));
            root.addView(panelBody, new LinearLayout.LayoutParams(0, MATCH, 1));
        } else {
            root.setOrientation(LinearLayout.VERTICAL);
            root.addView(panelHeader, new LinearLayout.LayoutParams(MATCH, WRAP));
            root.addView(tabLayout, new LinearLayout.LayoutParams(MATCH, dp(56)));
            root.addView(content, verticalWeightParams());
        }
    }

    private void updateFullscreenButton() {
        if (fullscreenButton == null)
            return;
        fullscreenButton.setImageResource(panelExpanded ? R.drawable.ic_close_fullscreen : R.drawable.ic_open_in_full);
        fullscreenButton.setContentDescription(s(panelExpanded ? R.string.debug_exit_fullscreen : R.string.debug_fullscreen));
    }

    private View buildLogsPage() {
        LinearLayout page = page();
        logsScroll = new ScrollView(activity);
        logsScroll.setFillViewport(false);
        logsText = text("", 13, COLOR_INK, Typeface.MONOSPACE);
        logsText.setGravity(Gravity.TOP | Gravity.START);
        logsText.setTextIsSelectable(true);
        logsText.setMovementMethod(ScrollingMovementMethod.getInstance());
        logsText.setPadding(dp(16), dp(8), dp(16), dp(12));
        logsScroll.addView(logsText, new ScrollView.LayoutParams(MATCH, WRAP));
        page.addView(logsScroll, verticalWeightParams());

        LinearLayout.LayoutParams consoleParams = new LinearLayout.LayoutParams(MATCH, WRAP);
        consoleParams.setMargins(dp(12), dp(4), dp(12), dp(12));
        page.addView(buildConsoleControls(), consoleParams);
        return page;
    }

    private View buildConsoleControls() {
        MaterialCardView card = new MaterialCardView(activity);
        card.setCardBackgroundColor(Color.WHITE);
        card.setRadius(dp(26));
        card.setStrokeColor(Color.rgb(202, 196, 208));
        card.setStrokeWidth(dp(1));
        card.setCardElevation(0);

        LinearLayout controls = new LinearLayout(activity);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(6), dp(6), dp(6), dp(6));

        logToolPanel = new LinearLayout(activity);
        logToolPanel.setOrientation(LinearLayout.VERTICAL);
        logToolPanel.setVisibility(GONE);

        LinearLayout searchRow = new LinearLayout(activity);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setPadding(dp(6), dp(4), dp(6), dp(4));
        logSearch = edit(s(R.string.debug_log_search), false);
        searchRow.addView(logSearch, new LinearLayout.LayoutParams(MATCH, dp(52)));
        logSearchPanel = searchRow;
        logToolPanel.addView(searchRow, new LinearLayout.LayoutParams(MATCH, dp(60)));

        HorizontalScrollView filterScroll = new HorizontalScrollView(activity);
        filterScroll.setHorizontalScrollBarEnabled(false);
        ChipGroup filterGroup = new ChipGroup(activity);
        filterGroup.setSingleLine(true);
        filterGroup.setSingleSelection(true);
        filterGroup.setSelectionRequired(true);
        filterGroup.setPadding(dp(6), dp(2), dp(6), dp(2));
        addFilter(filterGroup, R.string.debug_filter_all, "all");
        addFilter(filterGroup, R.string.debug_filter_info, "info");
        addFilter(filterGroup, R.string.debug_filter_warn, "warn");
        addFilter(filterGroup, R.string.debug_filter_error, "error");
        filterScroll.addView(filterGroup, new HorizontalScrollView.LayoutParams(WRAP, MATCH));
        logFilterPanel = filterScroll;
        logToolPanel.addView(filterScroll, new LinearLayout.LayoutParams(MATCH, dp(56)));
        controls.addView(logToolPanel, new LinearLayout.LayoutParams(MATCH, WRAP));

        FrameLayout inputFrame = new FrameLayout(activity);
        replInput = edit(s(R.string.debug_repl_hint), true);
        replInput.setOnFocusChangeListener(null);
        replInput.setBackgroundColor(Color.TRANSPARENT);
        replInput.setGravity(Gravity.TOP | Gravity.START);
        replInput.setTypeface(Typeface.MONOSPACE);
        replInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        replInput.setPadding(dp(14), dp(12), dp(58), dp(10));
        inputFrame.addView(replInput, new FrameLayout.LayoutParams(MATCH, dp(92)));
        replExpandButton = iconButton(R.drawable.ic_expand_content, R.string.debug_expand_repl);
        replExpandButton.setOnClickListener(view -> toggleReplExpanded());
        FrameLayout.LayoutParams expandParams = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP | Gravity.END);
        expandParams.setMargins(0, dp(2), dp(2), 0);
        inputFrame.addView(replExpandButton, expandParams);
        controls.addView(inputFrame, new LinearLayout.LayoutParams(MATCH, WRAP));

        LinearLayout toolbar = new LinearLayout(activity);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(2), 0, dp(2), dp(2));
        logSearchToggle = iconButton(R.drawable.ic_search, R.string.debug_log_search);
        logSearchToggle.setOnClickListener(view -> toggleLogTool(LOG_TOOL_SEARCH));
        toolbar.addView(logSearchToggle, iconParams());
        logFilterToggle = iconButton(R.drawable.ic_filter_alt, R.string.debug_log_filter);
        logFilterToggle.setOnClickListener(view -> toggleLogTool(LOG_TOOL_FILTER));
        toolbar.addView(logFilterToggle, iconParams());
        ImageButton clear = iconButton(R.drawable.ic_delete, R.string.debug_clear_logs);
        clear.setOnClickListener(view -> {
            activity.nativeClearDebugLogs();
            latestLogs = "";
            renderLogs();
        });
        toolbar.addView(clear, iconParams());
        logFollowButton = iconButton(R.drawable.ic_lock_open, R.string.debug_lock_logs);
        logFollowButton.setOnClickListener(view -> {
            logsAutoFollow = !logsAutoFollow;
            updateLogFollowButton();
        });
        toolbar.addView(logFollowButton, iconParams());
        toolbar.addView(new View(activity), new LinearLayout.LayoutParams(0, 1, 1));
        ImageButton run = iconButton(R.drawable.ic_arrow_upward, R.string.debug_execute);
        run.setBackground(rippleBackground(
            roundDrawable(COLOR_PRIMARY, dp(20), COLOR_PRIMARY, 0),
            dp(20), Color.argb(60, 255, 255, 255)));
        run.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
        run.setOnClickListener(view -> submitRepl());
        toolbar.addView(run, iconParams());
        controls.addView(toolbar, new LinearLayout.LayoutParams(MATCH, dp(58)));
        card.addView(controls, new MaterialCardView.LayoutParams(MATCH, WRAP));

        logSearch.addTextChangedListener(simpleWatcher(this::renderLogs));
        replInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable editable) { highlightLua(editable); }
        });
        updateLogToolVisibility();
        updateLogFollowButton();
        return card;
    }

    private void toggleLogTool(int tool) {
        visibleLogTool = visibleLogTool == tool ? LOG_TOOL_NONE : tool;
        updateLogToolVisibility();
        if (visibleLogTool == LOG_TOOL_SEARCH)
            logSearch.post(logSearch::requestFocus);
    }

    private void updateLogToolVisibility() {
        if (logToolPanel == null)
            return;
        logSearchPanel.setVisibility(visibleLogTool == LOG_TOOL_SEARCH ? VISIBLE : GONE);
        logFilterPanel.setVisibility(visibleLogTool == LOG_TOOL_FILTER ? VISIBLE : GONE);
        logToolPanel.setVisibility(visibleLogTool == LOG_TOOL_NONE ? GONE : VISIBLE);
        setIconButtonActive(logSearchToggle, visibleLogTool == LOG_TOOL_SEARCH);
        setIconButtonActive(logFilterToggle, visibleLogTool == LOG_TOOL_FILTER);
    }

    private void updateLogFollowButton() {
        if (logFollowButton == null)
            return;
        logFollowButton.setImageResource(logsAutoFollow ? R.drawable.ic_lock_open : R.drawable.ic_lock);
        logFollowButton.setContentDescription(s(logsAutoFollow ? R.string.debug_lock_logs : R.string.debug_unlock_logs));
        setIconButtonActive(logFollowButton, !logsAutoFollow);
    }

    private void toggleReplExpanded() {
        replExpanded = !replExpanded;
        int availableHeight = panel == null || panel.getHeight() == 0 ? getHeight() : panel.getHeight();
        int height = replExpanded
            ? Math.max(dp(160), Math.min(dp(280), availableHeight / 2))
            : dp(92);
        replInput.getLayoutParams().height = height;
        replInput.requestLayout();
        replExpandButton.setImageResource(replExpanded ? R.drawable.ic_collapse_content : R.drawable.ic_expand_content);
        replExpandButton.setContentDescription(s(replExpanded ? R.string.debug_collapse_repl : R.string.debug_expand_repl));
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



    private void showTab(int index) {
        activeTab = Math.max(0, Math.min(2, index));
        if (content != null) {
            for (int i = 0; i < content.getChildCount(); ++i)
                content.getChildAt(i).setVisibility(i == activeTab ? VISIBLE : GONE);
        }
        syncingTabSelection = true;
        if (tabLayout != null && tabLayout.getSelectedTabPosition() != activeTab) {
            TabLayout.Tab tab = tabLayout.getTabAt(activeTab);
            if (tab != null)
                tab.select();
        }
        int navigationId = tabIdForIndex(activeTab);
        if (navigationRail != null && navigationRail.getSelectedItemId() != navigationId)
            navigationRail.setSelectedItemId(navigationId);
        syncingTabSelection = false;
        beginNativeRefresh();
    }

    private int tabIdForIndex(int index) {
        if (index == 1) return TAB_WATCH_ID;
        if (index == 2) return TAB_BREAKPOINTS_ID;
        return TAB_CONSOLE_ID;
    }

    private int tabIndexForId(int id) {
        if (id == TAB_WATCH_ID) return 1;
        if (id == TAB_BREAKPOINTS_ID) return 2;
        return 0;
    }

    private ColorStateList tabIconColors() {
        return new ColorStateList(
            new int[][]{new int[]{android.R.attr.state_selected}, new int[]{}},
            new int[]{COLOR_PRIMARY, COLOR_MUTED});
    }

    private ColorStateList navigationIconColors() {
        return new ColorStateList(
            new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
            new int[]{COLOR_PRIMARY, COLOR_MUTED});
    }

    private void addFilter(ChipGroup row, int label, String filter) {
        Chip chip = new Chip(activity);
        chip.setText(s(label));
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        chip.setTextColor(COLOR_INK);
        chip.setCheckable(true);
        chip.setCheckedIconVisible(false);
        chip.setChecked("all".equals(filter));
        chip.setChipBackgroundColor(new ColorStateList(
            new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
            new int[]{COLOR_PRIMARY_CONTAINER, Color.TRANSPARENT}));
        chip.setRippleColor(ColorStateList.valueOf(Color.argb(28, 103, 80, 164)));
        chip.setOnClickListener(view -> {
            currentFilter = filter;
            renderLogs();
        });
        row.addView(chip, new ChipGroup.LayoutParams(WRAP, dp(48)));
    }

    private void addControl(LinearLayout row, int icon, int label, Runnable action) {
        TextView button = actionText(s(label), icon, false);
        button.setOnClickListener(view -> action.run());
        row.addView(button, new LinearLayout.LayoutParams(WRAP, dp(58)));
    }
    private void restoreWatches() {
        for (DebugWatchStore.Watch restored : watchStore.load()) {
            WatchEntry entry = new WatchEntry(nextWatchId++, restored.expression);
            entry.pinned = restored.pinned;
            watches.add(entry);
        }
    }

    private void persistWatches() {
        List<DebugWatchStore.Watch> stored = new ArrayList<>(watches.size());
        for (WatchEntry entry : watches)
            stored.add(new DebugWatchStore.Watch(entry.expression, entry.pinned));
        if (!watchStore.save(stored))
            Toast.makeText(activity, R.string.debug_watch_save_failed, Toast.LENGTH_SHORT).show();
    }




    private void addWatch() {
        String expression = watchInput == null ? "" : watchInput.getText().toString().trim();
        if (expression.isEmpty()) {
            watchInput.setError(s(R.string.debug_watch_empty));
            return;
        }
        if (expression.length() > DebugWatchStore.MAX_EXPRESSION_LENGTH) {
            watchInput.setError(s(R.string.debug_watch_too_long));
            return;
        }
        if (watches.size() >= DebugWatchStore.MAX_WATCHES) {
            watchInput.setError(s(R.string.debug_watch_limit, DebugWatchStore.MAX_WATCHES));
            return;
        }
        WatchEntry entry = new WatchEntry(nextWatchId++, expression);
        watches.add(entry);
        persistWatches();
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
                persistWatches();
                renderWatchRows();
            });
            top.addView(pin, iconParams());
            ImageButton remove = iconButton(R.drawable.ic_close, R.string.debug_remove_watch);
            remove.setOnClickListener(view -> {
                watches.remove(entry);
                watchValues.remove(entry.id);
                persistWatches();
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
        if (detached || (!panelOpen && !hasPinnedWatches()) || !refreshInFlight.compareAndSet(false, true)) return;
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
                boolean requestAllWatches = panelOpen && activeTab == 1;
                if (requestAllWatches || hasPinnedWatches())
                    requestWatchValues(!requestAllWatches);
            });
        });
    }
    private boolean hasPinnedWatches() {
        for (WatchEntry entry : watches) {
            if (entry.pinned) return true;
        }
        return false;
    }

    private void requestWatchValues(boolean pinnedOnly) {
        for (WatchEntry entry : watches) {
            if (pinnedOnly && !entry.pinned) continue;
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
        MaterialButton button = new MaterialButton(activity);
        button.setText(value);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        button.setTextColor(filled ? Color.WHITE : COLOR_INK);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setCornerRadius(dp(18));
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setBackgroundTintList(ColorStateList.valueOf(filled ? COLOR_PRIMARY : Color.TRANSPARENT));
        button.setStrokeColor(ColorStateList.valueOf(filled ? COLOR_PRIMARY : Color.rgb(225, 220, 230)));
        button.setStrokeWidth(filled ? 0 : dp(1));
        if (icon != 0) {
            button.setIconResource(icon);
            button.setIconTint(ColorStateList.valueOf(filled ? Color.WHITE : COLOR_INK));
            button.setIconPadding(dp(8));
        }
        return button;
    }


    private ImageButton iconButton(int icon, int description) {
        ImageButton button = new ImageButton(activity);
        button.setImageResource(icon);
        button.setColorFilter(COLOR_INK, PorterDuff.Mode.SRC_IN);
        button.setContentDescription(s(description));
        button.setPadding(dp(14), dp(14), dp(14), dp(14));
        button.setBackground(rippleBackground(
            roundDrawable(Color.TRANSPARENT, dp(24), Color.TRANSPARENT, 0),
            dp(24), Color.argb(36, 103, 80, 164)));
        return button;
    }

    private void setIconButtonActive(ImageButton button, boolean active) {
        if (button == null)
            return;
        button.setBackground(rippleBackground(
            roundDrawable(active ? COLOR_PRIMARY_CONTAINER : Color.TRANSPARENT, dp(24), Color.TRANSPARENT, 0),
            dp(24), Color.argb(36, 103, 80, 164)));
    }

    private Drawable roundDrawable(int fill, int radius, int stroke, int strokeWidth) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(radius);
        if (strokeWidth > 0) drawable.setStroke(strokeWidth, stroke);
        return drawable;
    }

    private Drawable rippleBackground(Drawable content, int radius, int rippleColor) {
        Drawable mask = roundDrawable(Color.WHITE, radius, Color.TRANSPARENT, 0);
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask);
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
