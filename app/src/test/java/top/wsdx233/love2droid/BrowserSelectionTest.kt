package top.wsdx233.love2droid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserSelectionTest {
    private val paths = listOf("alpha", "beta", "gamma", "delta")

    @Test
    fun rangeIncludesBothEndpointsInVisibleOrder() {
        assertEquals(
            listOf("beta", "gamma", "delta"),
            inclusiveSelectionRange(paths, "beta", "delta")?.toList(),
        )
        assertEquals(
            listOf("beta", "gamma", "delta"),
            inclusiveSelectionRange(paths, "delta", "beta")?.toList(),
        )
    }

    @Test
    fun rangeRejectsMissingAnchorOrTarget() {
        assertNull(inclusiveSelectionRange(paths, "missing", "delta"))
        assertNull(inclusiveSelectionRange(paths, "alpha", "missing"))
    }

    @Test
    fun inverseKeepsVisibleOrderAndExcludesSelectedPaths() {
        assertEquals(
            listOf("alpha", "gamma"),
            invertedSelection(paths, setOf("beta", "delta")).toList(),
        )
    }
}
