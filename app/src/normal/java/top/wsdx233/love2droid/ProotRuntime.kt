package top.wsdx233.love2droid

import android.content.Context
import android.os.Build
import android.os.Process
import org.json.JSONObject
import java.io.File
object ProotRuntime {
    const val SUPPORTED_ABI = "arm64-v8a"
    private const val OMP_SESSION_MTIME_TOLERANCE_MS = 2_000L
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

    fun prootBinary(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, PROOT_LIBRARY_NAME)

    fun isEnvironmentReady(context: Context): Boolean {
        return isSupportedDevice() &&
            prootBinary(context).isFile &&
            rootfsDir(context).isDirectory &&
            readyMarker(context).isFile &&
            luaLanguageServer(context).isFile &&
            isOmpReady(context) &&
            isBashPromptReady(context)
    }

    fun ompBinary(context: Context): File =
        File(rootfsDir(context), OMP_GUEST_PATH.removePrefix("/"))
    fun ompModelsFile(context: Context): File {
        val directory = File(rootfsDir(context), "root/.omp/agent")
        return listOf("models.yml", "models.yaml")
            .map { File(directory, it) }
            .firstOrNull(File::isFile)
            ?: File(directory, "models.yml")
    }
    /**
     * OMP names its top-level transcript `<timestamp>_<session-id>.jsonl`.
     * Read only the generated filename; do not invent an ID that `-r` cannot resume.
     */
    internal fun ompSessionIdFromFileName(name: String): String? {
        if (!name.endsWith(".jsonl")) return null
        val stem = name.removeSuffix(".jsonl")
        val separator = stem.lastIndexOf('_')
        if (separator < 0 || separator == stem.lastIndex) return null
        return stem.substring(separator + 1)
            .takeIf { it.isNotEmpty() && it.length <= 256 && it.all { char -> char.isLetterOrDigit() || char in "-_.:" } }
    }

    fun ompSessionFiles(context: Context): List<File> {
        val sessionsRoot = File(rootfsDir(context), "root/.omp/agent/sessions")
        return sessionsRoot.listFiles()
            ?.asSequence()
            ?.filter(File::isDirectory)
            ?.flatMap { directory ->
                directory.listFiles()
                    ?.asSequence()
                    ?.filter { it.isFile && it.name.endsWith(".jsonl") }
                    ?: emptySequence()
            }
            ?.sortedByDescending(File::lastModified)
            ?.toList()
            ?: emptyList()
    }

    /** Finds a newly created OMP session for this terminal's working directory. */
    fun findOmpSessionId(
        context: Context,
        workingDirectory: File?,
        notBeforeMillis: Long,
        excludedSessionIds: Set<String> = emptySet(),
    ): String? {
        val expectedDirectory = workingDirectory?.canonicalFile?.path ?: "/root"
        return ompSessionFiles(context)
            .asSequence()
            .filter { it.lastModified() >= notBeforeMillis - OMP_SESSION_MTIME_TOLERANCE_MS }
            .mapNotNull { file ->
                val id = ompSessionIdFromFileName(file.name) ?: return@mapNotNull null
                if (id in excludedSessionIds) return@mapNotNull null
                val cwd = readOmpSessionCwd(file) ?: return@mapNotNull null
                if (cwd == expectedDirectory) id else null
            }
            .firstOrNull()
    }
    private fun readOmpSessionCwd(file: File): String? {
        return runCatching {
            file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.take(8)
                    .mapNotNull { line -> runCatching { JSONObject(line) }.getOrNull() }
                    .firstOrNull { it.optString("type") == "session" }
                    ?.optString("cwd")
                    ?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
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
