package top.wsdx233.love2droid

import java.io.File
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
}
