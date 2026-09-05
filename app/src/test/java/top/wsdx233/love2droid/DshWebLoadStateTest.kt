package top.wsdx233.love2droid

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DshWebLoadStateTest {
    @Test
    fun loadingSpansServiceStartupAndAuthenticatedNavigation() {
        val state = DshWebLoadState()
        assertFalse(state.isLoading)
        state.waitForService()
        state.start("about:blank")
        state.finish("about:blank", "about:blank")
        assertTrue(state.isLoading)
        state.start("http://127.0.0.1:3080/?token=complete")
        assertTrue(state.isLoading)
        state.finish("http://127.0.0.1:3080/?token=complete", "http://127.0.0.1:3080/")
        assertTrue(state.isLoading)
        state.finish("http://127.0.0.1:3080/", "http://127.0.0.1:3080/")
        assertFalse(state.isLoading)
    }

    @Test
    fun mainFrameFailureFinishesLoadingAndNavigationCanRestart() {
        val state = DshWebLoadState()
        val url = "http://127.0.0.1:3080/"
        state.start(url)
        state.stop()
        assertFalse(state.isLoading)
        state.start(url)
        assertTrue(state.isLoading)
        state.finish(url, url)
        assertFalse(state.isLoading)
    }

    @Test
    fun startupFailureStopsLoadingAndCanBeRetried() {
        val state = DshWebLoadState()
        state.waitForService()
        state.stop()
        state.start("about:blank")
        state.finish("about:blank", "about:blank")
        assertFalse(state.isLoading)
        state.waitForService()
        assertTrue(state.isLoading)
    }
}
