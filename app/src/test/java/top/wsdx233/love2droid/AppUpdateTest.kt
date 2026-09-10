package top.wsdx233.love2droid

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateTest {
    @Test
    fun numericVersionsDoNotUseLexicographicOrderingOrOfferDowngrades() {
        assertTrue(release("v0.1.10", "0.1.9") is AppUpdateResult.Available)
        assertEquals(AppUpdateResult.UpToDate, release("v0.1.9", "0.1.10"))
        assertEquals(AppUpdateResult.UpToDate, release("v0.1.2", "0.1.2+local.4"))
        assertEquals(AppUpdateResult.UpToDate, release("V1.2.0", "1.2"))
        assertTrue(release("v999999999999999999999.0.0", "1.0.0") is AppUpdateResult.Available)
    }

    @Test
    fun stableVersionsAndPrereleaseIdentifiersFollowVersionPrecedence() {
        assertTrue(release("v1.0.0", "1.0.0-rc.10") is AppUpdateResult.Available)
        assertEquals(AppUpdateResult.UpToDate, release("v1.0.0-rc.10", "1.0.0"))
        assertTrue(release("v1.0.0-rc.10", "1.0.0-rc.2") is AppUpdateResult.Available)
        assertTrue(release("v1.0.0-beta", "1.0.0-alpha.1") is AppUpdateResult.Available)
        assertTrue(release("v1.0.0--1", "1.0.0-1") is AppUpdateResult.Available)
    }

    @Test
    fun unpublishedAndPrereleaseEntriesAreNotOffered() {
        assertEquals(AppUpdateResult.NoRelease, release("v2.0.0", "1.0.0", draft = true))
        assertEquals(AppUpdateResult.NoRelease, release("v2.0.0-rc.1", "1.0.0", prerelease = true))
    }

    @Test
    fun invalidVersionsAndMalformedResponsesAreFailuresNotUpdates() {
        assertThrows(IOException::class.java) { release("latest", "1.0.0") }
        assertThrows(IOException::class.java) { release("v2.0.0", "unknown") }
        assertThrows(Exception::class.java) { AppUpdateChecker.parseRelease("{}", "1.0.0") }
    }

    @Test
    fun releaseLinksStayOnTheRepositoryEvenIfTheResponseSuppliesAnotherUrl() {
        val result = release("v1.1.0", "1.0.0") as AppUpdateResult.Available
        assertEquals("https://github.com/wsdx233/Love2Droid/releases/tag/v1.1.0", result.releaseUrl)
    }

    @Test
    fun automaticFailuresAreSilentAndManualChecksCanRetryWithinTheSameStartup() = runBlocking {
        var offline = true
        val session = AppUpdateSession(this) {
            if (offline) throw IOException("offline")
            AppUpdateResult.UpToDate
        }
        session.checkAtStartup(true)
        yield()
        assertFalse(session.state.value.checking)
        assertNull(session.takeNotice(true))
        session.checkAtStartup(true)
        assertFalse(session.state.value.checking)
        session.checkManually()
        yield()
        assertEquals(AppUpdateResult.Failed, session.takeNotice(true))
        offline = false
        session.checkManually()
        yield()
        assertEquals(AppUpdateResult.UpToDate, session.takeNotice(true))
    }

    @Test
    fun pendingAutomaticUpdatesAreConsumedOnceAndRespectAChangedSwitch() = runBlocking {
        val pending = CompletableDeferred<AppUpdateResult>()
        val update = AppUpdateResult.Available("v2.0.0", "https://github.com/wsdx233/Love2Droid/releases/tag/v2.0.0")
        val session = AppUpdateSession(this) { pending.await() }
        session.checkAtStartup(true)
        pending.complete(update)
        yield()
        assertEquals(update, session.takeNotice(true))
        assertNull(session.takeNotice(true))
        session.checkAtStartup(true)
        assertFalse(session.state.value.checking)

        val disabledWhileChecking = AppUpdateSession(this) { update }
        disabledWhileChecking.checkAtStartup(true)
        yield()
        assertNull(disabledWhileChecking.takeNotice(false))
    }

    @Test
    fun disablingAutomaticChecksDoesNotDisableManualChecks() = runBlocking {
        val session = AppUpdateSession(this) { AppUpdateResult.NoRelease }
        session.checkAtStartup(false)
        assertFalse(session.state.value.checking)
        session.checkManually()
        yield()
        assertEquals(AppUpdateResult.NoRelease, session.takeNotice(false))
    }

    @Test
    fun manualCheckSharesAnInflightRequestAndReceivesItsOtherwiseSilentResult() = runBlocking {
        val pending = CompletableDeferred<AppUpdateResult>()
        var requests = 0
        val session = AppUpdateSession(this) {
            requests++
            pending.await()
        }
        session.checkAtStartup(true)
        session.checkManually()
        session.checkManually()
        yield()
        pending.complete(AppUpdateResult.UpToDate)
        yield()
        assertEquals(1, requests)
        assertEquals(AppUpdateResult.UpToDate, session.takeNotice(false))
        assertNull(session.takeNotice(false))
    }

    private fun release(
        tag: String,
        current: String,
        draft: Boolean = false,
        prerelease: Boolean = false,
    ): AppUpdateResult = AppUpdateChecker.parseRelease(
        JSONObject()
            .put("tag_name", tag)
            .put("draft", draft)
            .put("prerelease", prerelease)
            .put("html_url", "https://untrusted.invalid/download")
            .toString(),
        current,
    )
}
