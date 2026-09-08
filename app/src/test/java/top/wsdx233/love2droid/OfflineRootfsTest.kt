package top.wsdx233.love2droid

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class OfflineRootfsTest {
    private data class Entry(val name: String, val content: String = "", val link: String? = null, val hard: Boolean = false)

    private fun tar(entries: List<Entry>): ByteArray = ByteArrayOutputStream().also { bytes ->
        TarArchiveOutputStream(bytes).use { output ->
            entries.forEach { value ->
                val data = value.content.toByteArray()
                val entry = when {
                    value.hard -> TarArchiveEntry(value.name, TarConstants.LF_LINK)
                    value.link != null -> TarArchiveEntry(value.name, TarConstants.LF_SYMLINK)
                    else -> TarArchiveEntry(value.name, true)
                }.apply {
                    if (value.link != null) linkName = value.link
                    else size = data.size.toLong()
                    mode = 493
                }
                output.putArchiveEntry(entry)
                if (value.link == null) output.write(data)
                output.closeArchiveEntry()
            }
        }
    }.toByteArray()

    private fun archive(entries: List<Entry>): Pair<ByteArray, OfflineRootfs.Manifest> {
        val bytes = ByteArrayOutputStream().also { output ->
            XZOutputStream(output, LZMA2Options(1)).use { it.write(tar(entries)) }
        }.toByteArray()
        val checksum = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return bytes to OfflineRootfs.Manifest(checksum, bytes.size.toLong(),
            entries.sumOf { it.content.toByteArray().size.toLong() }.coerceAtLeast(1), entries.size)
    }

    private fun restore(root: File, data: ByteArray, manifest: OfflineRootfs.Manifest): Boolean =
        OfflineRootfs.restore(root, manifest, { ByteArrayInputStream(data) },
            { target, path -> Files.createSymbolicLink(File(path).toPath(), File(target).toPath()); Unit },
            { path, mode -> assertTrue(File(path).setExecutable(mode and 64 != 0)) },
            { Files.isSymbolicLink(it.toPath()) })

    private fun inDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("offline-rootfs").toFile()
        try { block(directory) } finally {
            Files.walk(directory.toPath()).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
            }
        }
    }

    @Test
    fun restoresExecutableFilesAndPortableGuestLinks() = inDirectory { directory ->
        val entries = listOf(Entry("usr/bin/tool", "#!/bin/sh\necho ready\n"),
            Entry("bin", link = "usr/bin"), Entry("root/.local/bin/tool", link = "/usr/bin/tool"),
            Entry("usr/share/value", "neighbor"), Entry("bin/neighbor", link = "../share/value"),
            Entry("bin/absolute", link = "/usr/bin/tool"),
            Entry("usr/bin/copy", link = "usr/bin/tool", hard = true))
        val (data, manifest) = archive(entries)
        val root = File(directory, "ubuntu")
        assertTrue(restore(root, data, manifest))
        assertEquals(entries[0].content, File(root, "root/.local/bin/tool").readText())
        assertEquals(entries[0].content, File(root, "bin/copy").readText())
        assertFalse(Files.readSymbolicLink(File(root, "root/.local/bin/tool").toPath()).isAbsolute)
        assertTrue(File(root, "usr/bin/tool").canExecute())
        assertEquals(manifest.sha256, File(root, OfflineRootfs.IMAGE_MARKER).readText())
        assertFalse(File(directory, OfflineRootfs.VERIFIED_MARKER).exists())
        assertEquals("neighbor", File(root, "bin/neighbor").readText())
        assertEquals(entries[0].content, File(root, "bin/absolute").readText())
    }

    @Test
    fun rejectsCorruptionAndTruncationWithoutCommittingRootfs() = inDirectory { directory ->
        val (data, manifest) = archive(listOf(Entry("usr/bin/tool", "payload")))
        val root = File(directory, "ubuntu")
        assertThrows(IllegalStateException::class.java) { restore(root, data, manifest.copy(sha256 = "0".repeat(64))) }
        assertFalse(root.exists())
        assertThrows(Exception::class.java) { restore(root, data.copyOf(data.size - 5), manifest) }
        assertFalse(root.exists())
        assertFalse(File(directory, "ubuntu.offline-staging").exists())
    }

    @Test
    fun neverOverwritesAnExistingEnvironmentIncludingImageUpgrades() = inDirectory { directory ->
        val root = File(directory, "ubuntu").apply { mkdirs() }
        val userFile = File(root, "user-data").apply { writeText("keep") }
        val (data, manifest) = archive(listOf(Entry("usr/bin/tool", "new")))
        assertThrows(IllegalStateException::class.java) { restore(root, data, manifest) }
        File(root, OfflineRootfs.IMAGE_MARKER).writeText("old image")
        assertThrows(IllegalStateException::class.java) { restore(root, data, manifest) }
        assertEquals("keep", userFile.readText())
        assertFalse(File(root, "usr").exists())
    }

    @Test
    fun interruptedDeviceVerificationResumesWithoutReadingOrReplacingTheImage() = inDirectory { directory ->
        val (data, manifest) = archive(listOf(Entry("usr/bin/tool", "payload")))
        val root = File(directory, "ubuntu")
        assertTrue(restore(root, data, manifest))
        File(root, "user-data").writeText("keep")
        assertFalse(OfflineRootfs.restore(root, manifest, { error("Archive must not be read again") },
            { _, _ -> error("No links to create") }, { _, _ -> error("No permissions to change") },
            { Files.isSymbolicLink(it.toPath()) }))
        assertEquals("keep", File(root, "user-data").readText())
    }

    @Test
    fun failedImagesCleanDirectoryLinksWithoutFollowingThem() = inDirectory { directory ->
        val (data, manifest) = archive(listOf(Entry("usr/bin/tool", "payload"), Entry("usr/bin/X11", link = ".")))
        assertThrows(IllegalStateException::class.java) {
            restore(File(directory, "ubuntu"), data, manifest.copy(sha256 = "0".repeat(64)))
        }
        assertFalse(File(directory, "ubuntu.offline-staging").exists())
    }

    @Test
    fun rejectsTraversalAndEscapingLinks() = inDirectory { directory ->
        for (entry in listOf(Entry("../escape", "bad"), Entry("/absolute", "bad"),
            Entry("usr/link", link = "../../escape"))) {
            val (data, manifest) = archive(listOf(entry))
            assertThrows(IllegalArgumentException::class.java) { restore(File(directory, "ubuntu"), data, manifest) }
            assertFalse(File(directory, "escape").exists())
            assertFalse(File(directory, "ubuntu").exists())
        }
    }

    @Test
    fun enforcesDeclaredEntryAndUnpackedSizeLimits() = inDirectory { directory ->
        val (data, manifest) = archive(listOf(Entry("a", "payload"), Entry("b", "payload")))
        assertThrows(IllegalStateException::class.java) {
            restore(File(directory, "ubuntu"), data, manifest.copy(entryCount = 1))
        }
        assertThrows(IllegalStateException::class.java) {
            restore(File(directory, "ubuntu"), data, manifest.copy(unpackedBytes = 1))
        }
        assertFalse(File(directory, "ubuntu").exists())
    }

    @Test
    fun downloadedGzipArchivesUseTheSameLinkAndPermissionRules() = inDirectory { directory ->
        val bytes = ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { it.write(tar(listOf(Entry("usr/bin/tool", "ready"), Entry("bin", link = "/usr/bin")))) }
        }.toByteArray()
        val root = File(directory, "ubuntu").apply { mkdirs() }
        GZIPInputStream(ByteArrayInputStream(bytes)).use {
            assertEquals(2, RootfsArchive.extract(it, root,
                { target, path -> Files.createSymbolicLink(File(path).toPath(), File(target).toPath()); Unit },
                { path, _ -> assertTrue(File(path).setExecutable(true)) }))
        }
        assertEquals("ready", File(root, "bin/tool").readText())
    }

    @Test
    fun manifestRequiresArm64AndTheEntireComponentSet() {
        val metadata = JSONObject().put("formatVersion", 1).put("abi", "arm64-v8a")
            .put("archive", "rootfs-arm64.tar.xz").put("sha256", "a".repeat(64))
            .put("compressedBytes", 1024).put("unpackedBytes", 4096).put("entryCount", 12)
            .put("decoderMemoryLimitKiB", 65536)
            .put("components", listOf("rootfs", "lua_lsp", "omp", "dsh", "git", "love_check"))
        assertEquals(12, OfflineRootfs.parseManifest(metadata.toString()).entryCount)
        metadata.put("abi", "x86_64")
        assertThrows(IllegalArgumentException::class.java) { OfflineRootfs.parseManifest(metadata.toString()) }
        metadata.put("abi", "arm64-v8a").put("components", listOf("rootfs", "lua_lsp", "omp", "dsh", "git"))
        assertThrows(IllegalArgumentException::class.java) { OfflineRootfs.parseManifest(metadata.toString()) }
    }
}
