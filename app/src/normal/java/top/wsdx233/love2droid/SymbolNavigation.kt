package top.wsdx233.love2droid

import java.io.File
import java.net.URI

internal data class LuaSymbolLocation(
    val uri: String,
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int,
)

internal data class EditorNavigationTarget(
    val file: File,
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int,
)

internal data class SymbolNavigationItem(
    val target: EditorNavigationTarget,
    val relativePath: String,
    val preview: String,
)

internal fun symbolAt(text: CharSequence, index: Int): String? {
    if (text.isEmpty()) return null
    val candidate = when {
        index in text.indices && text[index].isSymbolPart() -> index
        index == text.length && index > 0 && text[index - 1].isSymbolPart() -> index - 1
        else -> return null
    }
    var start = candidate
    while (start > 0 && text[start - 1].isSymbolPart()) start--
    var end = candidate + 1
    while (end < text.length && text[end].isSymbolPart()) end++
    return text.subSequence(start, end).toString().takeIf { it.any(Char::isLetter) || '_' in it }
}

internal fun selectedSymbol(text: CharSequence, start: Int, end: Int): String? {
    if (start < 0 || end <= start || end > text.length) return null
    if (start > 0 && text[start - 1].isSymbolPart()) return null
    if (end < text.length && text[end].isSymbolPart()) return null
    val value = text.subSequence(start, end).toString()
    return value.takeIf { SYMBOL_IDENTIFIER.matches(it) }
}

internal fun resolveProjectNavigationTarget(
    projectRoot: File,
    location: LuaSymbolLocation,
): EditorNavigationTarget? {
    val file = fileFromUri(location.uri) ?: return null
    if (!StorageUtils.isWithin(projectRoot, file)) return null
    return EditorNavigationTarget(
        file = file,
        startLine = location.startLine.coerceAtLeast(0),
        startColumn = location.startColumn.coerceAtLeast(0),
        endLine = location.endLine.coerceAtLeast(location.startLine.coerceAtLeast(0)),
        endColumn = location.endColumn.coerceAtLeast(0),
    )
}

internal fun resolveProjectFileLink(projectRoot: File, link: String): EditorNavigationTarget? {
    val uri = runCatching { URI(link) }.getOrNull() ?: return null
    val file = fileFromUri(uri) ?: return null
    if (!StorageUtils.isWithin(projectRoot, file)) return null
    val (line, column) = parseLinkFragment(uri.fragment)
    return EditorNavigationTarget(file, line, column, line, column)
}

internal fun buildSymbolNavigationItems(
    projectRoot: File,
    targets: List<EditorNavigationTarget>,
): List<SymbolNavigationItem> {
    val distinct = targets.distinctBy {
        listOf(it.file.canonicalPath, it.startLine, it.startColumn, it.endLine, it.endColumn)
    }.sortedWith(
        compareBy<EditorNavigationTarget>({ StorageUtils.relativePath(projectRoot, it.file) }, { it.startLine }, { it.startColumn }),
    )
    val previews = HashMap<Pair<String, Int>, String>()
    distinct.groupBy { it.file.canonicalFile }.forEach { (file, fileTargets) ->
        val requestedLines = fileTargets.mapTo(sortedSetOf()) { it.startLine }
        if (requestedLines.isEmpty() || !file.isFile) return@forEach
        runCatching {
            file.bufferedReader(Charsets.UTF_8).use { reader ->
                var lineNumber = 0
                var remaining = requestedLines.size
                while (remaining > 0) {
                    val line = reader.readLine() ?: break
                    if (lineNumber in requestedLines) {
                        previews[file.path to lineNumber] = line.trim().take(MAX_PREVIEW_CHARS)
                        remaining--
                    }
                    lineNumber++
                }
            }
        }
    }
    return distinct.map { target ->
        SymbolNavigationItem(
            target = target,
            relativePath = StorageUtils.relativePath(projectRoot, target.file),
            preview = previews[target.file.canonicalPath to target.startLine].orEmpty(),
        )
    }
}

private fun Char.isSymbolPart(): Boolean = isLetterOrDigit() || this == '_'

private fun fileFromUri(value: String): File? = runCatching { URI(value) }.getOrNull()?.let(::fileFromUri)

private fun fileFromUri(uri: URI): File? {
    if (!uri.scheme.equals("file", ignoreCase = true)) return null
    val cleanUri = runCatching { URI("file", uri.authority, uri.path, null, null) }.getOrNull() ?: return null
    return runCatching { File(cleanUri).canonicalFile }.getOrNull()
}

private fun parseLinkFragment(fragment: String?): Pair<Int, Int> {
    if (fragment.isNullOrBlank()) return 0 to 0
    val match = LINK_FRAGMENT.matchEntire(fragment) ?: return 0 to 0
    val line = match.groupValues[1].toIntOrNull()?.minus(1)?.coerceAtLeast(0) ?: 0
    val column = match.groupValues[2].toIntOrNull()?.minus(1)?.coerceAtLeast(0) ?: 0
    return line to column
}

private val LINK_FRAGMENT = Regex("""L?(\d+)(?:(?:C|[,:]C?)(\d+))?""", RegexOption.IGNORE_CASE)
private const val MAX_PREVIEW_CHARS = 180
private val SYMBOL_IDENTIFIER = Regex("[\\p{L}_][\\p{L}\\p{N}_]*")
