package top.wsdx233.love2droid

import java.io.File

/** A one-based Lua source breakpoint stored in project metadata. */
data class ProjectBreakpoint(
    val file: String,
    val line: Int,
)

internal enum class BreakpointEditKind { INSERT, DELETE }

internal fun normalizeProjectBreakpoints(
    root: File,
    breakpoints: Collection<ProjectBreakpoint>,
): List<ProjectBreakpoint> = breakpoints.asSequence()
    .mapNotNull { breakpoint ->
        if (breakpoint.line <= 0) return@mapNotNull null
        val relativePath = breakpoint.file.replace('\\', '/').removePrefix("./")
        val file = runCatching { StorageUtils.resolveChild(root, relativePath) }.getOrNull()
            ?: return@mapNotNull null
        if (!file.isFile || !file.extension.equals("lua", ignoreCase = true)) return@mapNotNull null
        ProjectBreakpoint(StorageUtils.relativePath(root, file), breakpoint.line)
    }
    .distinct()
    .sortedWith(compareBy(ProjectBreakpoint::file, ProjectBreakpoint::line))
    .toList()

internal fun Collection<ProjectBreakpoint>.adjustedForEdit(
    file: String,
    kind: BreakpointEditKind,
    startLine: Int,
    endLine: Int,
): List<ProjectBreakpoint> {
    val delta = endLine - startLine
    if (delta <= 0) return distinct().sortedWith(compareBy(ProjectBreakpoint::file, ProjectBreakpoint::line))
    return mapNotNull { breakpoint ->
        if (breakpoint.file != file) return@mapNotNull breakpoint
        val zeroBasedLine = breakpoint.line - 1
        when (kind) {
            BreakpointEditKind.INSERT -> {
                if (zeroBasedLine > startLine) breakpoint.copy(line = breakpoint.line + delta) else breakpoint
            }
            BreakpointEditKind.DELETE -> when {
                zeroBasedLine > endLine -> breakpoint.copy(line = breakpoint.line - delta)
                zeroBasedLine > startLine -> null
                else -> breakpoint
            }
        }
    }.distinct().sortedWith(compareBy(ProjectBreakpoint::file, ProjectBreakpoint::line))
}
