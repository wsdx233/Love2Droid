package top.wsdx233.love2droid.runtime;

final class GameDebugBubblePosition {
    private GameDebugBubblePosition() {
    }

    static boolean isOrientationChange(
        int oldWidth,
        int oldHeight,
        int newWidth,
        int newHeight
    ) {
        return oldWidth > 0
            && oldHeight > 0
            && newWidth > 0
            && newHeight > 0
            && (oldWidth > oldHeight) != (newWidth > newHeight);
    }

    static float dockedRightX(int windowWidth, int bubbleWidth, int edgeMargin) {
        return Math.max(0, windowWidth - bubbleWidth - edgeMargin);
    }

    static float mapVerticalPosition(
        float y,
        int oldWindowHeight,
        int newWindowHeight,
        int bubbleHeight
    ) {
        float oldRange = Math.max(0, oldWindowHeight - bubbleHeight);
        float newRange = Math.max(0, newWindowHeight - bubbleHeight);
        if (oldRange == 0) {
            return newRange / 2f;
        }
        float fraction = Math.max(0f, Math.min(y, oldRange)) / oldRange;
        return fraction * newRange;
    }
}
