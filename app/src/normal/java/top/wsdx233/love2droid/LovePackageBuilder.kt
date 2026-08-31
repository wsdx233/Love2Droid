package top.wsdx233.love2droid

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object LovePackageBuilder {
    fun build(project: Project, cacheRoot: File): File {
        val root = project.root.canonicalFile
        val mainLua = File(root, "main.lua")
        require(StorageUtils.isWithin(root, mainLua)) { "项目入口路径无效" }
        require(mainLua.isFile) { "项目缺少 main.lua" }

        val outputDirectory = File(cacheRoot, "love-packages")
        if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
            throw IOException("无法创建 LÖVE 临时目录")
        }
        outputDirectory.listFiles()?.forEach { file ->
            if (file.extension == "love" || file.name.endsWith(".tmp")) {
                StorageUtils.deleteRecursively(file)
            }
        }

        val temporary = File(outputDirectory, "${project.id}-${System.nanoTime()}.tmp")
        val output = File(outputDirectory, "${project.id}.love")
        ZipOutputStream(BufferedOutputStream(FileOutputStream(temporary))).use { zip ->
            addDirectory(root, root, zip)
        }
        if (!temporary.renameTo(output)) {
            temporary.inputStream().use { input -> output.outputStream().use { input.copyTo(it) } }
            temporary.delete()
        }
        return output
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
}
