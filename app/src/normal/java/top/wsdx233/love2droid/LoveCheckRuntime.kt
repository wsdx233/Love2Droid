package top.wsdx233.love2droid

import android.content.Context
import java.io.File

internal object LoveCheckRuntime {
    const val GUEST_DIRECTORY = "/root/.local/share/love2droid/love-check"
    const val GUEST_COMMAND = "/usr/local/bin/love-check"
    const val READY_CONTENT = "love=11.5\nchecker=1\n"
    val assetNames = listOf("install.sh", "love-check.py", "bootstrap.lua", "syntax.lua", "doctor.lua")
    // /usr/bin/love goes through absolute guest alternatives links, which the Android host cannot resolve.
    private val executables = listOf("usr/bin/love-11.5", "usr/bin/luajit", "usr/bin/python3", "usr/bin/Xvfb")

    fun readyMarker(context: Context): File = File(ProotRuntime.runtimeDir(context), ".love-check-complete")

    fun isReady(context: Context): Boolean = ProotRuntime.isRootfsReady(context) &&
        hasInstalledFiles(ProotRuntime.rootfsDir(context), readyMarker(context))

    internal fun hasInstalledFiles(rootfs: File, marker: File): Boolean =
        marker.isFile && marker.readText() == READY_CONTENT &&
            executables.all { File(rootfs, it).isFile } &&
            File(rootfs, GUEST_COMMAND.removePrefix("/")).isFile &&
            assetNames.all { File(rootfs, "${GUEST_DIRECTORY.removePrefix("/")}/$it").isFile }

    @Synchronized
    fun deploy(rootfs: File, assets: Map<String, String>) {
        check(rootfs.isDirectory) { "Ubuntu rootfs is missing" }
        val contents = assetNames.associate { name ->
            "${GUEST_DIRECTORY.removePrefix("/")}/$name" to requireNotNull(assets[name]) {
                "LÖVE checker asset is missing: $name"
            }
        } + (GUEST_COMMAND.removePrefix("/") to
            "#!/bin/sh\nexec /usr/bin/python3 $GUEST_DIRECTORY/love-check.py \"\$@\"\n")
        // Validate every target before writing any of the assets.
        contents.keys.forEach { path ->
            require(StorageUtils.isWithin(rootfs, File(rootfs, path))) { "LÖVE checker escapes rootfs" }
        }
        contents.forEach { (path, content) ->
            val target = File(rootfs, path)
            if (!target.isFile || target.readText() != content) StorageUtils.writeTextAtomic(target, content)
        }
        check(File(rootfs, GUEST_COMMAND.removePrefix("/")).setExecutable(true, false)) {
            "Cannot make LÖVE checker executable"
        }
    }
}
