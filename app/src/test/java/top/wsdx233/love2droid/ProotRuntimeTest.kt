package top.wsdx233.love2droid

import org.junit.Assert.assertEquals
import org.junit.Test

class ProotRuntimeTest {
    @Test
    fun resolverConfigForcesDnsOverTcp() {
        assertEquals(
            "nameserver 8.8.8.8\nnameserver 8.8.4.4\noptions use-vc timeout:2 attempts:2\n",
            ProotRuntime.resolverConfig(),
        )
    }
}
