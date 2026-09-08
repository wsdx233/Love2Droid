package top.wsdx233.love2droid

import org.json.JSONObject
import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest

/** Image metadata and atomic restoration, independent of Android and network clients. */
internal object OfflineRootfs {
    const val ASSET_DIRECTORY = "proot/offline"
    const val IMAGE_MARKER = ".love2droid-offline-image"
    const val VERIFIED_MARKER = ".offline-complete"
    private const val DECODER_MEMORY_KIB = 65536
    private val requiredComponents = setOf("rootfs", "lua_lsp", "omp", "dsh", "git", "love_check")

    data class Manifest(
        val sha256: String,
        val compressedBytes: Long,
        val unpackedBytes: Long,
        val entryCount: Int,
        val archive: String = "rootfs-arm64.tar.xz",
    ) {
        // Include filesystem allocation overhead and room for the verification run.
        val requiredSpace: Long get() = unpackedBytes + entryCount.toLong() * 4096 + 64L * 1024 * 1024
    }

    fun parseManifest(text: String): Manifest {
        val json = JSONObject(text)
        require(json.getInt("formatVersion") == 1) { "Unsupported offline image format" }
        require(json.getString("abi") == "arm64-v8a") { "Offline image is not ARM64" }
        require(json.getString("archive") == "rootfs-arm64.tar.xz") { "Invalid offline archive name" }
        require(json.getInt("decoderMemoryLimitKiB") <= DECODER_MEMORY_KIB) { "Offline image requires too much decoder memory" }
        val components = json.getJSONArray("components")
        require((0 until components.length()).map { components.getString(it) }.toSet() == requiredComponents) {
            "Offline image must contain every component, including love-check"
        }
        return Manifest(json.getString("sha256"), json.getLong("compressedBytes"),
            json.getLong("unpackedBytes"), json.getInt("entryCount")).also {
            require(it.sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid offline image checksum" }
            require(it.compressedBytes in 1..(2L * 1024 * 1024 * 1024)) { "Invalid offline image size" }
            require(it.unpackedBytes in 1..(16L * 1024 * 1024 * 1024)) { "Invalid unpacked image size" }
            require(it.entryCount in 1..1_000_000) { "Invalid offline image entry count" }
        }
    }

    fun canRestore(rootfs: File, manifest: Manifest): Boolean {
        if (!rootfs.exists()) return true
        if (!rootfs.isDirectory) return false
        val image = File(rootfs, IMAGE_MARKER)
        return (image.isFile && image.readText() == manifest.sha256) || rootfs.list()?.isEmpty() == true
    }

    /** Returns false when a previously restored image can resume device verification. */
    fun restore(
        rootfs: File,
        manifest: Manifest,
        openArchive: () -> InputStream,
        createSymlink: (String, String) -> Unit,
        setMode: (String, Int) -> Unit,
        isSymlink: (File) -> Boolean,
        onProgress: (Long) -> Unit = {},
    ): Boolean {
        check(canRestore(rootfs, manifest)) { "Existing rootfs will not be overwritten" }
        if (File(rootfs, IMAGE_MARKER).isFile) return false
        val staging = File(rootfs.parentFile, "ubuntu.offline-staging")
        if (staging.exists()) deleteStaging(staging, isSymlink)
        check(staging.mkdirs()) { "Cannot create offline staging directory" }
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var compressed = 0L
            openArchive().buffered().use { archive ->
                val source = DigestInputStream(archive, digest)
                val counting = object : FilterInputStream(source) {
                    override fun read(): Int = source.read().also { if (it >= 0) report(1) }
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                        source.read(bytes, offset, length).also { if (it > 0) report(it) }
                    private fun report(count: Int) {
                        compressed += count
                        check(compressed <= manifest.compressedBytes) { "Offline archive exceeds its declared size" }
                        onProgress(compressed)
                    }
                }
                XZInputStream(counting, DECODER_MEMORY_KIB).use { xz ->
                    val entries = RootfsArchive.extract(
                        object : FilterInputStream(xz) { override fun close() = Unit }, staging,
                        createSymlink, setMode, manifest.entryCount, manifest.unpackedBytes,
                    )
                    check(entries == manifest.entryCount) { "Offline archive entry count mismatch" }
                    // Read through the XZ footer, validating its checksum and any remaining padding.
                    val buffer = ByteArray(64 * 1024)
                    while (xz.read(buffer) >= 0) { /* drain */ }
                }
            }
            check(compressed == manifest.compressedBytes) { "Offline archive is incomplete" }
            check(digest.digest().joinToString("") { "%02x".format(it) } == manifest.sha256) {
                "Offline archive SHA-256 verification failed"
            }
            File(staging, IMAGE_MARKER).writeText(manifest.sha256)
            // A non-empty user rootfs is never deleted, including on an APK upgrade.
            check(!rootfs.exists() || rootfs.delete()) { "Existing rootfs will not be overwritten" }
            check(staging.renameTo(rootfs)) { "Cannot commit offline rootfs" }
            return true
        } finally {
            if (staging.exists()) deleteStaging(staging, isSymlink)
        }
    }

    private fun deleteStaging(file: File, isSymlink: (File) -> Boolean) {
        // Installed Linux trees contain directory aliases, including X11 -> . .
        // File.deleteRecursively follows them and can recurse outside the staging tree.
        if (file.isDirectory && !isSymlink(file)) {
            val children = checkNotNull(file.listFiles()) { "Cannot list offline staging: $file" }
            children.forEach { deleteStaging(it, isSymlink) }
        }
        check(file.delete()) { "Cannot delete offline staging: $file" }
    }
}
