package top.wsdx233.love2droid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DshDaemonTest {
    @Test
    fun extractsAuthenticatedLoopbackUrlFromTerminalOutput() {
        val output = "dsh web: http://127.0.0.1:3080/?token=pe3yG4RKEFsaFhKVPw7VlNFj9fzN1tha7Ahuakf8cDs\n"

        assertEquals(
            "http://127.0.0.1:3080/?token=pe3yG4RKEFsaFhKVPw7VlNFj9fzN1tha7Ahuakf8cDs",
            DshDaemon.extractAuthenticatedWebUrl(output),
        )
    }
    @Test
    fun dshStartupActivatesAppPluginBeforeHeadlessDaemon() {
        assertEquals(
            "node ${ProotRuntime.DSH_PLUGIN_GUEST_PATH}/activate.mjs /root/.local/bin/dsh && exec dsh --profile web --no-open --port 3080",
            ProotRuntime.dshStartupCommand(),
        )
    }

    @Test
    fun rejectsNonLoopbackOrUnauthenticatedUrls() {
        assertNull(DshDaemon.extractAuthenticatedWebUrl("http://192.168.1.2:3080/?token=abc"))
        assertNull(DshDaemon.extractAuthenticatedWebUrl("http://127.0.0.1:3080/"))
        assertNull(DshDaemon.extractAuthenticatedWebUrl("http://127.0.0.1:3081/?token=abc"))
    }

    @Test
    fun waitsForCompleteBannerBeforeAcceptingToken() {
        val banner = "dsh web: http://127.0.0.1:3080/?token=abc_DEF-123"
        for (end in 0..banner.length) {
            assertNull(DshDaemon.extractAuthenticatedWebUrl(banner.take(end)))
        }
        assertEquals(
            "http://127.0.0.1:3080/?token=abc_DEF-123",
            DshDaemon.extractAuthenticatedWebUrl("$banner\r\n"),
        )
    }

    @Test
    fun acceptsLatestCompleteBannerAfterRestart() {
        val output = "dsh web: http://127.0.0.1:3080/?token=old\n" +
            "dsh web: http://127.0.0.1:3080/?token=new (LAN: http://192.168.1.2:3080/?token=new)\n" +
            "dsh web: http://127.0.0.1:3080/?token=partial"
        assertEquals("http://127.0.0.1:3080/?token=new", DshDaemon.extractAuthenticatedWebUrl(output))
    }

    @Test
    fun rejectsNonBannerAndNonLoopbackAnnouncements() {
        assertNull(DshDaemon.extractAuthenticatedWebUrl("echo http://127.0.0.1:3080/?token=abc\n"))
        assertNull(DshDaemon.extractAuthenticatedWebUrl("dsh web: http://192.168.1.2:3080/?token=abc\n"))
        assertNull(DshDaemon.extractAuthenticatedWebUrl("dsh web: http://127.0.0.1:3081/?token=abc\n"))
        assertNull(DshDaemon.extractAuthenticatedWebUrl("dsh web: http://127.0.0.1:3080/\n"))
    }
}
