package top.wsdx233.love2droid.runtime;

import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.view.WindowManager;

import java.util.ArrayList;
import top.wsdx233.love2droid.R;

/**
 * Product entry point for the upstream LÖVE Android runtime.
 * The base activity owns SDL/native library initialization and content URI handling.
 */
public class LoveGameActivity extends org.love2d.android.GameActivity {
    public static final String EXTRA_DEBUG_BREAKPOINT_FILES = "debugBreakpointFiles";
    public static final String EXTRA_DEBUG_BREAKPOINT_LINES = "debugBreakpointLines";
    public static final String EXTRA_DEBUG_PROJECT_ID = "debugProjectId";

    private GameDebugOverlay debugOverlay;

    public native void nativeQueueDebugCode(byte[] code);

    public native byte[] nativeGetDebugLogs();

    public native byte[] nativeGetDebugState();

    public native void nativeClearDebugLogs();

    public native void nativeResetDebugState();

    public native void nativeSetBreakpoint(String file, int line, String condition);



    public native void nativeResumeDebug();

    public native void nativeStepDebug(int mode);

    public native void nativePauseDebug();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_Love2Droid_Game);
        Intent intent = getIntent();
        Uri gameUri = intent == null ? null : intent.getData();
        String projectId = intent == null ? null : intent.getStringExtra(EXTRA_DEBUG_PROJECT_ID);
        ArrayList<String> breakpointFiles = intent == null
            ? null : intent.getStringArrayListExtra(EXTRA_DEBUG_BREAKPOINT_FILES);
        ArrayList<Integer> breakpointLines = intent == null
            ? null : intent.getIntegerArrayListExtra(EXTRA_DEBUG_BREAKPOINT_LINES);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
        if (!mBrokenLibraries) {
            nativeResetDebugState();
            if (breakpointFiles != null && breakpointLines != null) {
                int count = Math.min(breakpointFiles.size(), breakpointLines.size());
                for (int index = 0; index < count; ++index) {
                    Integer line = breakpointLines.get(index);
                    if (line != null && line > 0) {
                        nativeSetBreakpoint(breakpointFiles.get(index), line, "");
                    }
                }
            }
            debugOverlay = new GameDebugOverlay(this, gameUri, projectId);
            debugOverlay.attach();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (debugOverlay != null) {
            debugOverlay.constrainBubbleToWindow();
        }
    }

    @Override
    protected void onDestroy() {
        if (debugOverlay != null) {
            debugOverlay.detach();
            debugOverlay = null;
        }
        super.onDestroy();
    }
}
