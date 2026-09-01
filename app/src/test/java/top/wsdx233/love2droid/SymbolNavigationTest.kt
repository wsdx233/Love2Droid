package top.wsdx233.love2droid

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SymbolNavigationTest {
    @Test
    fun symbolAtFindsIdentifierAtEitherEdge() {
        val source = "love.graphics.print(value)"
        val start = source.indexOf("print")

        assertEquals("print", symbolAt(source, start))
        assertEquals("print", symbolAt("print", "print".length))
        assertNull(symbolAt(source, source.indexOf('(')))
        assertNull(symbolAt("42", 0))
    }

    @Test
    fun selectedSymbolRequiresAnExactIdentifier() {
        assertEquals("value", selectedSymbol("value", 0, 5))
        assertNull(selectedSymbol("value + other", 0, 7))
        assertNull(selectedSymbol("value", 1, 5))
    }

    @Test
    fun projectFileLinkResolvesEncodedPathAndFragment() {
        val root = Files.createTempDirectory("love2droid-navigation").toFile()
        try {
            val file = File(root, "src/file name.lua").apply {
                parentFile.mkdirs()
                writeText("one\ntwo\nthree\n")
            }

            val target = resolveProjectFileLink(root, "${file.toURI()}#L3C4")

            assertEquals(file.canonicalFile, target?.file)
            assertEquals(2, target?.startLine)
            assertEquals(3, target?.startColumn)
        } finally {
            StorageUtils.deleteRecursively(root)
        }
    }

    @Test
    fun navigationRejectsFileOutsideProject() {
        val root = Files.createTempDirectory("love2droid-navigation-root").toFile()
        val outside = Files.createTempFile("love2droid-navigation-outside", ".lua").toFile()
        try {
            val location = LuaSymbolLocation(outside.toURI().toString(), 0, 0, 0, 1)

            assertNull(resolveProjectNavigationTarget(root, location))
            assertNull(resolveProjectFileLink(root, outside.toURI().toString()))
        } finally {
            StorageUtils.deleteRecursively(root)
            outside.delete()
        }
    }

    @Test
    fun navigationItemsAreSortedDeduplicatedAndIncludePreview() {
        val root = Files.createTempDirectory("love2droid-navigation-items").toFile()
        try {
            val first = File(root, "a.lua").apply { writeText("local first = 1\nprint(first)\n") }
            val second = File(root, "b.lua").apply { writeText("local second = 2\n") }
            val targets = listOf(
                EditorNavigationTarget(second, 0, 6, 0, 12),
                EditorNavigationTarget(first, 1, 6, 1, 11),
                EditorNavigationTarget(first, 1, 6, 1, 11),
            )

            val items = buildSymbolNavigationItems(root, targets)

            assertEquals(listOf("a.lua", "b.lua"), items.map { it.relativePath })
            assertEquals("print(first)", items.first().preview)
            assertTrue(items.all { it.target.file.isFile })
        } finally {
            StorageUtils.deleteRecursively(root)
        }
    }
}
