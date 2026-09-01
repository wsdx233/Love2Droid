package top.wsdx233.love2droid

import android.content.Context
import android.system.Os
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import kotlinx.coroutines.launch
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

private const val UBUNTU_BASE_FILE = "ubuntu-base-24.04.4-base-arm64.tar.gz"
private const val UBUNTU_BASE_URL =
    "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04.4/release/$UBUNTU_BASE_FILE"
private const val UBUNTU_BASE_SHA256 =
    "04207713ece899c3740823d33690441ad3a7f0ded1101aca744e2b0f37ac7ff2"
private const val LUA_LS_VERSION = "3.19.1"
private const val LUA_LS_FILE = "lua-language-server-$LUA_LS_VERSION-linux-arm64.tar.gz"
private const val LUA_LS_URL =
    "https://github.com/LuaLS/lua-language-server/releases/download/$LUA_LS_VERSION/$LUA_LS_FILE"
private const val LUA_LS_SHA256 =
    "abd2572e8fc929dc838a81ffb8473c5bce0bf39bfe8edb4b120b3b623176ce83"


data class ProotInstallState(
    val status: Status = Status.IDLE,
    val progress: Int = 0,
    val message: String = "",
    val logs: List<String> = emptyList(),
) {
    enum class Status {
        IDLE,
        PREPARING,
        DOWNLOADING,
        EXTRACTING,
        CONFIGURING,
        INSTALLING,
        DONE,
        ERROR,
    }

    val isWorking: Boolean
        get() = status in setOf(
            Status.PREPARING,
            Status.DOWNLOADING,
            Status.EXTRACTING,
            Status.CONFIGURING,
            Status.INSTALLING,
        )
}

object ProotInstaller {
    private const val TAG = "ProotInstaller"
    private const val ROOTFS_MARKER = ".rootfs-extracted"
    private const val MAX_LOG_LINES = 400

    private val installMutex = Mutex()
    private val installScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var installJob: Job? = null
    private val _state = MutableStateFlow(ProotInstallState())
    val state = _state.asStateFlow()

    @Synchronized
    fun start(context: Context) {
        if (installJob?.isActive == true || _state.value.isWorking) return
        val appContext = context.applicationContext
        installJob = installScope.launch { install(appContext) }
    }

    suspend fun install(context: Context): Result<Unit> {
        val appContext = context.applicationContext
        return installMutex.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    _state.value = ProotInstallState()
                    check(ProotRuntime.isSupportedDevice()) {
                        appContext.getString(R.string.proot_arm64_required)
                    }
                    check(ProotRuntime.prootBinary(appContext).isFile) {
                        appContext.getString(R.string.proot_library_missing)
                    }
                    if (ProotRuntime.isEnvironmentReady(appContext)) {
                        update(
                            ProotInstallState.Status.DONE,
                            100,
                            appContext.getString(R.string.proot_install_done),
                        )
                        return@runCatching
                    }

                    prepareRuntime(appContext)
                    val rootfsMarker = File(ProotRuntime.runtimeDir(appContext), ROOTFS_MARKER)
                    if (!rootfsMarker.isFile || !File(ProotRuntime.rootfsDir(appContext), "bin").isDirectory) {
                        rootfsMarker.delete()
                        val archive = downloadUbuntuBase(appContext)
                        extractUbuntuBase(appContext, archive)
                        configureRootfs(appContext)
                        rootfsMarker.writeText("ubuntu=24.04.4\nsha256=$UBUNTU_BASE_SHA256\n")
                    } else {
                        appendLog(appContext.getString(R.string.proot_log_rootfs_reused))
                    }
                    repairRootfsPermissions(appContext)
                    installLuaLanguageServer(appContext)
                    writeReadyMarker(appContext)
                    File(appContext.cacheDir, "proot/$UBUNTU_BASE_FILE").delete()
                    update(
                        ProotInstallState.Status.DONE,
                        100,
                        appContext.getString(R.string.proot_install_done),
                    )
                    appendLog(appContext.getString(R.string.proot_log_completed))
                }.onFailure { error ->
                    Log.e(TAG, "Proot installation failed", error)
                    val detail = error.message ?: error::class.java.simpleName
                    appendLog(appContext.getString(R.string.proot_log_failed, detail))
                    update(
                        ProotInstallState.Status.ERROR,
                        _state.value.progress,
                        appContext.getString(R.string.proot_install_failed, detail),
                    )
                }
            }
        }
    }

    private fun prepareRuntime(context: Context) {
        update(
            ProotInstallState.Status.PREPARING,
            3,
            context.getString(R.string.proot_install_preparing),
        )
        ProotRuntime.runtimeDir(context).mkdirs()
        ProotRuntime.hostTmpDir(context).mkdirs()
        runCatching { Os.chmod(ProotRuntime.hostTmpDir(context).absolutePath, 511) }
        appendLog(
            context.getString(
                R.string.proot_log_library_path,
                ProotRuntime.prootBinary(context).absolutePath,
            ),
        )
    }

    private fun repairRootfsPermissions(context: Context) {
        val rootfs = ProotRuntime.rootfsDir(context)
        val directories = listOf(
            rootfs,
            File(rootfs, "usr"),
            File(rootfs, "usr/bin"),
            File(rootfs, "usr/sbin"),
            File(rootfs, "usr/lib"),
            File(rootfs, "usr/lib/aarch64-linux-gnu"),
        )
        directories.filter { it.exists() }.forEach { directory ->
            runCatching { Os.chmod(directory.absolutePath, 493) }
        }

        listOf("usr/bin", "usr/sbin").forEach { relativeDirectory ->
            File(rootfs, relativeDirectory).walkTopDown()
                .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }
                .forEach { executable ->
                    runCatching { Os.chmod(executable.absolutePath, 493) }
                }
        }
        listOf(
            "usr/bin/env",
            "usr/bin/dash",
            "usr/bin/bash",
            "usr/bin/apt-get",
            "usr/bin/curl",
            "usr/bin/tar",
            "usr/bin/sha256sum",
            "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1",
        ).map { File(rootfs, it) }
            .filter { it.exists() }
            .forEach { executable -> runCatching { Os.chmod(executable.absolutePath, 493) } }
        appendLog(context.getString(R.string.proot_log_permissions_repaired))
    }

    private fun downloadUbuntuBase(context: Context): File {
        val cacheDir = File(context.cacheDir, "proot").apply { mkdirs() }
        val archive = File(cacheDir, UBUNTU_BASE_FILE)
        if (archive.isFile && sha256(archive) == UBUNTU_BASE_SHA256) {
            update(
                ProotInstallState.Status.DOWNLOADING,
                42,
                context.getString(R.string.proot_install_downloaded),
            )
            appendLog(context.getString(R.string.proot_log_archive_reused))
            return archive
        }
        archive.delete()

        update(
            ProotInstallState.Status.DOWNLOADING,
            5,
            context.getString(R.string.proot_install_downloading),
        )
        appendLog(context.getString(R.string.proot_log_downloading, UBUNTU_BASE_URL))
        val partial = File(cacheDir, "$UBUNTU_BASE_FILE.part").apply { delete() }
        val connection = URL(UBUNTU_BASE_URL).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "Love2Droid")
            connection.connect()
            check(connection.responseCode in 200..299) {
                "Ubuntu Base download failed: HTTP ${connection.responseCode}"
            }
            val total = connection.contentLengthLong
            var downloaded = 0L
            var lastUpdate = 0L
            BufferedInputStream(connection.inputStream).use { input ->
                BufferedOutputStream(FileOutputStream(partial)).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        if (downloaded - lastUpdate >= 512 * 1024) {
                            lastUpdate = downloaded
                            if (total > 0) {
                                val progress = 5 + ((downloaded * 37L / total).toInt())
                                update(
                                    ProotInstallState.Status.DOWNLOADING,
                                    progress.coerceIn(5, 42),
                                    context.getString(R.string.proot_install_downloading),
                                )
                            }
                        }
                    }
                }
            }
            check(total <= 0 || partial.length() == total) {
                "Ubuntu Base download is incomplete (${partial.length()} / $total bytes)"
            }
            check(sha256(partial) == UBUNTU_BASE_SHA256) {
                "Ubuntu Base SHA-256 verification failed"
            }
            if (!partial.renameTo(archive)) {
                partial.copyTo(archive, overwrite = true)
                partial.delete()
            }
            appendLog(context.getString(R.string.proot_log_download_complete, archive.length()))
            return archive
        } catch (error: Throwable) {
            partial.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun extractUbuntuBase(context: Context, archive: File) {
        update(
            ProotInstallState.Status.EXTRACTING,
            44,
            context.getString(R.string.proot_install_extracting),
        )
        val rootfs = ProotRuntime.rootfsDir(context)
        rootfs.deleteRecursively()
        check(rootfs.mkdirs()) { "Unable to create rootfs directory" }
        val rootPath = rootfs.canonicalPath
        val total = archive.length().coerceAtLeast(1L)
        var lastReported = 0L

        try {
            FileInputStream(archive).buffered().use { fileInput ->
                val countingInput = CountingInputStream(fileInput) { count ->
                    if (count - lastReported >= 512 * 1024 || count == total) {
                        lastReported = count
                        val progress = 44 + ((count.coerceAtMost(total) * 25L / total).toInt())
                        update(
                            ProotInstallState.Status.EXTRACTING,
                            progress.coerceIn(44, 69),
                            context.getString(R.string.proot_install_extracting),
                        )
                    }
                }
                GzipCompressorInputStream(countingInput).use { gzipInput ->
                    TarArchiveInputStream(gzipInput).use { tarInput ->
                        while (true) {
                            val entry = tarInput.nextEntry ?: break
                            extractEntry(tarInput, entry, rootfs, rootPath)
                        }
                    }
                }
            }
            appendLog(context.getString(R.string.proot_log_extract_complete))
        } catch (error: Throwable) {
            rootfs.deleteRecursively()
            File(ProotRuntime.runtimeDir(context), ROOTFS_MARKER).delete()
            throw error
        }
    }

    private fun extractEntry(
        tarInput: TarArchiveInputStream,
        entry: TarArchiveEntry,
        rootfs: File,
        rootPath: String,
    ) {
        val rawName = entry.name.replace('\\', '/')
        check(!rawName.startsWith('/')) { "Absolute archive path is not allowed: ${entry.name}" }
        val name = rawName.removePrefix("./").trimEnd('/')
        if (name.isBlank()) return
        check(name.split('/').none { it == ".." }) { "Invalid archive path: ${entry.name}" }
        val output = File(rootfs, name)
        val outputPath = output.canonicalPath
        check(outputPath == rootPath || outputPath.startsWith("$rootPath${File.separator}")) {
            "Archive path escapes rootfs: ${entry.name}"
        }

        when {
            entry.isDirectory -> output.mkdirs()
            entry.isSymbolicLink -> {
                output.parentFile?.mkdirs()
                output.delete()
                Os.symlink(entry.linkName, output.absolutePath)
            }
            entry.isLink -> {
                output.parentFile?.mkdirs()
                output.delete()
                val target = entry.linkName.removePrefix("./")
                val linkTarget = if (target.startsWith('/')) target else File(rootfs, target).relativeTo(output.parentFile ?: rootfs).path
                Os.symlink(linkTarget, output.absolutePath)
            }
            entry.isFile -> {
                output.parentFile?.mkdirs()
                BufferedOutputStream(FileOutputStream(output)).use { fileOutput ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = tarInput.read(buffer)
                        if (count < 0) break
                        fileOutput.write(buffer, 0, count)
                    }
                }
            }
            else -> return
        }

        if (!entry.isSymbolicLink && !entry.isLink) {
            var mode = entry.mode and 511
            mode = mode or 384
            if (entry.isDirectory) mode = mode or 64
            runCatching { Os.chmod(output.absolutePath, mode) }
        }
    }

    private fun configureRootfs(context: Context) {
        update(
            ProotInstallState.Status.CONFIGURING,
            72,
            context.getString(R.string.proot_install_configuring),
        )
        val rootfs = ProotRuntime.rootfsDir(context)
        replaceTextFile(
            File(rootfs, "etc/resolv.conf"),
            "nameserver 1.1.1.1\nnameserver 8.8.8.8\n",
        )
        replaceTextFile(File(rootfs, "etc/hosts"), "127.0.0.1 localhost\n::1 localhost\n")
        replaceTextFile(
            File(rootfs, "etc/apt/apt.conf.d/99proot-nosandbox"),
            "APT::Sandbox::User \"root\";\n",
        )
        replaceTextFile(
            File(rootfs, "etc/dpkg/dpkg.cfg.d/force-unsafe-io"),
            "force-unsafe-io\n",
        )
        replaceTextFile(File(rootfs, "proc/sys/crypto/fips_enabled"), "0\n")
        listOf("tmp", "var/tmp", "dev/shm", "root/.cache", "root/.local/share").forEach { path ->
            File(rootfs, path).mkdirs()
        }
        runCatching { Os.chmod(File(rootfs, "tmp").absolutePath, 511) }
        runCatching { Os.chmod(File(rootfs, "var/tmp").absolutePath, 511) }
        appendLog(context.getString(R.string.proot_log_configured))
    }

    private fun installLuaLanguageServer(context: Context) {
        update(
            ProotInstallState.Status.INSTALLING,
            76,
            context.getString(R.string.proot_install_packages),
        )
        val script = """
            set -eu
            export DEBIAN_FRONTEND=noninteractive
            apt-get update
            apt-get install -y --no-install-recommends ca-certificates curl tar
            rm -rf /opt/lua-language-server
            mkdir -p /opt/lua-language-server
            curl --fail --location --retry 3 '$LUA_LS_URL' -o /tmp/$LUA_LS_FILE
            echo '$LUA_LS_SHA256  /tmp/$LUA_LS_FILE' | sha256sum -c -
            tar -xzf /tmp/$LUA_LS_FILE -C /opt/lua-language-server
            rm -f /tmp/$LUA_LS_FILE
            chmod 0755 /opt/lua-language-server/bin/lua-language-server
            test -x /opt/lua-language-server/bin/lua-language-server
        """.trimIndent()
        runProotCommand(context, script)
        check(ProotRuntime.luaLanguageServer(context).isFile) {
            "Lua language server executable was not installed"
        }
        update(
            ProotInstallState.Status.INSTALLING,
            97,
            context.getString(R.string.proot_install_verifying),
        )
    }

    private fun runProotCommand(context: Context, script: String) {
        val spec = ProotRuntime.shellLaunch(context, script)
        val process = ProcessBuilder(spec.command)
            .directory(spec.workingDirectory)
            .redirectErrorStream(true)
            .apply { environment().putAll(spec.environment) }
            .start()
        try {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.isNotBlank()) appendLog(line)
                }
            }
            val exitCode = process.waitFor()
            check(exitCode == 0) { "Proot command failed with exit code $exitCode" }
        } finally {
            if (process.isAlive) process.destroy()
        }
    }

    private fun writeReadyMarker(context: Context) {
        ProotRuntime.readyMarker(context).writeText(
            "ubuntu=24.04.4\nlua-language-server=$LUA_LS_VERSION\nabi=${ProotRuntime.SUPPORTED_ABI}\n",
        )
    }

    private fun replaceTextFile(file: File, content: String) {
        file.parentFile?.mkdirs()
        file.delete()
        file.writeText(content)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun update(status: ProotInstallState.Status, progress: Int, message: String) {
        _state.value = _state.value.copy(
            status = status,
            progress = progress.coerceIn(0, 100),
            message = message,
        )
    }

    private fun appendLog(line: String) {
        val normalized = line.replace('\r', ' ').take(600)
        _state.value = _state.value.copy(
            logs = (_state.value.logs + normalized).takeLast(MAX_LOG_LINES),
        )
    }

    private class CountingInputStream(
        input: InputStream,
        private val onCount: (Long) -> Unit,
    ) : FilterInputStream(input) {
        private var count = 0L

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) report(1)
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = super.read(buffer, offset, length)
            if (read > 0) report(read)
            return read
        }

        private fun report(bytes: Int) {
            count += bytes
            onCount(count)
        }
    }
}
