package top.wsdx233.love2droid

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Test

class EditorFileAccessTest {
    private class Fixture : AutoCloseable {
        val root = Files.createTempDirectory("editor-external").toFile()
        val files = File(root, "files").apply { mkdirs() }
        val rootfs = File(files, "proot/ubuntu").apply { mkdirs() }
        val tmp = File(files, "proot/tmp").apply { mkdirs() }
        val cache = File(root, "cache").apply { mkdirs() }
        val external = File(root, "external").apply { mkdirs() }
        val project = File(external, "projects/current").apply { mkdirs() }
        val other = File(external, "projects/other").apply { mkdirs() }
        val access = EditorFileAccess(rootfs, tmp, files, cache, external)
        override fun close() { root.deleteRecursively() }
    }

    @Test
    fun guestFilesFollowActualBindingsWithoutSwitchingProjectRoot() = Fixture().use { f ->
        val outside = File(f.other, "audio 中文.lua").apply { writeText("return {}") }
        val config = File(f.rootfs, "root/config.json").apply { parentFile.mkdirs(); writeText("{}") }
        assertEquals(outside.canonicalFile, f.access.guestFile(outside.path))
        assertEquals(config.canonicalFile, f.access.guestFile("/root/config.json"))
        assertEquals(File(f.tmp, "a.txt").canonicalFile, f.access.guestFile("/tmp/a.txt"))
        assertEquals(File(f.tmp, "a.txt").canonicalFile, f.access.guestFile("/var/tmp/a.txt"))
        assertEquals(File(f.rootfs, "tmp/a.txt").canonicalFile, f.access.guestFile("/dev/shm/a.txt"))
        assertEquals("current", f.project.name)
        assertFalse(StorageUtils.isWithin(f.project, outside))
    }

    @Test
    fun guestMappingRejectsVirtualDevicesAndSymlinkEscapes() = Fixture().use { f ->
        listOf("/proc/self/mem", "/sys/kernel", "/dev/zero", "/root/../secret", "relative.lua", "/root/a\u0000b").forEach {
            assertThrows(IllegalArgumentException::class.java) { f.access.guestFile(it) }
        }
        val outside = File(f.root, "outside").apply { mkdirs() }
        val link = File(f.rootfs, "escape")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) { f.access.guestFile("/escape/secret.txt") }
        assertThrows(IllegalArgumentException::class.java) { f.access.hostFile(File(outside, "secret.txt").path) }
        Files.delete(link.toPath())
    }

    @Test
    fun externalTabAliasesReuseDirtyContentAndSaveToOriginalFile() = Fixture().use { f ->
        val file = File(f.other, "audio.lua").apply { writeText("disk") }
        val alias = File(f.other, "alias.lua")
        Files.createSymbolicLink(alias.toPath(), file.toPath())
        val session = EditorSession()
        val tab = EditorTab(f.access.hostFile(file.path), "unsaved", "source.lua").apply { dirty = true }
        session.select(session.add(tab))
        assertSame(tab, session.find(f.access.hostFile(alias.path)))
        assertEquals("unsaved", session.activeEditorTab!!.text)
        assertTrue(tab.dirty)
        assertEquals(1, session.tabs.size)
        StorageUtils.writeTextAtomic(f.access.hostFile(tab.file!!.path), tab.text)
        assertEquals("unsaved", file.readText())
        assertFalse(File(f.project, "audio.lua").exists())
        Files.delete(alias.toPath())
    }

    @Test
    fun workspaceRoundTripPreservesExternalPathsAndUnsavedText(): Unit = Fixture().use { f ->
        val file = File(f.other, "audio.lua").apply { writeText("disk") }
        val snapshot = WorkspaceSnapshot(activeTab = 1, tabs = listOf(
            WorkspaceTabSnapshot(WorkspaceTabType.EDITOR, path = "main.lua"),
            WorkspaceTabSnapshot(WorkspaceTabType.EDITOR, externalPath = file.canonicalPath,
                text = "unsaved", dirty = true, selectionStart = 3, selectionEnd = 5),
        ))
        WorkspaceStore.write(f.project, snapshot)
        val restored = WorkspaceStore.read(f.project)!!
        assertEquals(snapshot, restored)
        assertEquals(file.canonicalFile, f.access.workspaceFile(f.project,
            restored.tabs[1].path, restored.tabs[1].externalPath))
        assertEquals(File(f.project, "main.lua"), f.access.workspaceFile(f.project, restored.tabs[0].path, null))
        assertThrows(IllegalArgumentException::class.java) {
            f.access.workspaceFile(f.project, "main.lua", file.path)
        }
        assertThrows(IllegalArgumentException::class.java) {
            f.access.workspaceFile(f.project, null, File(f.root, "secret").path)
        }
        assertThrows(IllegalArgumentException::class.java) {
            f.access.workspaceFile(f.project, "../other/audio.lua", null)
        }
    }

    @Test
    fun externalReferencesAndEditorStateNeverEnterGameSnapshot() = Fixture().use { f ->
        File(f.project, "main.lua").writeText("print('project')")
        val outside = File(f.other, "secret.lua").apply { writeText("private") }
        WorkspaceStore.write(f.project, WorkspaceSnapshot(tabs = listOf(
            WorkspaceTabSnapshot(WorkspaceTabType.EDITOR, externalPath = outside.path, text = "dirty private", dirty = true),
        )))
        val packaged = LovePackageBuilder.build(Project("current", "Current", "", f.project, 0L), f.cache)
        ZipFile(packaged).use { zip ->
            assertNotNull(zip.getEntry("main.lua"))
            assertNull(zip.getEntry("secret.lua"))
            assertNull(zip.getEntry(StorageUtils.WORKSPACE_FILE))
        }
        assertEquals("private", outside.readText())
    }
}
