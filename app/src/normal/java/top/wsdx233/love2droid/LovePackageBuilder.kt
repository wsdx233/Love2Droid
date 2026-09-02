package top.wsdx233.love2droid

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object LovePackageBuilder {
    fun build(project: Project, cacheRoot: File): File {
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
        try {
            FileOutputStream(temporary).use { stream ->
                LoveArchiveTransfer.export(project, stream)
            }
            if (!temporary.renameTo(output)) {
                temporary.inputStream().use { input -> output.outputStream().use { input.copyTo(it) } }
                temporary.delete()
            }
            return output
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }
}
