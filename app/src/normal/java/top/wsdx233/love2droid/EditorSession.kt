package top.wsdx233.love2droid

import com.termux.terminal.TerminalSession
import java.io.File

sealed interface WorkspaceTab

class EditorTab(
    var file: File?,
    var text: String,
    var languageScope: String?,
) : WorkspaceTab {
    var dirty: Boolean = false
    var selectionStart: Int = 0
    var selectionEnd: Int = 0
    var scrollX: Int = 0
    var scrollY: Int = 0
    var readOnly: Boolean = false
    internal var lineEnding: EditorLineEnding = EditorLineEnding.LF
    internal var diskSnapshot: EditorFileSnapshot? = null
    internal var lastObservedExternalSnapshot: EditorFileSnapshot? = null
    internal var externalChangeAcknowledged: Boolean = false
}

class TerminalTab(
    val session: TerminalSession,
    var title: String,
    val workingDirectory: String? = null,
    val isOmp: Boolean = false,
    var pendingStartupCommand: String? = null,
) : WorkspaceTab {
}

class EditorSession {
    val tabs: MutableList<WorkspaceTab> = mutableListOf()
    var activeIndex: Int = -1
        private set

    val activeTab: WorkspaceTab?
        get() = tabs.getOrNull(activeIndex)

    val activeEditorTab: EditorTab?
        get() = activeTab as? EditorTab

    fun find(file: File): EditorTab? {
        val canonical = file.canonicalFile
        return tabs.filterIsInstance<EditorTab>()
            .firstOrNull { it.file?.canonicalFile == canonical }
    }

    fun add(tab: WorkspaceTab): Int {
        tabs += tab
        return tabs.lastIndex
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
