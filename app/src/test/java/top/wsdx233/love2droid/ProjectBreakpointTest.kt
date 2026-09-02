package top.wsdx233.love2droid

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectBreakpointTest {
    @Test
    fun normalizationKeepsOnlyProjectLuaFiles() {
        val root = Files.createTempDirectory("love2droid-breakpoints").toFile()
        try {
            val lua = root.resolve("scripts/player.lua")
            lua.parentFile.mkdirs()
            lua.writeText("return true")
            root.resolve("notes.txt").writeText("notes")

            assertEquals(
                listOf(ProjectBreakpoint("scripts/player.lua", 3)),
                normalizeProjectBreakpoints(
                    root,
                    listOf(
                        ProjectBreakpoint("scripts/player.lua", 3),
                        ProjectBreakpoint("scripts/player.lua", 3),
                        ProjectBreakpoint("../outside.lua", 4),
                        ProjectBreakpoint("notes.txt", 2),
                        ProjectBreakpoint("scripts/player.lua", 0),
                    ),
                ),
            )
        } finally {
            StorageUtils.deleteRecursively(root)
        }
    }

    @Test
    fun multilineEditsMoveAndRemoveAffectedBreakpoints() {
        val original = listOf(
            ProjectBreakpoint("main.lua", 2),
            ProjectBreakpoint("main.lua", 5),
            ProjectBreakpoint("other.lua", 5),
        )
        val inserted = original.adjustedForEdit(
            file = "main.lua",
            kind = BreakpointEditKind.INSERT,
            startLine = 1,
            endLine = 3,
        )
        assertEquals(
            listOf(
                ProjectBreakpoint("main.lua", 2),
                ProjectBreakpoint("main.lua", 7),
                ProjectBreakpoint("other.lua", 5),
            ),
            inserted,
        )

        assertEquals(
            listOf(
                ProjectBreakpoint("main.lua", 2),
                ProjectBreakpoint("other.lua", 5),
            ),
            inserted.adjustedForEdit(
                file = "main.lua",
                kind = BreakpointEditKind.DELETE,
                startLine = 1,
                endLine = 6,
            ),
        )
    }
}
