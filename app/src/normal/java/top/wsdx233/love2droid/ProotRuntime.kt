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
            isOmpReady(context)
    }

    fun ompBinary(context: Context): File =
        File(rootfsDir(context), OMP_GUEST_PATH.removePrefix("/"))

    fun rootBashrc(context: Context): File =
        File(rootfsDir(context), "root/.bashrc")

    fun isOmpReady(context: Context): Boolean {
        val bashrc = rootBashrc(context)
        return ompBinary(context).isFile &&
            bashrc.isFile &&
            bashrc.useLines { lines -> lines.any { it.trim() == OMP_BASHRC_ENTRY } }
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
