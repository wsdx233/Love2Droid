package top.wsdx233.love2droid

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/** Host smoke entry point: run the production decoder against the real release image. */
object OfflineRootfsSmoke {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 3) { "Expected manifest, archive, destination" }
        val manifest = OfflineRootfs.parseManifest(File(args[0]).readText())
        val archive = File(args[1])
        val destination = File(args[2])
        check(!destination.exists()) { "Use a new destination for the full-image smoke check" }
        check(destination.parentFile.isDirectory || destination.parentFile.mkdirs())
        var lastPercent = -1
        OfflineRootfs.restore(destination, manifest, { archive.inputStream() },
            { target, path -> Files.createSymbolicLink(File(path).toPath(), File(target).toPath()); Unit },
            { path, mode ->
                val permissions = PosixFilePermission.entries.filterIndexed { index, _ ->
                    mode and (1 shl (8 - index)) != 0
                }.toSet()
                Files.setPosixFilePermissions(File(path).toPath(), permissions)
            },
            { Files.isSymbolicLink(it.toPath()) },
        ) { bytes ->
            val percent = (bytes * 100 / manifest.compressedBytes).toInt()
            if (percent / 10 != lastPercent / 10 || lastPercent < 0) {
                println("Offline image: $percent%")
                lastPercent = percent
            }
        }
        println("Restored verified ARM64 image: ${manifest.sha256}")
        println("Destination: ${destination.absolutePath}")
    }
}
