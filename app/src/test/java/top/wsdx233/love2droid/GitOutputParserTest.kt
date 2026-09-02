package top.wsdx233.love2droid

import org.junit.Assert.assertEquals
import org.junit.Test

class GitOutputParserTest {
    @Test
    fun statusParserHandlesSpacesUnicodeAndRenameRecords() {
        val output = (
            " M src/main file.lua\u0000" +
                "?? 资源.png\u0000" +
                "R  new name.lua\u0000old name.lua\u0000"
            ).toByteArray(Charsets.UTF_8)

        val entries = GitOutputParser.parseStatus(output)

        assertEquals(listOf("new name.lua", "src/main file.lua", "资源.png"), entries.map(GitStatusEntry::relativePath))
        assertEquals("old name.lua", entries.first().originalPath)
        assertEquals('M', entries[1].workTreeStatus)
        assertEquals('?', entries[2].indexStatus)
    }

    @Test
    fun historyParserReadsGraphAndMachineSeparatedFields() {
        val output = "* \u001e0123456789abcdef\u001faaaa bbbb\u001f开发者\u001f1700000000\u001fAdd feature\n" +
            "| * \u001efedcba9876543210\u001f\u001fOther\u001f1700000010\u001fSide branch"

        val entries = GitOutputParser.parseHistory(output)

        assertEquals(2, entries.size)
        assertEquals("*", entries[0].graph)
        assertEquals(listOf("aaaa", "bbbb"), entries[0].parents)
        assertEquals("开发者", entries[0].author)
        assertEquals("| *", entries[1].graph)
        assertEquals(emptyList<String>(), entries[1].parents)
    }
}
