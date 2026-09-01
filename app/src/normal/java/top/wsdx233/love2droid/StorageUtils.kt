package top.wsdx233.love2droid

import androidx.core.util.AtomicFile
import java.io.File
import java.io.IOException
import java.util.Locale

internal object StorageUtils {
    const val METADATA_FILE = ".love2droid.json"
    const val WORKSPACE_FILE = ".lovedroid"
    fun isWithin(root: File, candidate: File): Boolean {
        val rootPath = root.canonicalFile.path
        val candidatePath = candidate.canonicalFile.path
        return candidatePath == rootPath || candidatePath.startsWith(rootPath + File.separator)
    }

    fun resolveChild(root: File, relativePath: String): File {
        require(relativePath.isNotBlank()) { "Path must not be blank" }
        require(!File(relativePath).isAbsolute) { "Absolute paths are not allowed" }
        val candidate = File(root, relativePath)
        require(isWithin(root, candidate)) { "Path escapes project root" }
        return candidate
    }

    fun relativePath(root: File, file: File): String {
        require(isWithin(root, file)) { "File is outside project root" }
        val rootPath = root.canonicalFile.path
        val filePath = file.canonicalFile.path
        if (filePath == rootPath) return ""
        return filePath.substring(rootPath.length + 1).replace(File.separatorChar, '/')
    }

    fun writeTextAtomic(target: File, content: String) {
        val parent = requireNotNull(target.parentFile) { "Target has no parent directory" }
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Unable to create ${parent.path}")
        }
        val atomicFile = AtomicFile(target)
        var output: java.io.FileOutputStream? = null
        try {
            output = atomicFile.startWrite()
            output.write(content.toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(output)
            output = null
        } finally {
            output?.let(atomicFile::failWrite)
        }
    }

    fun deleteRecursively(file: File) {
        if (!file.exists()) return
        file.walkBottomUp().forEach { child ->
            if (!child.delete() && child.exists()) {
                throw IOException("Unable to delete ${child.path}")
            }
        }
    }

    fun copyRecursively(source: File, target: File) {
        if (source.isDirectory) {
            if (!target.exists() && !target.mkdirs()) {
                throw IOException("Unable to create ${target.path}")
            }
            source.listFiles()?.forEach { child ->
                copyRecursively(child, File(target, child.name))
            }
        } else {
            target.parentFile?.mkdirs()
            source.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        if (bytes < 1024 * 1024) return String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
        return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
    }
}
