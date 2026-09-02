package top.wsdx233.love2droid

internal enum class GitDiffLineKind {
    METADATA,
    HUNK,
    CONTEXT,
    ADDITION,
    REMOVAL,
}

internal data class GitDiffLine(
    val text: String,
    val kind: GitDiffLineKind,
    val oldLineNumber: Int? = null,
    val newLineNumber: Int? = null,
)

internal object GitDiffParser {
    private val hunkHeader = Regex("@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*")

    fun parse(value: String): List<GitDiffLine> {
        val rawLines = value.split('\n').let { lines ->
            if (lines.lastOrNull().isNullOrEmpty()) lines.dropLast(1) else lines
        }
        var oldLine = 0
        var newLine = 0
        return rawLines.map { line ->
            val hunk = hunkHeader.matchEntire(line)
            if (hunk != null) {
                oldLine = hunk.groupValues[1].toInt()
                newLine = hunk.groupValues[3].toInt()
                GitDiffLine(line, GitDiffLineKind.HUNK)
            } else if (line.startsWith("+++") || line.startsWith("---")) {
                GitDiffLine(line, GitDiffLineKind.METADATA)
            } else {
                when {
                    line.startsWith("+") -> GitDiffLine(
                        text = line,
                        kind = GitDiffLineKind.ADDITION,
                        newLineNumber = newLine++,
                    )
                    line.startsWith("-") -> GitDiffLine(
                        text = line,
                        kind = GitDiffLineKind.REMOVAL,
                        oldLineNumber = oldLine++,
                    )
                    line.startsWith(" ") -> GitDiffLine(
                        text = line,
                        kind = GitDiffLineKind.CONTEXT,
                        oldLineNumber = oldLine++,
                        newLineNumber = newLine++,
                    )
                    else -> GitDiffLine(line, GitDiffLineKind.METADATA)
                }
            }
        }
    }
}
