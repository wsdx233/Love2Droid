package top.wsdx233.love2droid

import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File
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
    private const val READY_MARKER_NAME = ".setup-complete"

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

    fun luaLanguageServer(context: Context): File =
        File(rootfsDir(context), LUA_LANGUAGE_SERVER_GUEST_PATH.removePrefix("/"))

    fun gitBinary(context: Context): File = File(rootfsDir(context), "usr/bin/git")

    fun prootBinary(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, PROOT_LIBRARY_NAME)

    fun isEnvironmentReady(context: Context): Boolean {
        return isSupportedDevice() &&
            prootBinary(context).isFile &&
            rootfsDir(context).isDirectory &&
            readyMarker(context).isFile &&
            luaLanguageServer(context).isFile &&
            isOmpReady(context) &&
            isBashPromptReady(context) &&
            areHostGroupsConfigured(context)
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
        return ompBinary(context).isFile &&
            bashrc.isFile &&
            bashrc.useLines { lines -> lines.any { it.trim() == OMP_BASHRC_ENTRY } }
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
        check(isEnvironmentReady(context)) { "Proot environment is not ready" }
        val guestWorkingDirectory = projectRoot
            ?.takeIf { it.isDirectory }
            ?.absolutePath
            ?: "/root"
        val spec = buildLaunch(
            context = context,
            guestWorkingDirectory = guestWorkingDirectory,
            terminalType = "xterm-256color",
            guestCommand = listOf(resolveGuestShell(context), "-l"),
        )
        return TerminalLaunchSpec(
            executable = spec.command.first(),
            workingDirectory = spec.workingDirectory.absolutePath,
            arguments = spec.command.drop(1).toTypedArray(),
            environment = spec.environment.map { (key, value) -> "$key=$value" }.toTypedArray(),
        )
    }

    fun shellLaunch(context: Context, script: String): LaunchSpec {
        return buildLaunch(
            context = context,
            guestWorkingDirectory = "/root",
            terminalType = "dumb",
            guestCommand = listOf(resolveGuestShell(context), "-l", "-c", script),
        )
    }

    fun projectCommandLaunch(context: Context, projectRoot: File, guestCommand: List<String>): LaunchSpec {
        check(isEnvironmentReady(context)) { "Proot environment is not ready" }
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
        check(isEnvironmentReady(context)) { "Proot environment is not ready" }
        return buildLaunch(
            context = context,
            guestWorkingDirectory = projectRoot.absolutePath,
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
        val proot = prootBinary(context)
        check(proot.isFile) { "Bundled proot library is missing: ${proot.absolutePath}" }
        val rootfs = rootfsDir(context)
        check(rootfs.isDirectory) { "Ubuntu rootfs is missing: ${rootfs.absolutePath}" }

        val hostTmp = hostTmpDir(context).apply { mkdirs() }
        val command = mutableListOf(
            proot.absolutePath,
            "-L",
            "--link2symlink",
            "--kill-on-exit",
            "--root-id",
            "-r",
            rootfs.absolutePath,
        )

        val bindPaths = linkedSetOf<String>()
        listOf(
            "/dev",
            "/proc",
            "/sys",
            context.filesDir.absolutePath,
            context.filesDir.parentFile?.absolutePath,
            context.cacheDir.absolutePath,
            context.cacheDir.parentFile?.absolutePath,
            "/data/data/${context.packageName}/files",
            "/data/data/${context.packageName}/cache",
            "/storage",
            "/sdcard",
            "/mnt",
            guestWorkingDirectory.takeIf { it.startsWith("/") },
        ).forEach { path ->
            if (!path.isNullOrBlank()) bindPaths += path
        }
        bindPaths.forEach { path ->
            if (File(path).exists()) command += listOf("-b", path)
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
            workingDirectory = runtimeDir(context).apply { mkdirs() },
            environment = mapOf(
                "TMPDIR" to hostTmp.absolutePath,
                "PROOT_TMP_DIR" to hostTmp.absolutePath,
            ),
        )

    }

    private fun resolveGuestShell(context: Context): String {
        val rootfs = rootfsDir(context)
        return when {
            File(rootfs, "bin/bash").isFile -> "/bin/bash"
            File(rootfs, "usr/bin/bash").isFile -> "/usr/bin/bash"
            else -> "/bin/sh"
        }
    }
}
