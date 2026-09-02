package top.wsdx233.love2droid

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal object AndroidApkAssembler {
    fun assemble(
        templateInput: InputStream,
        runtimeApks: List<File>,
        properties: AndroidProjectProperties,
        lovePackage: File,
        icon: File?,
        target: File,
    ) {
        require(runtimeApks.isNotEmpty() && runtimeApks.all(File::isFile)) { "Runtime APK is unavailable" }
        require(lovePackage.isFile) { "Game package is unavailable" }
        val written = hashSetOf<String>()
        ZipOutputStream(FileOutputStream(target).buffered()).use { output ->
            ZipInputStream(BufferedInputStream(templateInput)).use { template ->
                while (true) {
                    val entry = template.nextEntry ?: break
                    if (entry.isDirectory || entry.name.startsWith("META-INF/")) continue
                    val bytes = template.readBytes()
                    val content = when (entry.name) {
                        MANIFEST_ENTRY -> AndroidBinaryXmlEditor.customizeManifest(bytes, properties)
                        ICON_ENTRY -> icon?.readBytes() ?: bytes
                        else -> bytes
                    }
                    writeBytes(output, entry.name, content)
                    written += entry.name
                }
            }
            check(MANIFEST_ENTRY in written && RESOURCES_ENTRY in written && ICON_ENTRY in written) {
                "Android package template is incomplete"
            }
            runtimeApks.forEach { source ->
                ZipFile(source).use { apk ->
                    val entries = apk.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.isDirectory || entry.name in written || !isRuntimeEntry(entry.name)) continue
                        apk.getInputStream(entry).use { input ->
                            output.putNextEntry(ZipEntry(entry.name).apply { time = 0L })
                            input.copyTo(output)
                            output.closeEntry()
                        }
                        written += entry.name
                    }
                }
            }
            check(written.any(CLASSES_ENTRY::matches)) { "Runtime APK contains no classes" }
            check(written.any { it.endsWith("/liblove.so") }) { "Runtime APK contains no LÖVE native library" }
            writeStoredFile(output, GAME_ASSET_ENTRY, lovePackage)
        }
    }

    private fun isRuntimeEntry(name: String): Boolean {
        if (CLASSES_ENTRY.matches(name)) return true
        if (!name.startsWith("lib/")) return false
        return name.substringAfterLast('/') in RUNTIME_LIBRARIES
    }

    private fun writeBytes(output: ZipOutputStream, name: String, bytes: ByteArray) {
        output.putNextEntry(ZipEntry(name).apply { time = 0L })
        output.write(bytes)
        output.closeEntry()
    }

    private fun writeStoredFile(output: ZipOutputStream, name: String, source: File) {
        val crc = CRC32()
        FileInputStream(source).buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                crc.update(buffer, 0, count)
            }
        }
        output.putNextEntry(ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = source.length()
            compressedSize = source.length()
            this.crc = crc.value
            time = 0L
        })
        FileInputStream(source).buffered().use { input -> input.copyTo(output) }
        output.closeEntry()
    }

    private const val MANIFEST_ENTRY = "AndroidManifest.xml"
    private const val RESOURCES_ENTRY = "resources.arsc"
    private const val ICON_ENTRY = "res/drawable/icon.png"
    private const val GAME_ASSET_ENTRY = "assets/game.love"
    private val CLASSES_ENTRY = Regex("classes(?:[2-9]|[1-9][0-9]+)?\\.dex")
    private val RUNTIME_LIBRARIES = setOf(
        "libc++_shared.so",
        "libliblove.so",
        "liblove.so",
        "libluajit.so",
        "liboboe.so",
        "libopenal.so",
        "libSDL3.so",
    )
}
