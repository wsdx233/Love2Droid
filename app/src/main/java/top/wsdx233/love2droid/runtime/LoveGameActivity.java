package top.wsdx233.love2droid.runtime;

import android.content.res.Configuration;
import android.os.Bundle;

/**
 * Product entry point for the upstream LÖVE Android runtime.
 * The base activity owns SDL/native library initialization and content URI handling.
 */
public class LoveGameActivity extends org.love2d.android.GameActivity {
    private GameDebugOverlay debugOverlay;

    public native void nativeQueueDebugCode(byte[] code);

    public native byte[] nativeGetDebugLogs();

    public native void nativeResetDebugState();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!mBrokenLibraries) {
            nativeResetDebugState();
            debugOverlay = new GameDebugOverlay(this);
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
    public void setOrientationBis(int width, int height, boolean resizable, String hint) {
        // Android games follow the device sensor unless the game explicitly
        // restricts the allowed orientations in the SDL hint.
        super.setOrientationBis(width, height, true, hint);
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
