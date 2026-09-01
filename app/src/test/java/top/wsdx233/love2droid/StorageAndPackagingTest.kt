package top.wsdx233.love2droid

import java.io.File
import java.io.InputStream
import java.io.InterruptedIOException
import java.nio.file.Files
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun ompSessionIdComesFromGeneratedTranscriptName() {
        assertEquals(
            "01a05bf3-0bc9-7147-af78-4c97af3c1e74",
            ProotRuntime.ompSessionIdFromFileName(
                "2026-09-01T07-50-50-057Z_01a05bf3-0bc9-7147-af78-4c97af3c1e74.jsonl",
            ),
        )
        assertEquals(null, ProotRuntime.ompSessionIdFromFileName("not-a-session.txt"))
        assertEquals(null, ProotRuntime.ompSessionIdFromFileName("session.jsonl"))
    }

    @Test
    fun ompSessionIdComesFromMatchingTerminalBreadcrumb() {
        val cwd = "/data/user/0/top.wsdx233.love2droid/files/projects/demo"
        val sessionId = "01a05bf3-0bc9-7147-af78-4c97af3c1e74"
        val breadcrumb = buildString {
            appendLine(cwd)
            appendLine("/root/.omp/agent/sessions/--demo--/2026-09-01T07-50-50-057Z_$sessionId.jsonl")
            appendLine("fresh")
        }

        assertEquals(sessionId, ProotRuntime.ompSessionIdFromTerminalBreadcrumb(breadcrumb, cwd))
        assertEquals(null, ProotRuntime.ompSessionIdFromTerminalBreadcrumb(breadcrumb, "/root"))
        assertEquals("pts-7", ProotRuntime.ompTerminalIdFromTtyPath("/dev/pts/7"))
        assertEquals(null, ProotRuntime.ompTerminalIdFromTtyPath("/proc/self/fd/0"))
    }
}
