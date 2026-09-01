package top.wsdx233.love2droid.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameDebugBubblePositionTest {
    @Test
    fun portraitToLandscapeDocksRightAndKeepsVerticalPosition() {
        val bubbleSize = 56
        val oldHeight = 2400
        val newHeight = 1080

        assertTrue(GameDebugBubblePosition.isOrientationChange(1080, oldHeight, 2400, newHeight))
        assertEquals(2328f, GameDebugBubblePosition.dockedRightX(2400, bubbleSize, 16), 0.001f)
        assertEquals(
            (newHeight - bubbleSize) / 2f,
            GameDebugBubblePosition.mapVerticalPosition(
                (oldHeight - bubbleSize) / 2f,
                oldHeight,
                newHeight,
                bubbleSize,
            ),
            0.001f,
        )
    }

    @Test
    fun landscapeToPortraitDocksRightAndKeepsVerticalPosition() {
        val bubbleSize = 56
        val oldHeight = 1080
        val newHeight = 2400

        assertTrue(GameDebugBubblePosition.isOrientationChange(2400, oldHeight, 1080, newHeight))
        assertEquals(1008f, GameDebugBubblePosition.dockedRightX(1080, bubbleSize, 16), 0.001f)
        assertEquals(
            (newHeight - bubbleSize) * 0.25f,
            GameDebugBubblePosition.mapVerticalPosition(
                (oldHeight - bubbleSize) * 0.25f,
                oldHeight,
                newHeight,
                bubbleSize,
            ),
            0.001f,
        )
    }

    @Test
    fun sameOrientationResizeDoesNotTriggerDockingPolicy() {
        assertFalse(GameDebugBubblePosition.isOrientationChange(1080, 2400, 1000, 2200))
    }
}
