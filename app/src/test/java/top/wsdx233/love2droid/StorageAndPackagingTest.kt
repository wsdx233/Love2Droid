package top.wsdx233.love2droid

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.nio.file.Files
import java.util.zip.ZipFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageAndPackagingTest {
    @Test
    fun relativePathRejectsEscape() {
        val root = Files.createTempDirectory("love2droid-root").toFile()
        try {
            val outside = File(root.parentFile, "outside.txt")
            try {
                StorageUtils.resolveChild(root, "../${outside.name}")
                throw AssertionError("escape path was accepted")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        } finally {
            StorageUtils.deleteRecursively(root)
        }
    }

    @Test
    fun packageContainsGameFilesAndOmitsEditorMetadata() {
        val root = Files.createTempDirectory("love2droid-project").toFile()
        val cache = Files.createTempDirectory("love2droid-cache").toFile()
        try {
            File(root, "main.lua").writeText("print('ok')")
            File(root, "assets").mkdirs()
            File(root, "assets/sprite.txt").writeText("asset")
            File(root, StorageUtils.METADATA_FILE).writeText("editor-only")
            val project = Project("demo", "Demo", "", root, 0L)
            val packageFile = LovePackageBuilder.build(project, cache)
            ZipFile(packageFile).use { zip ->
                assertTrue(zip.getEntry("main.lua") != null)
                assertTrue(zip.getEntry("assets/sprite.txt") != null)
                assertFalse(zip.getEntry(StorageUtils.METADATA_FILE) != null)
            }
        } finally {
            StorageUtils.deleteRecursively(root)
            StorageUtils.deleteRecursively(cache)
        }
    }

    @Test
    fun projectTemplateFontAndLicensesSurvivePackaging() {
        val root = Files.createTempDirectory("love2droid-template").toFile()
        val cache = Files.createTempDirectory("love2droid-template-cache").toFile()
        val template = File("src/normal/assets/project-template")
        try {
            ProjectTemplate.write(root) { path -> File(template, path).inputStream() }
            val project = Project("demo", "Demo", "", root, 0L)
            val packageFile = LovePackageBuilder.build(project, cache)
            val paths = listOf(
                "main.lua",
                "AGENTS.md",
                "assets/fonts/fusion-pixel-12px-monospaced-zh_hans.otf",
                "assets/fonts/OFL.txt",
                "assets/fonts/LICENSES/ark-pixel/OFL.txt",
                "assets/fonts/LICENSES/cubic-11/OFL.txt",
                "assets/fonts/LICENSES/galmuri/LICENSE.txt",
            )
            ZipFile(packageFile).use { zip ->
                for (path in paths) {
                    val expected = File(template, path).readBytes()
                    assertArrayEquals(path, expected, File(root, path).readBytes())
                    val entry = requireNotNull(zip.getEntry(path)) { "Missing packaged template asset: $path" }
                    assertArrayEquals(path, expected, zip.getInputStream(entry).use { it.readBytes() })
                }
            }
        } finally {
            StorageUtils.deleteRecursively(root)
            StorageUtils.deleteRecursively(cache)
        }
    }

    @Test
    fun projectTemplateRejectsMissingFontRatherThanSilentlyFallingBack() {
        val root = Files.createTempDirectory("love2droid-template-missing-font").toFile()
        val template = File("src/normal/assets/project-template")
        try {
            assertThrows(IOException::class.java) {
                ProjectTemplate.write(root) { path ->
                    if (path.endsWith(".otf")) throw IOException("Missing bundled font: $path")
                    File(template, path).inputStream()
                }
            }
        } finally {
            StorageUtils.deleteRecursively(root)
        }
    }

    @Test
    fun projectTemplateRejectsMissingAgentInstructions() {
        val root = Files.createTempDirectory("love2droid-template-missing-instructions").toFile()
        val template = File("src/normal/assets/project-template")
        try {
            assertThrows(IOException::class.java) {
                ProjectTemplate.write(root) { path ->
                    if (path == "AGENTS.md") throw IOException("Missing bundled instructions")
                    File(template, path).inputStream()
                }
            }
        } finally {
            StorageUtils.deleteRecursively(root)
        }
    }

    @Test
    fun languageResolverMapsCommonGrammarsAndFallsBackForUnknown() {
        assertEquals("source.lua", LanguageResolver.scopeFor(File("main.lua")))
        assertEquals("source.json", LanguageResolver.scopeFor(File("data.json")))
        assertEquals(null, LanguageResolver.scopeFor(File("notes.txt")))
        assertEquals("JSON", LanguageResolver.displayName(File("data.json")))
    }

    @Test
    fun love2dConfigLoadsLuaJitAndBundledApiLibrary() {
        val config = LuaLanguageServerProjectConfig.content()
        assertTrue(config.contains("\"runtime.version\": \"LuaJIT\""))
        assertTrue(config.contains("\"love.filesystem.load\": \"loadfile\""))
        assertTrue(config.contains("\"workspace.library\""))
        assertTrue(config.contains(ProotRuntime.LUA_LSP_LOVE_LIBRARY_GUEST_PATH))
        assertTrue(config.contains("\"workspace.checkThirdParty\": false"))
    }

    @Test
    fun addingLoadedTabKeepsCurrentTabUntilExplicitSelection() {
        val session = EditorSession()
        val current = EditorTab(File("current.lua"), "current", "source.lua")
        val currentIndex = session.add(current)
        session.select(currentIndex)

        val loaded = EditorTab(File("loaded.lua"), "print('loaded')", "source.lua")
        val loadedIndex = session.add(loaded)

        assertTrue(session.activeTab === current)
        session.select(loadedIndex)
        assertTrue(session.activeTab === loaded)
        assertEquals("print('loaded')", session.activeEditorTab?.text)
    }

    @Test
    fun lspStderrReaderIgnoresInterruptedClosedPipe() {
        var logged = false
        val closedPipe = object : InputStream() {
            override fun read(): Int = throw InterruptedIOException("closed")
        }

        consumeLuaLanguageServerStderr(closedPipe) { logged = true }

        assertFalse(logged)
    }
    @Test
    fun ompStartupResumesFirstAvailableSession() {
        assertEquals("omp --allow-home --continue", ProotRuntime.ompStartupCommand())
    }

    @Test
    fun guestGroupRepairOnlyAddsUnmappedIds() {
        val groupContent = "root:x:0:\ninet:x:3003:\n"
        assertEquals(
            setOf(1079, 9997),
            ProotRuntime.missingHostGroupIds(groupContent, setOf(0, 1079, 3003, 9997)),
        )
    }
    @Test
    fun hostSupplementaryGroupsComeFromProcStatus() {
        val procStatus = "Name:\tlove2droid\nGroups:\t1079 3002 3003 9997 20644 50644 \n"
        assertEquals(
            setOf(1079, 3002, 3003, 9997, 20644, 50644),
            ProotRuntime.hostSupplementaryGroupIds(procStatus),
        )
    }




}
