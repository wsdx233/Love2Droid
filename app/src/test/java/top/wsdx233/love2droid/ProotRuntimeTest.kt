package top.wsdx233.love2droid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProotRuntimeTest {
    @Test
    fun resolverConfigForcesDnsOverTcp() {
        assertEquals(
            "nameserver 8.8.8.8\nnameserver 8.8.4.4\noptions use-vc timeout:2 attempts:2\n",
            ProotRuntime.resolverConfig(),
        )
    }

    @Test
    fun installRegistryContainsExpectedComponentsAndDependencies() {
        val components = InstallRegistry.availableComponents
        assertTrue(components.size >= 4)

        val rootfs = InstallRegistry.find(InstallRegistry.ID_ROOTFS)
        assertNotNull(rootfs)
        assertTrue(rootfs!!.isRequired)
        assertTrue(rootfs.dependencies.isEmpty())

        val lsp = InstallRegistry.find(InstallRegistry.ID_LSP)
        assertNotNull(lsp)
        assertTrue(lsp!!.dependencies.contains(InstallRegistry.ID_ROOTFS))

        val omp = InstallRegistry.find(InstallRegistry.ID_OMP)
        assertNotNull(omp)
        assertTrue(omp!!.dependencies.contains(InstallRegistry.ID_ROOTFS))

        val git = InstallRegistry.find(InstallRegistry.ID_GIT)
        assertNotNull(git)
        assertTrue(git!!.dependencies.contains(InstallRegistry.ID_ROOTFS))
    }
}
