package top.wsdx233.love2droid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ProjectSearchEngineTest {
    @Test
    fun fileSearchSupportsPlainTextAndRegex() {
        val root = Files.createTempDirectory("love2droid-project-search").toFile()
        try {
            File(root, "src").mkdirs()
            File(root, "src/Main.lua").writeText("return true\n")
            File(root, "notes.txt").writeText("notes\n")

            val plain = ProjectSearchEngine.searchFiles(root, "main", useRegex = false)
            val regex = ProjectSearchEngine.searchFiles(root, "^src/.+\\.lua$", useRegex = true)

            assertEquals(listOf("src/Main.lua"), plain.map(ProjectSearchResult::relativePath))
            assertEquals(listOf("src/Main.lua"), regex.map(ProjectSearchResult::relativePath))
        } finally {
            StorageUtils.deleteRecursively(root)
        }
    }

    @Test
    fun textSearchReturnsRangesAndSkipsBinaryFiles() {
        val root = Files.createTempDirectory("love2droid-text-search").toFile()
        try {
            File(root, "main.lua").writeText("local player = 1\nprint(player)\n")
            File(root, "data.bin").writeBytes(byteArrayOf(1, 0, 2, 3))

            val results = ProjectSearchEngine.searchText(root, "p[a-z]+r", useRegex = true)

            assertEquals(2, results.size)
            assertTrue(results.all { it.relativePath == "main.lua" })
            assertEquals(6, results.first().target.startColumn)
            assertEquals(12, results.first().target.endColumn)
        } finally {
            StorageUtils.deleteRecursively(root)
        }
    }

    @Test
    fun localSymbolsFindDeclarationsOnly() {
        val root = Files.createTempDirectory("love2droid-symbol-search").toFile()
        try {
            File(root, "main.lua").writeText(
                "local player = {}\nfunction player.draw()\nend\nprint(player)\n",
            )

            val results = ProjectSearchEngine.searchLocalSymbols(root, "player", useRegex = false)

            assertEquals(listOf("player", "player.draw"), results.map(ProjectSearchResult::title))
            assertTrue(results.all { it.symbolSource == ProjectSymbolSource.LOCAL })
        } finally {
            StorageUtils.deleteRecursively(root)
        }
    }

    @Test
    fun semanticSymbolsRejectLocationsOutsideProject() {
        val root = Files.createTempDirectory("love2droid-semantic-search").toFile()
        val outside = Files.createTempFile("outside-symbol", ".lua").toFile()
        try {
            val inside = File(root, "main.lua").apply { writeText("function draw() end\n") }
            val symbols = listOf(
                LuaWorkspaceSymbol("draw", "Function", LuaSymbolLocation(inside.toURI().toString(), 0, 9, 0, 13)),
                LuaWorkspaceSymbol("outside", "Function", LuaSymbolLocation(outside.toURI().toString(), 0, 0, 0, 7)),
            )

            val results = ProjectSearchEngine.semanticSymbols(root, ".*", useRegex = true, symbols)

            assertEquals(listOf("draw"), results.map(ProjectSearchResult::title))
            assertEquals(ProjectSymbolSource.SEMANTIC, results.single().symbolSource)
        } finally {
            StorageUtils.deleteRecursively(root)
            outside.delete()
        }
    }
}
