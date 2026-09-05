package top.wsdx233.love2droid

import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
object ProotRuntime {
    const val SUPPORTED_ABI = "arm64-v8a"
    private const val PROOT_LIBRARY_NAME = "libproot.so"
    private const val LUA_LANGUAGE_SERVER_GUEST_PATH = "/opt/lua-language-server/bin/lua-language-server"
    internal const val LUA_LSP_LOVE_LIBRARY_GUEST_PATH =
        "/opt/lua-language-server/meta/3rd/love2d/library"
    private const val OMP_GUEST_PATH = "/root/.local/bin/omp"
    private const val OMP_BASHRC_ENTRY = "export PATH=\"/root/.local/bin:\$PATH\""
    internal const val BASH_PROMPT_GUEST_PATH = "/root/.local/share/bash-prompt/prompt.sh"
    internal const val BASH_PROMPT_BASHRC_SOURCE = ". /root/.local/share/bash-prompt/prompt.sh"
    internal const val BASH_PROMPT_DIRTRIM_ENTRY = "PROMPT_DIRTRIM=1"
    internal const val BASH_PROMPT_PS1_ENTRY = "PS1=\"\$(prompt_get_ps1)\""
    const val READY_MARKER_NAME = ".setup-complete"
    const val ROOTFS_READY_MARKER_NAME = ".rootfs-complete"
    const val LSP_READY_MARKER_NAME = ".lsp-complete"
    const val OMP_READY_MARKER_NAME = ".omp-complete"
    const val DSH_READY_MARKER_NAME = ".dsh-complete"
    private const val RESOLV_CONF_PATH = "etc/resolv.conf"

    data class LaunchSpec(
        val command: List<String>,
        val workingDirectory: File,
        val environment: Map<String, String>,
    )

    data class TerminalLaunchSpec(
        val executable: String,
        val workingDirectory: String,
        val arguments: Array<String>,
        val environment: Array<String>,
    )

    fun isSupportedDevice(): Boolean {
        return Process.is64Bit() && Build.SUPPORTED_64_BIT_ABIS.contains(SUPPORTED_ABI)
    }

    fun runtimeDir(context: Context): File = File(context.filesDir, "proot")

    fun rootfsDir(context: Context): File = File(runtimeDir(context), "ubuntu")

    fun hostTmpDir(context: Context): File = File(runtimeDir(context), "tmp")

    fun readyMarker(context: Context): File = File(runtimeDir(context), READY_MARKER_NAME)

    fun rootfsReadyMarker(context: Context): File = File(runtimeDir(context), ROOTFS_READY_MARKER_NAME)

    fun lspReadyMarker(context: Context): File = File(runtimeDir(context), LSP_READY_MARKER_NAME)

    fun ompReadyMarker(context: Context): File = File(runtimeDir(context), OMP_READY_MARKER_NAME)
    fun dshReadyMarker(context: Context): File = File(runtimeDir(context), DSH_READY_MARKER_NAME)

    fun luaLanguageServer(context: Context): File =
        File(rootfsDir(context), LUA_LANGUAGE_SERVER_GUEST_PATH.removePrefix("/"))

    fun gitBinary(context: Context): File = File(rootfsDir(context), "usr/bin/git")

    fun prootBinary(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, PROOT_LIBRARY_NAME)

    /**
     * Checks if the base PRoot Linux container (Ubuntu rootfs, groups, resolver) is ready.
     * This is sufficient to launch a guest bash terminal or run CLI tools.
     */
    fun isRootfsReady(context: Context): Boolean {
        return isSupportedDevice() &&
            prootBinary(context).isFile &&
            rootfsDir(context).isDirectory &&
            (rootfsReadyMarker(context).isFile || readyMarker(context).isFile) &&
            areHostGroupsConfigured(context) &&
            isResolverConfigured(context)
    }

    /**
     * Checks if Lua Language Server is ready.
     */
    fun isLspReady(context: Context): Boolean {
        return isRootfsReady(context) &&
            luaLanguageServer(context).isFile &&
            (lspReadyMarker(context).isFile || readyMarker(context).isFile)
    }

    /**
     * Legacy/full environment check: whether PRoot rootfs, LSP, OMP, and prompt styling are all ready.
     */
    fun isEnvironmentReady(context: Context): Boolean {
        return isRootfsReady(context) &&
            luaLanguageServer(context).isFile &&
            isOmpReady(context) &&
            isBashPromptReady(context)
    }
    internal fun hostSupplementaryGroupIds(): Set<Int> {
        val procStatus = runCatching { File("/proc/self/status").readText(Charsets.UTF_8) }
            .getOrNull()
            ?: return emptySet()
        return hostSupplementaryGroupIds(procStatus)
    }

    internal fun hostSupplementaryGroupIds(procStatus: String): Set<Int> {
        val groups = procStatus.lineSequence()
            .firstOrNull { line -> line.startsWith("Groups:") }
            ?.substringAfter(':')
            ?: return emptySet()
        return groups.splitToSequence(' ', '\t')
            .mapNotNull(String::toIntOrNull)
            .filter { it >= 0 }
            .toSortedSet()
    }

    internal fun missingHostGroupIds(groupContent: String, requiredGroupIds: Set<Int>): Set<Int> {
        val configuredGroupIds = groupContent.lineSequence()
            .mapNotNull { line -> line.split(':', limit = 4).getOrNull(2)?.toIntOrNull() }
            .toSet()
        return requiredGroupIds.filterTo(sortedSetOf()) { it !in configuredGroupIds }
    }

    private fun areHostGroupsConfigured(context: Context): Boolean {
        val requiredGroupIds = hostSupplementaryGroupIds()
        if (requiredGroupIds.isEmpty()) return true
        val groupContent = runCatching {
            File(rootfsDir(context), "etc/group").readText(Charsets.UTF_8)
        }.getOrNull() ?: return false
        return missingHostGroupIds(groupContent, requiredGroupIds).isEmpty()
    }


    fun ompBinary(context: Context): File =
        File(rootfsDir(context), OMP_GUEST_PATH.removePrefix("/"))
    internal fun ompStartupCommand(): String = "omp --allow-home --continue"

    fun ompModelsFile(context: Context): File {
        val directory = File(rootfsDir(context), "root/.omp/agent")
        return listOf("models.yml", "models.yaml")
            .map { File(directory, it) }
            .firstOrNull(File::isFile)
            ?: File(directory, "models.yml")
    }
    fun rootBashrc(context: Context): File =
        File(rootfsDir(context), "root/.bashrc")

    fun isOmpReady(context: Context): Boolean {
        val bashrc = rootBashrc(context)
        return isRootfsReady(context) &&
            ompBinary(context).isFile &&
            bashrc.isFile &&
            (ompReadyMarker(context).isFile || readyMarker(context).isFile) &&
            bashrc.useLines { lines -> lines.any { it.trim() == OMP_BASHRC_ENTRY } }
    }

    fun isGitReady(context: Context): Boolean =
        isRootfsReady(context) && gitBinary(context).isFile

    fun dshBinary(context: Context): File =
        File(rootfsDir(context), "root/.local/bin/dsh")

    internal fun dshStartupCommand(): String = "exec dsh --profile web --no-open --port 3080"

    fun isDshReady(context: Context): Boolean {
        return isRootfsReady(context) &&
            (dshReadyMarker(context).isFile || dshBinary(context).isFile)
    }

    fun bashPromptScript(context: Context): File =
        File(rootfsDir(context), BASH_PROMPT_GUEST_PATH.removePrefix("/"))

    fun isBashPromptReady(context: Context): Boolean {
        val bashrc = rootBashrc(context)
        if (!bashPromptScript(context).isFile || !bashrc.isFile) return false
        var hasSource = false
        var hasDirTrim = false
        var hasPrompt = false
        bashrc.useLines { lines ->
            lines.forEach { line ->
                when (line.trim()) {
                    BASH_PROMPT_BASHRC_SOURCE -> hasSource = true
                    BASH_PROMPT_DIRTRIM_ENTRY -> hasDirTrim = true
                    BASH_PROMPT_PS1_ENTRY -> hasPrompt = true
                }
            }
        }
        return hasSource && hasDirTrim && hasPrompt
    }



    fun terminalLaunch(context: Context, projectRoot: File?): TerminalLaunchSpec {
        check(isRootfsReady(context)) { "Proot rootfs is not ready" }
        val guestWorkingDirectory = projectRoot?.let { root ->
            val canonicalRoot = root.canonicalFile
            require(canonicalRoot.isDirectory) { "Project directory is missing" }
            canonicalRoot.absolutePath
        } ?: "/root"
        val spec = buildLaunch(
            context = context,
            guestWorkingDirectory = guestWorkingDirectory,
            terminalType = "xterm-256color",
            guestCommand = listOf(resolveGuestShell(context), "-l"),
        )
        return terminalSpec(spec)
    }

    internal fun terminalSpec(spec: LaunchSpec): TerminalLaunchSpec = TerminalLaunchSpec(
        executable = spec.command.first(),
        workingDirectory = spec.workingDirectory.absolutePath,
        // TerminalSession passes this array directly to execvp(), including argv[0].
        arguments = spec.command.toTypedArray(),
        environment = spec.environment.map { (key, value) -> "$key=$value" }.toTypedArray(),
    )

    fun shellLaunch(context: Context, script: String): LaunchSpec {
        return buildLaunch(
            context = context,
            guestWorkingDirectory = "/root",
            terminalType = "dumb",
            guestCommand = listOf(resolveGuestShell(context), "-l", "-c", script),
        )
    }

    fun projectCommandLaunch(context: Context, projectRoot: File, guestCommand: List<String>): LaunchSpec {
        check(isRootfsReady(context)) { "Proot rootfs is not ready" }
        require(guestCommand.isNotEmpty()) { "Guest command is required" }
        val canonicalRoot = projectRoot.canonicalFile
        require(canonicalRoot.isDirectory) { "Project directory is missing" }
        return buildLaunch(
            context = context,
            guestWorkingDirectory = canonicalRoot.absolutePath,
            terminalType = "dumb",
            guestCommand = guestCommand,
        )
    }

    fun languageServerLaunch(context: Context, projectRoot: File): LaunchSpec {
        check(isLspReady(context)) { "Lua language server is not ready" }
        val canonicalRoot = projectRoot.canonicalFile
        require(canonicalRoot.isDirectory) { "Project directory is missing" }
        return buildLaunch(
            context = context,
            guestWorkingDirectory = canonicalRoot.absolutePath,
            terminalType = "dumb",
            guestCommand = listOf(LUA_LANGUAGE_SERVER_GUEST_PATH),
        )
    }
    private fun buildLaunch(
        context: Context,
        guestWorkingDirectory: String,
        terminalType: String,
        guestCommand: List<String>,
    ): LaunchSpec {
        check(isSupportedDevice()) { "Only arm64-v8a supports the bundled proot runtime" }
        val rootfs = rootfsDir(context)
        val externalFilesDir = context.getExternalFilesDir(null)
        prepareProjectsLink(rootfs, externalFilesDir ?: context.filesDir)
        return buildLaunch(
            proot = prootBinary(context),
            rootfs = rootfs,
            hostTmp = hostTmpDir(context),
            filesDir = context.filesDir,
            cacheDir = context.cacheDir,
            externalFilesDir = externalFilesDir,
            guestWorkingDirectory = guestWorkingDirectory,
            terminalType = terminalType,
            guestCommand = guestCommand,
        )
    }

    internal fun buildLaunch(
        proot: File,
        rootfs: File,
        hostTmp: File,
        filesDir: File,
        cacheDir: File,
        externalFilesDir: File?,
        guestWorkingDirectory: String,
        terminalType: String,
        guestCommand: List<String>,
    ): LaunchSpec {
        check(proot.isFile) { "Bundled proot library is missing: ${proot.absolutePath}" }
        require(guestWorkingDirectory.startsWith('/') && guestWorkingDirectory.split('/').none { it == ".." }) {
            "Invalid guest working directory"
        }
        check(rootfs.isDirectory) { "Ubuntu rootfs is missing: ${rootfs.absolutePath}" }

        check(hostTmp.isDirectory || hostTmp.mkdirs()) { "PRoot temporary directory is unavailable" }
        val command = mutableListOf(
            proot.absolutePath,
            "--link2symlink",
            "--kill-on-exit",
            "--root-id",
            "-r",
            rootfs.absolutePath,
        )

        // PRoot creates its own mount placeholders, which can have mode 000.
        // Never mkdir through them on the host. Bind accessible app directories,
        // not Android's /storage or /mnt parents whose children may be hidden.
        val bindPaths = linkedSetOf("/dev", "/proc", "/sys")
        listOfNotNull(filesDir, cacheDir, externalFilesDir).forEach { directory ->
            bindPaths += directory.absolutePath
            bindPaths += directory.canonicalPath
        }
        if (guestWorkingDirectory != "/root") bindPaths += guestWorkingDirectory
        bindPaths.forEach { path ->
            if (File(path).exists()) command += listOf("-b", "$path:$path!")
        }
        listOf(
            "/dev/urandom" to "/dev/random",
            "/proc/self/fd" to "/dev/fd",
        ).forEach { (hostPath, guestPath) ->
            if (File(hostPath).exists()) command += listOf("-b", "$hostPath:$guestPath")
        }
        listOf(
            hostTmp.absolutePath to "/tmp",
            hostTmp.absolutePath to "/var/tmp",
        ).forEach { (hostPath, guestPath) ->
            command += listOf("-b", "$hostPath:$guestPath")
        }
        val devShmSource = File(rootfs, "tmp")
        if (devShmSource.exists()) {
            command += listOf("-b", "${devShmSource.absolutePath}:/dev/shm")
        }
        val fakeFips = File(rootfs, "proc/sys/crypto/fips_enabled")
        if (fakeFips.exists()) {
            command += listOf("-b", "${fakeFips.absolutePath}:/proc/sys/crypto/fips_enabled")
        }

        command += listOf(
            "-w",
            guestWorkingDirectory,
            "/usr/bin/env",
            "-i",
            "HOME=/root",
            "LANG=C.UTF-8",
            "PATH=/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "XDG_DATA_HOME=/root/.local/share",
            "XDG_CACHE_HOME=/root/.cache",
            "TMPDIR=/tmp",
            "R2_NOCOLOR=1",
            "TERM=$terminalType",
            // Android shared storage does not reliably support Git's hard-link object finalization.
            // Force Git to rename its temporary object instead; this is inherited by terminal Git.
            "GIT_CONFIG_COUNT=1",
            "GIT_CONFIG_KEY_0=core.createObject",
            "GIT_CONFIG_VALUE_0=rename",
        )
        command += guestCommand

        return LaunchSpec(
            command = command,
            workingDirectory = rootfs.parentFile,
            environment = mapOf(
                "TMPDIR" to hostTmp.absolutePath,
                "PROOT_TMP_DIR" to hostTmp.absolutePath,
                "PROOT_DONT_POLLUTE_ROOTFS" to "1",
            ),
        )
    }

    @Synchronized
    internal fun prepareProjectsLink(
        rootfs: File,
        appFilesDir: File,
        readLink: (String) -> String? = ::readLinkIfPresent,
        createLink: (String, String) -> Unit = Os::symlink,
    ) {
        check(rootfs.isDirectory) { "Ubuntu rootfs is missing" }
        val home = File(rootfs, "root")
        require(StorageUtils.isWithin(rootfs, home)) { "Guest home escapes rootfs" }
        val projects = File(appFilesDir, "projects").canonicalFile
        require(StorageUtils.isWithin(appFilesDir, projects)) { "Projects directory escapes app storage" }
        check(projects.isDirectory || projects.mkdirs()) { "Projects directory is unavailable" }
        check(home.isDirectory || home.mkdirs()) { "Guest home is unavailable" }
        val link = File(home, "projects")
        val target = projects.absolutePath
        val currentTarget = readLink(link.absolutePath)
        if (currentTarget != null) {
            if (currentTarget == target) return
            check(link.delete()) { "Cannot update projects shortcut" }
        } else if (link.exists()) {
            // Never replace a user's real file or directory with the shortcut.
            return
        }
        createLink(target, link.absolutePath)
    }

    private fun readLinkIfPresent(path: String): String? = try {
        Os.readlink(path)
    } catch (error: ErrnoException) {
        if (error.errno != OsConstants.ENOENT && error.errno != OsConstants.EINVAL) throw error
        null
    }

    internal fun resolverConfig(): String =
        "nameserver 8.8.8.8\nnameserver 8.8.4.4\noptions use-vc timeout:2 attempts:2\n"

    private fun isResolverConfigured(context: Context): Boolean =
        runCatching {
            File(rootfsDir(context), RESOLV_CONF_PATH).readText(Charsets.UTF_8) == resolverConfig()
        }.getOrDefault(false)

    private fun resolveGuestShell(context: Context): String {
        val rootfs = rootfsDir(context)
        return when {
            File(rootfs, "bin/bash").isFile -> "/bin/bash"
            File(rootfs, "usr/bin/bash").isFile -> "/usr/bin/bash"
            else -> "/bin/sh"
        }
    }
}
