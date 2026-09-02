package top.wsdx233.love2droid

import android.content.Context
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException

/** Copies project files through SAF without resolving external content URIs to paths. */
internal object SafFileTransfer {
    fun importDirectory(context: Context, sourceUri: android.net.Uri, targetDirectory: File) {
        require(targetDirectory.isDirectory) { "导入目标不是文件夹" }
        val source = DocumentFile.fromTreeUri(context, sourceUri)
            ?: throw IOException("无法打开源文件夹")
        require(source.isDirectory) { "选择的项目不是文件夹" }

        val rootName = safeDocumentName(source.name, "imported-folder")
        val importedRoot = File(targetDirectory, rootName)
        require(StorageUtils.isWithin(targetDirectory, importedRoot)) { "导入路径无效" }
        if (importedRoot.exists()) throw IOException("目标已存在：$rootName")
        if (!importedRoot.mkdirs()) throw IOException("无法创建导入文件夹")
        try {
            copyDocumentChildren(context, source, importedRoot)
        } catch (error: Throwable) {
            StorageUtils.deleteRecursively(importedRoot)
            throw error
        }
    }

    fun importFile(context: Context, sourceUri: android.net.Uri, targetDirectory: File) {
        require(targetDirectory.isDirectory) { "导入目标不是文件夹" }
        val source = DocumentFile.fromSingleUri(context, sourceUri)
            ?: throw IOException("无法打开源文件")
        require(source.isFile) { "选择的项目不是文件" }

        val name = safeDocumentName(source.name, "imported-file")
        val target = File(targetDirectory, name)
        require(StorageUtils.isWithin(targetDirectory, target)) { "导入路径无效" }
        if (target.exists()) throw IOException("目标已存在：$name")
        copyDocumentFileToLocal(context, source, target)
    }

    fun exportDirectory(
        context: Context,
        projectRoot: File,
        sourceDirectory: File,
        targetTreeUri: android.net.Uri,
    ) {
        require(sourceDirectory.isDirectory && StorageUtils.isWithin(projectRoot, sourceDirectory)) {
            "导出文件夹无效"
        }
        val targetRoot = DocumentFile.fromTreeUri(context, targetTreeUri)
            ?: throw IOException("无法打开导出目标")
        require(targetRoot.isDirectory) { "导出目标不是文件夹" }

        val sourceName = safeLocalName(sourceDirectory.name)
        if (targetRoot.findFile(sourceName) != null) {
            throw IOException("目标已存在：$sourceName")
        }
        val exportedRoot = targetRoot.createDirectory(sourceName)
            ?: throw IOException("无法创建导出文件夹")
        try {
            copyLocalChildren(context, projectRoot, sourceDirectory, exportedRoot)
        } catch (error: Throwable) {
            deleteDocumentTree(exportedRoot)
            throw error
        }
    }

    fun exportFile(
        context: Context,
        projectRoot: File,
        sourceFile: File,
        targetUri: android.net.Uri,
    ) {
        require(sourceFile.isFile && StorageUtils.isWithin(projectRoot, sourceFile)) {
            "导出文件无效"
        }
        val output = context.contentResolver.openOutputStream(targetUri, "wt")
            ?: throw IOException("无法打开导出文件")
        try {
            sourceFile.inputStream().use { input ->
                output.use { input.copyTo(it) }
            }
        } catch (error: Throwable) {
            output.close()
            throw error
        }
    }

    private fun copyDocumentChildren(context: Context, source: DocumentFile, target: File) {
        val children = source.listFiles()
        children.forEach { child ->
            val childName = safeDocumentName(child.name, if (child.isDirectory) "imported-folder" else "imported-file")
            val childTarget = File(target, childName)
            require(StorageUtils.isWithin(target, childTarget)) { "导入路径无效" }
            if (childTarget.exists()) throw IOException("目标已存在：$childName")
            if (child.isDirectory) {
                if (!childTarget.mkdirs()) throw IOException("无法创建导入文件夹")
                try {
                    copyDocumentChildren(context, child, childTarget)
                } catch (error: Throwable) {
                    StorageUtils.deleteRecursively(childTarget)
                    throw error
                }
            } else if (child.isFile) {
                copyDocumentFileToLocal(context, child, childTarget)
            } else {
                throw IOException("不支持的 SAF 项目：$childName")
            }
        }
    }

    private fun copyDocumentFileToLocal(context: Context, source: DocumentFile, target: File) {
        val parent = requireNotNull(target.parentFile) { "导入文件没有父目录" }
        val temporary = File.createTempFile(".${target.name}.", ".saf-import", parent)
        try {
            val input = context.contentResolver.openInputStream(source.uri)
                ?: throw IOException("无法读取导入文件：${target.name}")
            input.use { stream ->
                temporary.outputStream().use { output -> stream.copyTo(output) }
            }
            if (!temporary.renameTo(target)) throw IOException("无法保存导入文件：${target.name}")
        } finally {
            temporary.delete()
        }
    }

    private fun copyLocalChildren(
        context: Context,
        projectRoot: File,
        source: File,
        target: DocumentFile,
    ) {
        val children = source.listFiles() ?: throw IOException("无法读取导出文件夹：${source.name}")
        children.forEach { child ->
            require(StorageUtils.isWithin(projectRoot, child)) { "导出路径越界" }
            copyLocalEntry(context, projectRoot, child, target)
        }
    }

    private fun copyLocalEntry(
        context: Context,
        projectRoot: File,
        source: File,
        target: DocumentFile,
    ) {
        val name = safeLocalName(source.name)
        if (source.isDirectory) {
            val childTarget = target.createDirectory(name)
                ?: throw IOException("无法创建导出文件夹：$name")
            try {
                copyLocalChildren(context, projectRoot, source, childTarget)
            } catch (error: Throwable) {
                deleteDocumentTree(childTarget)
                throw error
            }
            return
        }
        if (!source.isFile) throw IOException("不支持的导出项目：$name")
        val mimeType = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(source.extension.lowercase())
            ?: "application/octet-stream"
        val destination = target.createFile(mimeType, name)
            ?: throw IOException("无法创建导出文件：$name")
        val output = context.contentResolver.openOutputStream(destination.uri, "wt")
            ?: throw IOException("无法写入导出文件：$name")
        source.inputStream().use { input -> output.use { input.copyTo(it) } }
    }

    private fun deleteDocumentTree(document: DocumentFile) {
        if (document.isDirectory) document.listFiles().forEach(::deleteDocumentTree)
        document.delete()
    }

    private fun safeDocumentName(value: String?, fallback: String): String {
        val name = value?.trim().orEmpty().ifBlank { fallback }
        require(isSafeName(name)) { "SAF 文件名无效" }
        return name
    }

    private fun safeLocalName(value: String): String {
        require(isSafeName(value)) { "本地文件名无效" }
        return value
    }

    private fun isSafeName(value: String): Boolean =
        value.isNotEmpty() && value != "." && value != ".." &&
            !value.contains('/') && !value.contains('\\') &&
            !value.any { it.isISOControl() }
}
