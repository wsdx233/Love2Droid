package top.wsdx233.love2droid.runtime

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DebugSourceArchiveTest {
    @Test
    fun readsExactAndAbsoluteSuffixSourcePaths() {
        val archive = archiveOf("scripts/player.lua" to "local speed = 12\n")

        assertEquals(
            "local speed = 12\n",
            DebugSourceArchive.readSource(ByteArrayInputStream(archive), "scripts/player.lua"),
        )
        assertEquals(
            "local speed = 12\n",
            DebugSourceArchive.readSource(ByteArrayInputStream(archive), "@/mounted/game/scripts/player.lua"),
        )
    }

    @Test
    fun rejectsTraversalEntryAndMissingSource() {
        val archive = archiveOf("../main.lua" to "return true", "main.lua" to "return false")

        assertNull(DebugSourceArchive.readSource(ByteArrayInputStream(archive), "missing.lua"))
        assertEquals(
            "return false",
            DebugSourceArchive.readSource(ByteArrayInputStream(archive), "main.lua"),
        )
    }

    private fun archiveOf(vararg entries: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
