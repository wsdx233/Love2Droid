package top.wsdx233.love2droid

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets

internal data class GitStatusEntry(
    val indexStatus: Char,
    val workTreeStatus: Char,
    val relativePath: String,
    val originalPath: String? = null,
)
internal val GitStatusEntry.isUntracked: Boolean
    get() = indexStatus == '?' && workTreeStatus == '?'

internal val GitStatusEntry.isStaged: Boolean
    get() = indexStatus != ' ' && indexStatus != '?'

internal val GitStatusEntry.hasWorkTreeChanges: Boolean
    get() = workTreeStatus != ' ' || isUntracked


internal data class GitCommitEntry(
    val graph: String,
    val hash: String,
    val parents: List<String>,
    val author: String,
    val timestampSeconds: Long,
    val subject: String,
)

internal class GitCommandException(
    val exitCode: Int,
    message: String,
) : Exception(message)

internal object GitOutputParser {
    fun parseStatus(output: ByteArray): List<GitStatusEntry> {
        val fields = output.toString(StandardCharsets.UTF_8).split('\u0000')
        val results = ArrayList<GitStatusEntry>()
        var index = 0
        while (index < fields.size) {
            val field = fields[index++]
            if (field.isEmpty()) continue
            require(field.length >= 4 && field[2] == ' ') { "Invalid git status record" }
            val indexStatus = field[0]
            val workTreeStatus = field[1]
            val path = field.substring(3)
            val original = if (indexStatus == 'R' || indexStatus == 'C' || workTreeStatus == 'R' || workTreeStatus == 'C') {
                fields.getOrNull(index++)?.takeIf(String::isNotEmpty)
            } else {
                null
            }
            results += GitStatusEntry(indexStatus, workTreeStatus, path, original)
        }
        return results.sortedWith(compareBy(GitStatusEntry::relativePath, GitStatusEntry::indexStatus, GitStatusEntry::workTreeStatus))
    }

    fun parseHistory(output: String): List<GitCommitEntry> = output.lineSequence().mapNotNull { line ->
        val marker = line.indexOf(RECORD_SEPARATOR)
        if (marker < 0) return@mapNotNull null
        val fields = line.substring(marker + 1).split(FIELD_SEPARATOR, limit = 5)
        if (fields.size != 5) return@mapNotNull null
        val timestamp = fields[3].toLongOrNull() ?: return@mapNotNull null
        GitCommitEntry(
            graph = line.substring(0, marker).trimEnd(),
            hash = fields[0],
            parents = fields[1].split(' ').filter(String::isNotBlank),
            author = fields[2],
            timestampSeconds = timestamp,
            subject = fields[4],
        )
    }.toList()

    private const val RECORD_SEPARATOR = '\u001e'
    private const val FIELD_SEPARATOR = '\u001f'
}

internal class GitClient(context: Context) {
    private val appContext = context.applicationContext

    fun isInstalled(): Boolean = ProotRuntime.gitBinary(appContext).isFile

    suspend fun ensureInstalled(onProgress: (String) -> Unit) {
        if (isInstalled()) return
        onProgress(appContext.getString(R.string.git_install_updating))
        execute(
            projectRoot = ProotRuntime.rootfsDir(appContext),
            command = listOf("/usr/bin/apt-get", "update"),
            acceptedExitCodes = setOf(0),
            outputLimit = INSTALL_OUTPUT_LIMIT,
        )
        onProgress(appContext.getString(R.string.git_install_installing))
        execute(
            projectRoot = ProotRuntime.rootfsDir(appContext),
            command = listOf(
                "/usr/bin/env",
                "DEBIAN_FRONTEND=noninteractive",
                "/usr/bin/apt-get",
                "install",
                "-y",
                "--no-install-recommends",
                "git",
                "ca-certificates",
            ),
            acceptedExitCodes = setOf(0),
            outputLimit = INSTALL_OUTPUT_LIMIT,
        )
        check(isInstalled()) { appContext.getString(R.string.git_install_missing_after_install) }
    }

    suspend fun isRepository(projectRoot: File): Boolean = runCatching {
        executeGit(projectRoot, listOf("rev-parse", "--is-inside-work-tree")).stdout.trim() == "true"
    }.getOrDefault(false)

    suspend fun initialize(projectRoot: File) {
        executeGit(projectRoot, listOf("init"))
    }

    suspend fun status(projectRoot: File): List<GitStatusEntry> {
        val result = executeGit(
            projectRoot,
            listOf("status", "--porcelain=v1", "-z", "--untracked-files=all"),
        )
        return GitOutputParser.parseStatus(result.stdoutBytes)
    }

    suspend fun history(projectRoot: File): List<GitCommitEntry> {
        val result = executeGit(
            projectRoot,
            listOf(
                "log",
                "--all",
                "--topo-order",
                "--date-order",
                "--max-count=$MAX_HISTORY_COMMITS",
                "--graph",
                "--pretty=format:%x1e%H%x1f%P%x1f%an%x1f%at%x1f%s",
            ),
        )
        return GitOutputParser.parseHistory(result.stdout)
    }

    suspend fun stage(projectRoot: File, relativePath: String) {
        executeGit(projectRoot, listOf("add", "--", checkedPath(projectRoot, relativePath)))
    }

    suspend fun stageAll(projectRoot: File) {
        executeGit(projectRoot, listOf("add", "--all"))
    }

    suspend fun unstage(projectRoot: File, relativePath: String) {
        val path = checkedPath(projectRoot, relativePath)
        if (hasHead(projectRoot)) {
            executeGit(projectRoot, listOf("restore", "--staged", "--", path))
        } else {
            executeGit(
                projectRoot,
                listOf("rm", "--cached", "--ignore-unmatch", "--", path),
            )
        }
    }

    suspend fun unstageAll(projectRoot: File) {
        if (hasHead(projectRoot)) {
            executeGit(projectRoot, listOf("restore", "--staged", "--", "."))
        } else {
            executeGit(
                projectRoot,
                listOf("rm", "--cached", "--ignore-unmatch", "-r", "--", "."),
            )
        }
    }

    suspend fun commit(projectRoot: File, message: String) {
        require(message.isNotBlank()) { appContext.getString(R.string.git_commit_message_required) }
        executeGit(projectRoot, listOf("commit", "-m", message.trim()))
    }

    suspend fun diff(projectRoot: File, relativePath: String? = null, staged: Boolean = false): String {
        if (relativePath != null) {
            val path = checkedPath(projectRoot, relativePath)
            val entry = status(projectRoot).firstOrNull { it.relativePath == path }
            if (!staged && entry?.isUntracked == true) return untrackedDiff(projectRoot, path)
            return executeDiff(projectRoot, path, staged)
        }
        val trackedDiff = executeDiff(projectRoot, null, staged)
        if (staged) return trackedDiff
        val untrackedDiffs = status(projectRoot)
            .filter(GitStatusEntry::isUntracked)
            .map { untrackedDiff(projectRoot, checkedPath(projectRoot, it.relativePath)) }
        return (listOf(trackedDiff) + untrackedDiffs).filter(String::isNotBlank).joinToString("\n")
    }

    suspend fun commitDetails(projectRoot: File, hash: String): String {
        require(COMMIT_HASH.matches(hash)) { "Invalid commit hash" }
        return executeGit(
            projectRoot,
            listOf(
                "show",
                "--no-ext-diff",
                "--no-color",
                "--format=fuller",
                "--stat",
                "--patch",
                "--max-count=1",
                hash,
            ),
            outputLimit = DIFF_OUTPUT_LIMIT,
        ).stdout
    }

    private suspend fun executeDiff(projectRoot: File, path: String?, staged: Boolean): String {
        val command = mutableListOf("diff", "--no-ext-diff", "--no-color", "--unified=3", "--patch")
        if (staged) command += "--cached"
        if (path != null) command += listOf("--", path)
        return executeGit(projectRoot, command, outputLimit = DIFF_OUTPUT_LIMIT).stdout
    }

    private suspend fun untrackedDiff(projectRoot: File, path: String): String = executeGit(
        projectRoot,
        listOf(
            "diff",
            "--no-index",
            "--no-ext-diff",
            "--no-color",
            "--unified=3",
            "--patch",
            "--",
            "/dev/null",
            path,
        ),
        acceptedExitCodes = setOf(0, 1),
        outputLimit = DIFF_OUTPUT_LIMIT,
    ).stdout

    private suspend fun hasHead(projectRoot: File): Boolean = runCatching {
        executeGit(projectRoot, listOf("rev-parse", "--verify", "--quiet", "HEAD"))
        true
    }.getOrDefault(false)

    private fun checkedPath(projectRoot: File, relativePath: String): String {
        val file = StorageUtils.resolveChild(projectRoot, relativePath)
        return StorageUtils.relativePath(projectRoot, file)
    }

    private suspend fun executeGit(
        projectRoot: File,
        arguments: List<String>,
        acceptedExitCodes: Set<Int> = setOf(0),
        outputLimit: Int = COMMAND_OUTPUT_LIMIT,
    ): CommandResult = execute(
        projectRoot = projectRoot,
        command = listOf("/usr/bin/git", "--no-pager", "-c", "color.ui=false", "-c", "core.quotepath=false") + arguments,
        acceptedExitCodes = acceptedExitCodes,
        outputLimit = outputLimit,
    )

    private suspend fun execute(
        projectRoot: File,
        command: List<String>,
        acceptedExitCodes: Set<Int>,
        outputLimit: Int,
    ): CommandResult = withContext(Dispatchers.IO) {
        val spec = ProotRuntime.projectCommandLaunch(appContext, projectRoot, command)
        val process = ProcessBuilder(spec.command)
            .directory(spec.workingDirectory)
            .apply { environment().putAll(spec.environment) }
            .start()
        try {
            process.outputStream.close()
            coroutineScope {
                val stdout = async { process.inputStream.readLimited(outputLimit) }
                val stderr = async { process.errorStream.readLimited(outputLimit) }
                val exitCode = process.waitFor()
                val stdoutValue = stdout.await()
                val stderrValue = stderr.await()
                if (stdoutValue.truncated || stderrValue.truncated) {
                    throw GitCommandException(exitCode, appContext.getString(R.string.git_output_too_large))
                }
                if (exitCode !in acceptedExitCodes) {
                    val detail = stderrValue.bytes.toString(StandardCharsets.UTF_8).trim()
                        .ifBlank { stdoutValue.bytes.toString(StandardCharsets.UTF_8).trim() }
                        .take(MAX_ERROR_CHARS)
                    throw GitCommandException(
                        exitCode,
                        appContext.getString(R.string.git_command_failed, exitCode, detail),
                    )
                }
                CommandResult(stdoutValue.bytes, stderrValue.bytes)
            }
        } finally {
            if (process.isAlive) process.destroy()
        }
    }

    private fun InputStream.readLimited(limit: Int): LimitedBytes {
        val output = ByteArrayOutputStream(minOf(limit, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        var truncated = false
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            val remaining = limit - output.size()
            if (remaining > 0) output.write(buffer, 0, minOf(remaining, count))
            if (count > remaining) truncated = true
        }
        return LimitedBytes(output.toByteArray(), truncated)
    }

    private data class LimitedBytes(val bytes: ByteArray, val truncated: Boolean)

    private data class CommandResult(
        val stdoutBytes: ByteArray,
        val stderrBytes: ByteArray,
    ) {
        val stdout: String
            get() = stdoutBytes.toString(StandardCharsets.UTF_8)
    }

    private companion object {
        val COMMIT_HASH = Regex("[0-9a-fA-F]{7,64}")
        const val MAX_HISTORY_COMMITS = 200
        const val COMMAND_OUTPUT_LIMIT = 4 * 1024 * 1024
        const val INSTALL_OUTPUT_LIMIT = 2 * 1024 * 1024
        const val DIFF_OUTPUT_LIMIT = 8 * 1024 * 1024
        const val MAX_ERROR_CHARS = 800
    }
}
