package top.wsdx233.love2droid

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.HashSet
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal object LoveArchiveTransfer {
    const val MAX_ENTRY_COUNT = 4096
    const val MAX_ENTRY_BYTES = 64L * 1024 * 1024
    const val MAX_ARCHIVE_BYTES = 256L * 1024 * 1024

    fun export(project: Project, output: OutputStream) {
        val root = project.root.canonicalFile
        require(root.isDirectory) { "项目目录不存在" }
        val mainLua = File(root, "main.lua")
        require(StorageUtils.isWithin(root, mainLua) && mainLua.isFile) { "项目缺少 main.lua" }
        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            addDirectory(root, root, zip)
        }
    }

    fun import(input: InputStream, targetRoot: File) {
        val root = targetRoot.canonicalFile
        require(!root.exists()) { "导入目标已存在" }
        require(root.mkdirs()) { "无法创建导入项目目录" }
        try {
            ZipInputStream(input.buffered()).use { zip ->
                val paths = HashSet<String>()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var entryCount = 0
                var totalBytes = 0L
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount++
                    require(entryCount <= MAX_ENTRY_COUNT) { "压缩包包含过多文件" }
                    val relativePath = safeEntryPath(entry.name)
                    if (relativePath == StorageUtils.METADATA_FILE ||
                        relativePath == StorageUtils.WORKSPACE_FILE ||
                        relativePath.endsWith("/${StorageUtils.METADATA_FILE}") ||
                        relativePath.endsWith("/${StorageUtils.WORKSPACE_FILE}")
                    ) {
                        zip.closeEntry()
                        continue
                    }
                    require(paths.add(relativePath)) { "压缩包包含重复路径：$relativePath" }
                    val target = StorageUtils.resolveChild(root, relativePath)
                    if (entry.isDirectory || entry.name.endsWith('/')) {
                        require(!target.exists() || target.isDirectory) { "压缩包路径冲突：$relativePath" }
                        require(target.exists() || target.mkdirs()) { "无法创建导入目录：$relativePath" }
                    } else {
                        require(!target.exists()) { "压缩包路径冲突：$relativePath" }
                        val parent = requireNotNull(target.parentFile)
                        require(!parent.exists() || parent.isDirectory) { "压缩包路径冲突：$relativePath" }
                        require(parent.exists() || parent.mkdirs()) { "无法创建导入目录：${parent.path}" }
                        var entryBytes = 0L
                        target.outputStream().use { output ->
                            while (true) {
                                val read = zip.read(buffer)
                                if (read < 0) break
                                entryBytes += read
                                totalBytes += read
                                require(entryBytes <= MAX_ENTRY_BYTES) { "压缩包单个文件过大：$relativePath" }
                                require(totalBytes <= MAX_ARCHIVE_BYTES) { "压缩包解压内容过大" }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            normalizeSingleRoot(root)
            require(File(root, "main.lua").isFile) { "压缩包缺少根目录 main.lua" }
        } catch (error: Throwable) {
            StorageUtils.deleteRecursively(root)
            throw error
        }
    }

    private fun addDirectory(root: File, directory: File, zip: ZipOutputStream) {
        directory.listFiles()
            ?.asSequence()
            ?.filter { it.name != StorageUtils.METADATA_FILE && !it.name.startsWith(".") }
            ?.sortedBy { it.name }
            ?.forEach { child ->
                if (child.isDirectory) {
                    addDirectory(root, child, zip)
                } else if (child.isFile) {
                    val name = StorageUtils.relativePath(root, child)
                    zip.putNextEntry(ZipEntry(name))
                    BufferedInputStream(FileInputStream(child)).use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                }
            }
    }

    private fun safeEntryPath(name: String): String {
        require(name.isNotEmpty() && !name.startsWith('/') && !name.contains('\\') && !name.contains('\u0000')) {
            "压缩包包含无效路径"
        }
        val path = name.removeSuffix("/")
        require(path.isNotBlank()) { "压缩包包含无效路径" }
        val parts = path.split('/')
        require(parts.none { it.isBlank() || it == "." || it == ".." || it.contains(':') }) {
            "压缩包包含无效路径：$name"
        }
        return parts.joinToString("/")
    }

    private fun normalizeSingleRoot(root: File) {
        if (File(root, "main.lua").isFile) return
        val children = root.listFiles() ?: return
        if (children.size != 1 || !children[0].isDirectory || !File(children[0], "main.lua").isFile) return
        val nested = children[0]
        nested.listFiles()?.forEach { child ->
            val target = File(root, child.name)
            require(!target.exists()) { "压缩包根目录存在冲突：${child.name}" }
            require(child.renameTo(target)) { "无法整理压缩包目录" }
        }
        require(nested.delete()) { "无法整理压缩包目录" }
    }
}
