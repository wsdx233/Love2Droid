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

    @Test
    fun diffParserKeepsHunksAndLineNumbersForRedGreenRows() {
        val value = "diff --git a/main.lua b/main.lua\n" +
            "--- a/main.lua\n" +
            "+++ b/main.lua\n" +
            "@@ -2,2 +2,3 @@ function love.load()\n" +
            " keep\n" +
            "-old\n" +
            "+new\n" +
            "+extra\n" +
            "\\ No newline at end of file\n"

        val lines = GitDiffParser.parse(value)

        assertEquals(
            listOf(
                GitDiffLineKind.METADATA,
                GitDiffLineKind.METADATA,
                GitDiffLineKind.METADATA,
                GitDiffLineKind.HUNK,
                GitDiffLineKind.CONTEXT,
                GitDiffLineKind.REMOVAL,
                GitDiffLineKind.ADDITION,
                GitDiffLineKind.ADDITION,
                GitDiffLineKind.METADATA,
            ),
            lines.map(GitDiffLine::kind),
        )
        assertEquals(2, lines[4].oldLineNumber)
        assertEquals(2, lines[4].newLineNumber)
        assertEquals(3, lines[5].oldLineNumber)
        assertEquals(3, lines[6].newLineNumber)
        assertEquals(4, lines[7].newLineNumber)
    }

    @Test
    fun statusEntriesExposeStagedAndWorkingTreeBuckets() {
        val staged = GitStatusEntry('M', ' ', "staged.lua")
        val changed = GitStatusEntry(' ', 'M', "changed.lua")
        val both = GitStatusEntry('M', 'M', "both.lua")
        val untracked = GitStatusEntry('?', '?', "new.lua")

        assertEquals(true, staged.isStaged)
        assertEquals(false, staged.hasWorkTreeChanges)
        assertEquals(false, changed.isStaged)
        assertEquals(true, changed.hasWorkTreeChanges)
        assertEquals(true, both.isStaged)
        assertEquals(true, both.hasWorkTreeChanges)
        assertEquals(false, untracked.isStaged)
        assertEquals(true, untracked.isUntracked)
    }
}
