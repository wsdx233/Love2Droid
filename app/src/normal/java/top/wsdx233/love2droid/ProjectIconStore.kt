package top.wsdx233.love2droid

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.IOException

internal class ProjectIconStore(context: Context) {
    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "project-icons")

    fun iconFile(projectId: String): File? = File(root, "$projectId.png").takeIf(File::isFile)

    fun importIcon(projectId: String, uri: Uri): File {
        require(ProjectRepository.isSafeProjectId(projectId)) { "Invalid project ID" }
        if (!root.exists() && !root.mkdirs()) throw IOException("Unable to create project icon directory")
        val temporary = File(root, "$projectId-${System.nanoTime()}.tmp")
        val normalized = File(root, "$projectId-${System.nanoTime()}.png.tmp")
        try {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_ICON_BYTES) { "Project icon is too large" }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: throw IOException("Unable to read selected icon")
            validateImage(temporary)
            val bitmap = requireNotNull(BitmapFactory.decodeFile(temporary.absolutePath)) { "Unable to decode project icon" }
            try {
                normalized.outputStream().buffered().use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Unable to encode project icon" }
                }
            } finally {
                bitmap.recycle()
            }
            val target = File(root, "$projectId.png")
            if (target.exists() && !target.delete()) throw IOException("Unable to replace project icon")
            if (!normalized.renameTo(target)) {
                normalized.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
                normalized.delete()
            }
            return target
        } finally {
            temporary.delete()
            normalized.delete()
        }
    }

    fun renameProject(oldId: String, newId: String) {
        if (oldId == newId) return
        val source = File(root, "$oldId.png")
        if (!source.isFile) return
        val target = File(root, "$newId.png")
        if (target.exists()) target.delete()
        if (!source.renameTo(target)) {
            source.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
            source.delete()
        }
    }

    fun deleteProject(projectId: String) {
        File(root, "$projectId.png").delete()
    }

    private fun validateImage(file: File) {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        require(options.outWidth in 1..MAX_ICON_DIMENSION && options.outHeight in 1..MAX_ICON_DIMENSION) {
            "Project icon must be a valid image up to ${MAX_ICON_DIMENSION}x$MAX_ICON_DIMENSION"
        }
    }

    private companion object {
        const val MAX_ICON_BYTES = 16L * 1024L * 1024L
        const val MAX_ICON_DIMENSION = 4096
    }
}
