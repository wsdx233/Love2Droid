package top.wsdx233.love2droid

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.InputStream

/** Shared by downloaded Ubuntu Base and the bundled, preinstalled image. */
internal object RootfsArchive {
    fun extract(
        input: InputStream,
        rootfs: File,
        createSymlink: (target: String, path: String) -> Unit,
        setMode: (path: String, mode: Int) -> Unit,
        maxEntries: Int = Int.MAX_VALUE,
        maxBytes: Long = Long.MAX_VALUE,
    ): Int {
        val root = rootfs.canonicalFile
        check(root.isDirectory) { "Rootfs staging directory is missing" }
        val buffer = ByteArray(64 * 1024)
        var entries = 0
        var bytes = 0L
        TarArchiveInputStream(input).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                check(++entries <= maxEntries) { "Rootfs archive has too many entries" }
                val rawName = entry.name.replace('\\', '/')
                require(!rawName.startsWith('/') && rawName.split('/').none { it == ".." }) {
                    "Invalid rootfs archive path: ${entry.name}"
                }
                val name = rawName.removePrefix("./").trimEnd('/')
                if (name.isBlank() || name == ".") continue
                val output = File(root, name)
                require(StorageUtils.isWithin(root, output)) { "Archive path escapes rootfs: $name" }
                when {
                    entry.isDirectory -> check(output.isDirectory || output.mkdirs()) {
                        "Cannot create rootfs directory: $name"
                    }
                    entry.isSymbolicLink || entry.isLink -> {
                        val link = entry.linkName
                        require(link.isNotEmpty()) { "Empty rootfs link: $name" }
                        val target = when {
                            link.startsWith('/') -> File(root, link.removePrefix("/"))
                            entry.isLink -> File(root, link.removePrefix("./"))
                            else -> File(output.parentFile!!.canonicalFile, link)
                        }
                        require(StorageUtils.isWithin(root, target)) { "Link escapes rootfs: $name" }
                        check(output.parentFile!!.isDirectory || output.parentFile!!.mkdirs())
                        // Absolute Linux links must resolve inside the guest even when
                        // inspected by Android (readiness checks, atomic asset updates).
                        val relative = target.absoluteFile.normalize().relativeTo(output.parentFile!!.canonicalFile).path.ifEmpty { "." }
                        output.delete()
                        createSymlink(relative, output.absolutePath)
                    }
                    entry.isFile -> {
                        check(entry.size >= 0 && entry.size <= maxBytes - bytes) { "Rootfs archive exceeds its declared size" }
                        check(output.parentFile!!.isDirectory || output.parentFile!!.mkdirs())
                        output.outputStream().buffered().use { destination ->
                            while (true) {
                                val count = tar.read(buffer)
                                if (count < 0) break
                                bytes += count
                                check(bytes <= maxBytes) { "Rootfs archive exceeds its declared size" }
                                destination.write(buffer, 0, count)
                            }
                        }
                    }
                    // /dev and /proc are bound at launch; device nodes are not extracted.
                    else -> continue
                }
                if (!entry.isSymbolicLink && !entry.isLink) {
                    var mode = (entry.mode and 511) or 384
                    if (entry.isDirectory) mode = mode or 64
                    setMode(output.absolutePath, mode)
                }
            }
        }
        return entries
    }
}
