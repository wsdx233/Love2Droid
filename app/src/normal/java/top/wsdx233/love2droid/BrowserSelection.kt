package top.wsdx233.love2droid

internal fun inclusiveSelectionRange(
    orderedPaths: List<String>,
    anchorPath: String,
    targetPath: String,
): LinkedHashSet<String>? {
    val anchorIndex = orderedPaths.indexOf(anchorPath)
    val targetIndex = orderedPaths.indexOf(targetPath)
    if (anchorIndex < 0 || targetIndex < 0) return null

    val start = minOf(anchorIndex, targetIndex)
    val end = maxOf(anchorIndex, targetIndex)
    return LinkedHashSet(orderedPaths.subList(start, end + 1))
}

internal fun invertedSelection(
    orderedPaths: List<String>,
    selectedPaths: Set<String>,
): LinkedHashSet<String> = orderedPaths
    .filterTo(linkedSetOf()) { it !in selectedPaths }
