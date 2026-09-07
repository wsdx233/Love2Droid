package top.wsdx233.love2droid

import java.io.File

/** Maps canonical guest paths to the app's actual PRoot mounts, never Android's /proc or /dev. */
internal class EditorFileAccess(
    rootfs: File,
    hostTmp: File,
    filesDir: File,
    cacheDir: File,
    externalFilesDir: File?,
) {
    private val roots = listOfNotNull(rootfs, hostTmp, filesDir, cacheDir, externalFilesDir)
        .map { it.canonicalFile }.distinct()
    private val bindings = buildList {
        listOfNotNull(filesDir, cacheDir, externalFilesDir).forEach { root ->
            add(root.absolutePath to root.canonicalFile)
            add(root.canonicalPath to root.canonicalFile)
        }
        add("/tmp" to hostTmp.canonicalFile)
        add("/var/tmp" to hostTmp.canonicalFile)
        add("/dev/shm" to File(rootfs, "tmp").canonicalFile)
        add("/" to rootfs.canonicalFile)
    }.distinct().sortedByDescending { it.first.length }

    fun guestFile(path: String): File {
        require(path.startsWith('/') && '\u0000' !in path && path.split('/').none { it == ".." })
        require(listOf("/proc", "/sys", "/dev").none { prefix ->
            inside(prefix, path) && !inside("/dev/shm", path)
        })
        val (guest, host) = bindings.first { (guest, _) -> inside(guest, path) }
        val target = File(host, path.removePrefix(guest).trimStart('/')).canonicalFile
        // A symlink changed after the guest resolved it must not escape its mount.
        require(StorageUtils.isWithin(host, target))
        return hostFile(target.path)
    }

    fun hostFile(path: String): File {
        require(File(path).isAbsolute && '\u0000' !in path)
        return File(path).canonicalFile.also { target ->
            require(roots.any { StorageUtils.isWithin(it, target) })
        }
    }

    fun workspaceFile(projectRoot: File, path: String?, externalPath: String?): File? = when {
        externalPath != null -> {
            require(path == null)
            hostFile(externalPath)
        }
        !path.isNullOrBlank() -> StorageUtils.resolveChild(projectRoot, path)
        else -> null
    }

    private fun inside(root: String, path: String): Boolean =
        root == "/" || path == root || path.startsWith("$root/")
}
