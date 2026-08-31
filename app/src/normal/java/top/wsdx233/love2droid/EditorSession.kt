package top.wsdx233.love2droid

import java.io.File

class EditorTab(
    var file: File?,
    var text: String,
    var languageScope: String?,
) {
    var dirty: Boolean = false
    var selectionStart: Int = 0
    var selectionEnd: Int = 0
    var scrollX: Int = 0
    var scrollY: Int = 0
}

class EditorSession {
    val tabs: MutableList<EditorTab> = mutableListOf()
    var activeIndex: Int = -1
        private set

    val activeTab: EditorTab?
        get() = tabs.getOrNull(activeIndex)

    fun find(file: File): EditorTab? {
        val canonical = file.canonicalFile
        return tabs.firstOrNull { it.file?.canonicalFile == canonical }
    }

    fun add(tab: EditorTab): Int {
        tabs += tab
        activeIndex = tabs.lastIndex
        return activeIndex
    }

    fun select(index: Int) {
        require(index in tabs.indices)
        activeIndex = index
    }

    fun remove(index: Int) {
        if (index !in tabs.indices) return
        tabs.removeAt(index)
        activeIndex = when {
            tabs.isEmpty() -> -1
            activeIndex > index -> activeIndex - 1
            activeIndex >= tabs.size -> tabs.lastIndex
            else -> activeIndex
        }
    }

    fun clear() {
        tabs.clear()
        activeIndex = -1
    }
}
