package top.wsdx233.love2droid.runtime;

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
    protected void onDestroy() {
        if (debugOverlay != null) {
            debugOverlay.detach();
            debugOverlay = null;
        }
        super.onDestroy();
    }
}
