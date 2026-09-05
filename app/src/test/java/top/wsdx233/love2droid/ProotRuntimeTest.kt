package top.wsdx233.love2droid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

class ProotRuntimeTest {
    @Test
    fun resolverConfigForcesDnsOverTcp() {
        assertEquals(
            "nameserver 8.8.8.8\nnameserver 8.8.4.4\noptions use-vc timeout:2 attempts:2\n",
            ProotRuntime.resolverConfig(),
        )
    }

    @Test
    fun terminalArgumentsKeepExecutableBeforeProotOptions() {
        val spec = ProotRuntime.LaunchSpec(
            listOf("/native/libproot.so", "--link2symlink", "-r", "/private/ubuntu", "-w", "/root", "/bin/bash", "-l"),
            File("/private"),
            mapOf("PROOT_TMP_DIR" to "/private/tmp"),
        )
        val terminal = ProotRuntime.terminalSpec(spec)
        assertEquals(spec.command.first(), terminal.executable)
        assertArrayEquals(spec.command.toTypedArray(), terminal.arguments)
        assertEquals("--link2symlink", terminal.arguments[1])
        assertArrayEquals(arrayOf("PROOT_TMP_DIR=/private/tmp"), terminal.environment)
    }

    @Test
    fun launchUsesBindingsWithoutWritingThroughProotPlaceholders() {
        val directory = Files.createTempDirectory("proot-launch").toFile()
        val rootfs = File(directory, "runtime/ubuntu").apply { mkdirs() }
        val files = File(directory, "files").apply { mkdirs() }
        val cache = File(directory, "cache").apply { mkdirs() }
        val external = File(directory, "external").apply { mkdirs() }
        val project = File(external, "projects/d2").apply { mkdirs() }
        val proot = File(directory, "libproot.so").apply { createNewFile() }
        val placeholder = File(rootfs, external.absolutePath.removePrefix("/")).apply { mkdirs() }
        Files.setPosixFilePermissions(placeholder.toPath(), emptySet())
        try {
            prepareProjectsLink(rootfs, external)
            val spec = ProotRuntime.buildLaunch(
                proot, rootfs, File(directory, "tmp"), files, cache, external,
                project.canonicalPath, "xterm-256color", listOf("/bin/bash", "-l"),
            )
            val binds = spec.command.zipWithNext().filter { it.first == "-b" }.map { it.second }
            assertTrue(binds.contains("${external.absolutePath}:${external.absolutePath}!"))
            assertTrue(binds.contains("${project.canonicalPath}:${project.canonicalPath}!"))
            assertFalse(binds.any { it == "/root:/root!" || it == "/storage:/storage!" || it == "/mnt:/mnt!" })
            assertEquals(project.canonicalPath, spec.command[spec.command.indexOf("-w") + 1])
            assertEquals("1", spec.environment["PROOT_DONT_POLLUTE_ROOTFS"])
            assertTrue(Files.getPosixFilePermissions(placeholder.toPath()).isEmpty())
            val projectsLink = File(rootfs, "root/projects").toPath()
            assertTrue(Files.isSymbolicLink(projectsLink))
            assertEquals(File(external, "projects").canonicalFile.toPath(), Files.readSymbolicLink(projectsLink))
        } finally {
            Files.setPosixFilePermissions(placeholder.toPath(), setOf(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
            ))
            assertFalse(File(placeholder, "projects").exists())
            directory.deleteRecursively()
        }
    }

    @Test
    fun guestHomeDoesNotBindHostRootDirectory() {
        val directory = Files.createTempDirectory("proot-home").toFile()
        try {
            val rootfs = File(directory, "ubuntu").apply { mkdirs() }
            val proot = File(directory, "libproot.so").apply { createNewFile() }
            val spec = ProotRuntime.buildLaunch(
                proot, rootfs, File(directory, "tmp"), directory, directory, null,
                "/root", "dumb", listOf("/bin/bash", "-l"),
            )
            assertFalse(spec.command.contains("/root:/root!"))
            assertEquals("/root", spec.command[spec.command.indexOf("-w") + 1])
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun projectsShortcutIsIdempotentAndRepairsBrokenLinks() {
        val directory = Files.createTempDirectory("proot-projects").toFile()
        try {
            val rootfs = File(directory, "ubuntu").apply { mkdirs() }
            val external = File(directory, "external files").apply { mkdirs() }
            val project = File(external, "projects/my game").apply { mkdirs() }
            File(project, "main.lua").writeText("print('hello')")
            prepareProjectsLink(rootfs, external)
            val link = File(rootfs, "root/projects").toPath()
            val target = File(external, "projects").canonicalFile.toPath()
            val linkIdentity = Files.getAttribute(link, "unix:ino", java.nio.file.LinkOption.NOFOLLOW_LINKS)
            prepareProjectsLink(rootfs, external)
            assertEquals(linkIdentity, Files.getAttribute(link, "unix:ino", java.nio.file.LinkOption.NOFOLLOW_LINKS))
            assertEquals("print('hello')", File(rootfs, "root/projects/my game/main.lua").readText())
            Files.delete(link)
            Files.createSymbolicLink(link, File(directory, "missing").toPath())
            prepareProjectsLink(rootfs, external)
            assertEquals(target, Files.readSymbolicLink(link))
            assertTrue(File(rootfs, "root/projects/my game").isDirectory)
        } finally {
            Files.deleteIfExists(File(directory, "ubuntu/root/projects").toPath())
            directory.deleteRecursively()
        }
    }

    @Test
    fun projectsShortcutPreservesExistingUserDirectoryAndFile() {
        val directory = Files.createTempDirectory("proot-projects-conflict").toFile()
        try {
            val rootfs = File(directory, "ubuntu").apply { mkdirs() }
            val external = File(directory, "external").apply { mkdirs() }
            val existing = File(rootfs, "root/projects").apply { mkdirs() }
            val content = File(existing, "keep.txt").apply { writeText("keep") }
            prepareProjectsLink(rootfs, external)
            assertFalse(Files.isSymbolicLink(existing.toPath()))
            assertEquals("keep", content.readText())
            content.delete()
            existing.delete()
            existing.writeText("also keep")
            prepareProjectsLink(rootfs, external)
            assertEquals("also keep", existing.readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun dshProfileAllowsPnpmRootDependenciesAndPreservesOtherConfig() {
        val directory = Files.createTempDirectory("proot-dsh-pnpm").toFile()
        try {
            val profile = File(directory, "root/.dsh/profiles/web").apply { mkdirs() }
            val npmrc = File(profile, ".npmrc").apply { writeText("registry=https://registry.npmjs.org\nignore-workspace-root-check=false\n") }

            ProotRuntime.ensureDshPnpmWorkspaceRootForTest(directory)
            assertEquals(
                "registry=https://registry.npmjs.org\nignore-workspace-root-check=true\n",
                npmrc.readText(),
            )

            ProotRuntime.ensureDshPnpmWorkspaceRootForTest(directory)
            assertEquals(
                "registry=https://registry.npmjs.org\nignore-workspace-root-check=true\n",
                npmrc.readText(),
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun dshProfileConfigIsNotCreatedBeforeProfileInitialization() {
        val directory = Files.createTempDirectory("proot-dsh-no-profile").toFile()
        try {
            ProotRuntime.ensureDshPnpmWorkspaceRootForTest(directory)
            assertFalse(File(directory, "root/.dsh/profiles/web/.npmrc").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun prepareProjectsLink(rootfs: File, external: File) {
        ProotRuntime.prepareProjectsLink(
            rootfs, external,
            readLink = { path ->
                val link = File(path).toPath()
                if (Files.isSymbolicLink(link)) Files.readSymbolicLink(link).toString() else null
            },
            createLink = { target, path -> Files.createSymbolicLink(File(path).toPath(), File(target).toPath()) },
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
