package top.wsdx233.love2droid

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorFileRobustnessTest {
    @Test
    fun loaderAcceptsLimitAndRejectsOneByteOver() {
        val root = Files.createTempDirectory("editor-file-limit").toFile()
        try {
            val file = File(root, "data.txt")
            file.writeBytes(ByteArray(8) { 'a'.code.toByte() })
            assertTrue(EditorFileLoader.load(file, maxBytes = 8) is EditorFileLoadResult.Text)
            file.writeBytes(ByteArray(9) { 'a'.code.toByte() })
            assertTrue(EditorFileLoader.load(file, maxBytes = 8) is EditorFileLoadResult.TooLarge)
        } finally {
            StorageUtils.deleteRecursively(root)
        }
    }

    @Test
    fun loaderRejectsBinaryAndMalformedUtf8WithoutDecoding() {
        val root = Files.createTempDirectory("editor-file-encoding").toFile()
        try {
            val binary = File(root, "binary.dat").apply { writeBytes(byteArrayOf(0x41, 0, 0x42)) }
            val invalid = File(root, "invalid.txt").apply { writeBytes(byteArrayOf(0xC3.toByte(), 0x28)) }
            assertEquals(EditorFileLoadResult.Binary, EditorFileLoader.load(binary))
            assertEquals(EditorFileLoadResult.InvalidUtf8, EditorFileLoader.load(invalid))
        } finally {
            StorageUtils.deleteRecursively(root)
        }
    }

    @Test
    fun loaderDetectsAndNormalizesCrLf() {
        val root = Files.createTempDirectory("editor-file-newline").toFile()
        try {
            val file = File(root, "main.lua").apply { writeText("one\r\ntwo\r\n") }
            val loaded = EditorFileLoader.load(file) as EditorFileLoadResult.Text
            assertEquals(EditorLineEnding.CRLF, loaded.lineEnding)
            assertEquals("one\r\ntwo\r\n", EditorFileLoader.normalizeLineEndings("one\ntwo\n", loaded.lineEnding))
        } finally {
            StorageUtils.deleteRecursively(root)
        }
    }

    @Test
    fun archiveRoundTripKeepsGameFilesAndRejectsZipSlip() {
        val root = Files.createTempDirectory("love-archive-source").toFile()
        val imported = File(root.parentFile, "love-archive-imported-${System.nanoTime()}")
        try {
            File(root, "main.lua").writeText("print('ok')")
            File(root, "assets").mkdirs()
            File(root, "assets/sprite.txt").writeText("asset")
            val project = Project("demo", "Demo", "", root, 0L)
            val archive = ByteArrayOutputStream()
            LoveArchiveTransfer.export(project, archive)
            LoveArchiveTransfer.import(ByteArrayInputStream(archive.toByteArray()), imported)
            assertEquals("print('ok')", File(imported, "main.lua").readText())
            assertEquals("asset", File(imported, "assets/sprite.txt").readText())

            val malicious = ByteArrayOutputStream()
            ZipOutputStream(malicious).use { zip ->
                zip.putNextEntry(ZipEntry("../escape.txt"))
                zip.write(1)
                zip.closeEntry()
            }
            val rejected = File(root.parentFile, "love-archive-rejected-${System.nanoTime()}")
            try {
                try {
                    LoveArchiveTransfer.import(ByteArrayInputStream(malicious.toByteArray()), rejected)
                    throw AssertionError("zip slip was accepted")
                } catch (_: IllegalArgumentException) {
                    // expected
                }
                assertFalse(rejected.exists())
                assertFalse(File(root.parentFile, "escape.txt").exists())
            } finally {
                StorageUtils.deleteRecursively(rejected)
            }
        } finally {
            StorageUtils.deleteRecursively(root)
            StorageUtils.deleteRecursively(imported)
        }
    }

    @Test
    fun resolverMapsTomlAndGlslExtensions() {
        assertEquals("source.toml", LanguageResolver.scopeFor(File("settings.toml")))
        assertEquals("source.glsl", LanguageResolver.scopeFor(File("shader.frag")))
        assertEquals("GLSL", LanguageResolver.displayName(File("shader.vert")))
    }
}
