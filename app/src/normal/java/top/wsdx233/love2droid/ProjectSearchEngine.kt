package top.wsdx233.love2droid

import java.io.File
import java.io.FileInputStream
import java.util.ArrayDeque
import java.util.regex.Pattern

internal enum class ProjectSearchMode {
    FILES,
    TEXT,
    SYMBOLS,
}

internal enum class ProjectSymbolSource {
    SEMANTIC,
    LOCAL,
}

internal data class ProjectSearchResult(
    val mode: ProjectSearchMode,
    val title: String,
    val relativePath: String,
    val preview: String,
    val target: EditorNavigationTarget,
    val symbolSource: ProjectSymbolSource? = null,
)

internal data class LuaWorkspaceSymbol(
    val name: String,
    val kind: String,
    val location: LuaSymbolLocation,
)

internal object ProjectSearchEngine {
    fun searchFiles(
        projectRoot: File,
        query: String,
        useRegex: Boolean,
        cancelled: () -> Boolean = { false },
    ): List<ProjectSearchResult> {
        val matcher = SearchPattern(query, useRegex)
        return projectFiles(projectRoot, cancelled)
            .filter { matcher.matches(StorageUtils.relativePath(projectRoot, it)) }
            .take(MAX_FILE_RESULTS)
            .map { file ->
                val relativePath = StorageUtils.relativePath(projectRoot, file)
                ProjectSearchResult(
                    mode = ProjectSearchMode.FILES,
                    title = file.name,
                    relativePath = relativePath,
                    preview = relativePath,
                    target = EditorNavigationTarget(file, 0, 0, 0, 0),
                )
            }
            .toList()
    }

    fun searchText(
        projectRoot: File,
        query: String,
        useRegex: Boolean,
        cancelled: () -> Boolean = { false },
    ): List<ProjectSearchResult> {
        val searchPattern = SearchPattern(query, useRegex)
        val results = ArrayList<ProjectSearchResult>()
        projectFiles(projectRoot, cancelled).forEach { file ->
            if (cancelled() || results.size >= MAX_TEXT_RESULTS) return@forEach
            if (file.length() > MAX_TEXT_FILE_BYTES || isBinary(file)) return@forEach
            val relativePath = StorageUtils.relativePath(projectRoot, file)
            runCatching {
                file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEachIndexed { lineIndex, line ->
                        if (cancelled() || results.size >= MAX_TEXT_RESULTS) return@forEachIndexed
                        val matcher = searchPattern.matcher(line)
                        while (matcher.find() && results.size < MAX_TEXT_RESULTS && !cancelled()) {
                            results += ProjectSearchResult(
                                mode = ProjectSearchMode.TEXT,
                                title = file.name,
                                relativePath = relativePath,
                                preview = line.trim().take(MAX_PREVIEW_CHARS),
                                target = EditorNavigationTarget(
                                    file = file,
                                    startLine = lineIndex,
                                    startColumn = matcher.start(),
                                    endLine = lineIndex,
                                    endColumn = matcher.end(),
                                ),
                            )
                        }
                    }
                }
            }
        }
        return results.sortedWith(
            compareBy(ProjectSearchResult::relativePath)
                .thenBy { it.target.startLine }
                .thenBy { it.target.startColumn },
        )
    }

    fun searchLocalSymbols(
        projectRoot: File,
        query: String,
        useRegex: Boolean,
        cancelled: () -> Boolean = { false },
    ): List<ProjectSearchResult> {
        val searchPattern = SearchPattern(query, useRegex)
        val results = ArrayList<ProjectSearchResult>()
        projectFiles(projectRoot, cancelled)
            .filter { it.extension.equals("lua", ignoreCase = true) }
            .forEach { file ->
                if (cancelled() || results.size >= MAX_SYMBOL_RESULTS) return@forEach
                if (file.length() > MAX_TEXT_FILE_BYTES || isBinary(file)) return@forEach
                val relativePath = StorageUtils.relativePath(projectRoot, file)
                runCatching {
                    file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                        lines.forEachIndexed { lineIndex, line ->
                            if (cancelled() || results.size >= MAX_SYMBOL_RESULTS) return@forEachIndexed
                            val declaration = LUA_DECLARATIONS.firstNotNullOfOrNull { pattern ->
                                pattern.find(line)?.groups?.get(1)
                            } ?: return@forEachIndexed
                            val name = declaration.value
                            if (!searchPattern.matches(name)) return@forEachIndexed
                            results += ProjectSearchResult(
                                mode = ProjectSearchMode.SYMBOLS,
                                title = name,
                                relativePath = relativePath,
                                preview = line.trim().take(MAX_PREVIEW_CHARS),
                                target = EditorNavigationTarget(
                                    file = file,
                                    startLine = lineIndex,
                                    startColumn = declaration.range.first,
                                    endLine = lineIndex,
                                    endColumn = declaration.range.last + 1,
                                ),
                                symbolSource = ProjectSymbolSource.LOCAL,
                            )
                        }
                    }
                }
            }
        return results.sortedWith(compareBy(ProjectSearchResult::title, ProjectSearchResult::relativePath))
    }

    fun semanticSymbols(
        projectRoot: File,
        query: String,
        useRegex: Boolean,
        symbols: List<LuaWorkspaceSymbol>,
    ): List<ProjectSearchResult> {
        val searchPattern = SearchPattern(query, useRegex)
        return symbols.asSequence()
            .filter { searchPattern.matches(it.name) }
            .mapNotNull { symbol ->
                val target = resolveProjectNavigationTarget(projectRoot, symbol.location) ?: return@mapNotNull null
                ProjectSearchResult(
                    mode = ProjectSearchMode.SYMBOLS,
                    title = symbol.name,
                    relativePath = StorageUtils.relativePath(projectRoot, target.file),
                    preview = symbol.kind,
                    target = target,
                    symbolSource = ProjectSymbolSource.SEMANTIC,
                )
            }
            .distinctBy { listOf(it.title, it.relativePath, it.target.startLine, it.target.startColumn) }
            .sortedWith(compareBy(ProjectSearchResult::title, ProjectSearchResult::relativePath))
            .take(MAX_SYMBOL_RESULTS)
            .toList()
    }

    private fun projectFiles(root: File, cancelled: () -> Boolean): Sequence<File> = sequence {
        val canonicalRoot = root.canonicalFile
        val directories = ArrayDeque<File>()
        val visited = hashSetOf<String>()
        directories.add(canonicalRoot)
        while (directories.isNotEmpty() && !cancelled()) {
            val directory = directories.removeLast().canonicalFile
            if (!StorageUtils.isWithin(canonicalRoot, directory) || !visited.add(directory.path)) continue
            directory.listFiles()
                ?.sortedBy(File::getName)
                ?.forEach { child ->
                    if (cancelled() || shouldSkip(child)) return@forEach
                    val canonical = runCatching { child.canonicalFile }.getOrNull() ?: return@forEach
                    if (!StorageUtils.isWithin(canonicalRoot, canonical)) return@forEach
                    when {
                        canonical.isDirectory -> directories.add(canonical)
                        canonical.isFile -> yield(canonical)
                    }
                }
        }
    }

    private fun shouldSkip(file: File): Boolean =
        file.name == ".git" || file.name == StorageUtils.METADATA_FILE

    private fun isBinary(file: File): Boolean = runCatching {
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BINARY_SAMPLE_BYTES)
            val count = input.read(buffer)
            (0 until count).any { buffer[it] == 0.toByte() }
        }
    }.getOrDefault(true)

    private class SearchPattern(query: String, useRegex: Boolean) {
        private val pattern = if (useRegex) {
            Pattern.compile(query)
        } else {
            Pattern.compile(Pattern.quote(query), Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
        }

        fun matches(value: String): Boolean = matcher(value).find()

        fun matcher(value: String) = pattern.matcher(value)
    }

    private val LUA_DECLARATIONS = listOf(
        Regex("""\b(?:local\s+)?function\s+([\p{L}_][\p{L}\p{N}_.:]*)"""),
        Regex("""\blocal\s+([\p{L}_][\p{L}\p{N}_]*)"""),
        Regex("""^\s*([\p{L}_][\p{L}\p{N}_]*)\s*="""),
    )
    private const val MAX_FILE_RESULTS = 500
    private const val MAX_TEXT_RESULTS = 1_000
    private const val MAX_SYMBOL_RESULTS = 500
    private const val MAX_TEXT_FILE_BYTES = 2L * 1024L * 1024L
    private const val MAX_PREVIEW_CHARS = 200
    private const val BINARY_SAMPLE_BYTES = 8 * 1024
}
