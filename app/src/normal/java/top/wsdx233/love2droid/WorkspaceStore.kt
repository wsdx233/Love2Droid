package top.wsdx233.love2droid

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal enum class WorkspaceTabType {
    EDITOR,
    TERMINAL,
}

internal data class WorkspaceTabSnapshot(
    val type: WorkspaceTabType,
    val path: String? = null,
    val text: String? = null,
    val dirty: Boolean = false,
    val selectionStart: Int = 0,
    val selectionEnd: Int = 0,
    val scrollX: Int = 0,
    val scrollY: Int = 0,
    val title: String? = null,
    val workingDirectory: String? = null,
    val ompSessionId: String? = null,
    val isOmp: Boolean = false,
    val lineEnding: String? = null,
    val diskLength: Long? = null,
    val diskLastModified: Long? = null,
)

internal data class WorkspaceSnapshot(
    val version: Int = WorkspaceStore.VERSION,
    val directory: String = "",
    val activeTab: Int = -1,
    val terminalCounter: Int = 0,
    val tabs: List<WorkspaceTabSnapshot> = emptyList(),
)

internal object WorkspaceStore {
    const val VERSION = 1
    private const val MAX_FILE_BYTES = 16L * 1024 * 1024
    private const val MAX_TABS = 128

    fun file(root: File): File = File(root, StorageUtils.WORKSPACE_FILE)

    fun read(root: File): WorkspaceSnapshot? {
        val source = file(root)
        if (!source.isFile || source.length() > MAX_FILE_BYTES) return null
        return runCatching {
            val json = JSONObject(source.readText(Charsets.UTF_8))
            if (json.optInt("version", VERSION) != VERSION) return@runCatching null
            val tabsJson = json.optJSONArray("tabs") ?: JSONArray()
            val tabs = buildList {
                for (index in 0 until tabsJson.length().coerceAtMost(MAX_TABS)) {
                    val tab = tabsJson.optJSONObject(index) ?: continue
                    when (tab.optString("type")) {
                        "editor" -> add(
                            WorkspaceTabSnapshot(
                                type = WorkspaceTabType.EDITOR,
                                path = tab.optionalString("path"),
                                text = tab.optionalString("text"),
                                dirty = tab.optBoolean("dirty", false),
                                selectionStart = tab.optInt("selectionStart", 0).coerceAtLeast(0),
                                selectionEnd = tab.optInt("selectionEnd", 0).coerceAtLeast(0),
                                scrollX = tab.optInt("scrollX", 0).coerceAtLeast(0),
                                scrollY = tab.optInt("scrollY", 0).coerceAtLeast(0),
                                lineEnding = tab.optionalString("lineEnding"),
                                diskLength = tab.optionalLong("diskLength"),
                                diskLastModified = tab.optionalLong("diskLastModified"),
                            ),
                        )
                        "terminal" -> add(
                            WorkspaceTabSnapshot(
                                type = WorkspaceTabType.TERMINAL,
                                title = tab.optionalString("title"),
                                workingDirectory = tab.optionalString("workingDirectory"),
                                ompSessionId = tab.optionalString("ompSessionId"),
                                isOmp = tab.optBoolean("isOmp", false),
                            ),
                        )
                    }
                }
            }
            WorkspaceSnapshot(
                version = VERSION,
                directory = json.optString("directory", ""),
                activeTab = json.optInt("activeTab", -1),
                terminalCounter = json.optInt("terminalCounter", 0).coerceAtLeast(0),
                tabs = tabs,
            )
        }.getOrNull()
    }

    fun write(root: File, snapshot: WorkspaceSnapshot) {
        val tabs = JSONArray()
        snapshot.tabs.forEach { state ->
            val tab = JSONObject().put(
                "type",
                when (state.type) {
                    WorkspaceTabType.EDITOR -> "editor"
                    WorkspaceTabType.TERMINAL -> "terminal"
                },
            )
            when (state.type) {
                WorkspaceTabType.EDITOR -> {
                    state.path?.let { tab.put("path", it) }
                    state.text?.let { tab.put("text", it) }
                    tab.put("dirty", state.dirty)
                    tab.put("selectionStart", state.selectionStart.coerceAtLeast(0))
                    tab.put("selectionEnd", state.selectionEnd.coerceAtLeast(0))
                    tab.put("scrollX", state.scrollX.coerceAtLeast(0))
                    tab.put("scrollY", state.scrollY.coerceAtLeast(0))
                    state.lineEnding?.let { tab.put("lineEnding", it) }
                    state.diskLength?.let { tab.put("diskLength", it) }
                    state.diskLastModified?.let { tab.put("diskLastModified", it) }
                }
                WorkspaceTabType.TERMINAL -> {
                    state.title?.let { tab.put("title", it) }
                    state.workingDirectory?.let { tab.put("workingDirectory", it) }
                    state.ompSessionId?.let { tab.put("ompSessionId", it) }
                    tab.put("isOmp", state.isOmp)
                }
            }
            tabs.put(tab)
        }
        val json = JSONObject()
            .put("version", VERSION)
            .put("directory", snapshot.directory)
            .put("activeTab", snapshot.activeTab)
            .put("terminalCounter", snapshot.terminalCounter)
            .put("tabs", tabs)
        StorageUtils.writeTextAtomic(file(root), json.toString(2))
    }

    private fun JSONObject.optionalString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key)
    }
    private fun JSONObject.optionalLong(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        return optLong(key)
    }
}
