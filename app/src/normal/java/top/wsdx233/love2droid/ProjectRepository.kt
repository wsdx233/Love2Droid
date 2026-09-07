package top.wsdx233.love2droid

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import top.wsdx233.love2droid.runtime.DebugWatchStore
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Locale

internal object ProjectTemplate {
    private val textFiles = listOf("main.lua", "AGENTS.md")
    private val fontFiles = listOf(
        "fusion-pixel-12px-monospaced-zh_hans.otf",
        "OFL.txt",
        "LICENSES/ark-pixel/OFL.txt",
        "LICENSES/cubic-11/OFL.txt",
        "LICENSES/galmuri/LICENSE.txt",
    )

    fun write(root: File, openAsset: (String) -> InputStream) {
        for (name in textFiles) {
            val content = openAsset(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
            StorageUtils.writeTextAtomic(StorageUtils.resolveChild(root, name), content)
        }
        for (name in fontFiles) {
            val path = "assets/fonts/$name"
            val target = StorageUtils.resolveChild(root, path)
            val parent = requireNotNull(target.parentFile)
            if (!parent.isDirectory && !parent.mkdirs()) {
                throw IOException("Unable to create ${parent.path}")
            }
            openAsset(path).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }
}

internal object LuaLanguageServerProjectConfig {
    const val FILE_NAME = ".luarc.json"

    fun content(): String = """
        {
          "runtime.version": "LuaJIT",
          "runtime.special": {
            "love.filesystem.load": "loadfile"
          },
          "workspace.library": [
            "${ProotRuntime.LUA_LSP_LOVE_LIBRARY_GUEST_PATH}"
          ],
          "workspace.checkThirdParty": false
        }
    """.trimIndent() + "\n"
}

internal fun defaultProjectConf(id: String, displayName: String): String = """
    function love.conf(t)
        t.identity = "$id"
        t.window.title = "${displayName.replace(34.toChar(), 39.toChar())}"
        t.window.width = 800
        t.window.height = 480
    end
""".trimIndent() + "\n"

data class Project(
    val id: String,
    val displayName: String,
    val description: String,
    val root: File,
    val lastOpened: Long,
    val androidProperties: AndroidProjectProperties = AndroidProjectProperties.defaults(id, displayName),
    val breakpoints: List<ProjectBreakpoint> = emptyList(),
    val group: String = "",
)

class ProjectRepository(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("project-state", Context.MODE_PRIVATE)
    private val iconStore = ProjectIconStore(appContext)
    val projectsRoot: File = File(
        appContext.getExternalFilesDir(null) ?: appContext.filesDir,
        "projects",
    )

    init {
        if (!projectsRoot.exists() && !projectsRoot.mkdirs()) {
            throw IOException("Unable to create projects directory: ${projectsRoot.path}")
        }
    }

    fun listProjects(): List<Project> = projectsRoot.listFiles()
        ?.asSequence()
        ?.filter { it.isDirectory && !it.name.startsWith(".") }
        ?.mapNotNull { readProject(it) }
        ?.sortedWith(compareByDescending<Project> { it.lastOpened }.thenBy { it.displayName.lowercase(Locale.getDefault()) })
        ?.toList()
        ?: emptyList()

    fun findProject(id: String): Project? {
        if (!isSafeProjectId(id)) return null
        return readProject(File(projectsRoot, id))
    }

    fun lastOpenedProject(): Project? {
        return preferences.getString("last_project_id", null)?.let(::findProject)
    }

    fun markOpened(project: Project): Project {
        val now = System.currentTimeMillis()
        val updated = project.copy(lastOpened = now)
        writeMetadata(updated)
        preferences.edit().putString("last_project_id", project.id).apply()
        return updated
    }

    fun updateAndroidProperties(project: Project, properties: AndroidProjectProperties): Project {
        require(StorageUtils.isWithin(projectsRoot, project.root)) { "Project is outside storage root" }
        val updated = project.copy(androidProperties = properties.validated())
        writeMetadata(updated)
        return updated
    }
    fun updateBreakpoints(project: Project, breakpoints: Collection<ProjectBreakpoint>): Project {
        require(StorageUtils.isWithin(projectsRoot, project.root)) { "Project is outside storage root" }
        val updated = project.copy(breakpoints = normalizeProjectBreakpoints(project.root, breakpoints))
        writeMetadata(updated)
        return updated
    }


    fun updateProjectGroup(project: Project, groupName: String): Project {
        require(StorageUtils.isWithin(projectsRoot, project.root)) { "Project is outside storage root" }
        val updated = project.copy(group = groupName.trim())
        writeMetadata(updated)
        return updated
    }

    fun listGroups(): List<String> {
        val groups = linkedSetOf<String>()
        val saved = preferences.getStringSet("custom_groups", null)
        if (saved != null) {
            groups.addAll(saved.filter { it.isNotBlank() })
        }
        listProjects().forEach { project ->
            if (project.group.isNotBlank()) {
                groups.add(project.group)
            }
        }
        return groups.sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    fun addGroup(groupName: String): Boolean {
        val trimmed = groupName.trim()
        if (trimmed.isBlank()) return false
        val current = preferences.getStringSet("custom_groups", emptySet())?.toMutableSet() ?: mutableSetOf()
        current.add(trimmed)
        preferences.edit().putStringSet("custom_groups", current).apply()
        return true
    }

    fun deleteGroup(groupName: String) {
        val trimmed = groupName.trim()
        if (trimmed.isBlank()) return
        val current = preferences.getStringSet("custom_groups", emptySet())?.toMutableSet() ?: mutableSetOf()
        if (current.remove(trimmed)) {
            preferences.edit().putStringSet("custom_groups", current).apply()
        }
        listProjects().filter { it.group == trimmed }.forEach { project ->
            updateProjectGroup(project, "")
        }
    }

    fun renameGroup(oldName: String, newName: String) {
        val oldTrimmed = oldName.trim()
        val newTrimmed = newName.trim()
        if (oldTrimmed.isBlank() || newTrimmed.isBlank() || oldTrimmed == newTrimmed) return
        val current = preferences.getStringSet("custom_groups", emptySet())?.toMutableSet() ?: mutableSetOf()
        if (current.remove(oldTrimmed)) {
            current.add(newTrimmed)
            preferences.edit().putStringSet("custom_groups", current).apply()
        }
        listProjects().filter { it.group == oldTrimmed }.forEach { project ->
            updateProjectGroup(project, newTrimmed)
        }
    }

    fun projectIcon(project: Project): File? = iconStore.iconFile(project.id)

    fun importProjectIcon(project: Project, uri: Uri): File {
        require(StorageUtils.isWithin(projectsRoot, project.root)) { "Project is outside storage root" }
        return iconStore.importIcon(project.id, uri)
    }

    fun createProject(displayName: String, requestedId: String, description: String, group: String = ""): Project {
        val cleanName = displayName.trim()
        require(cleanName.isNotEmpty()) { "Project name is required" }
        val id = requestedId.trim().ifEmpty { slugify(cleanName) }
        require(isSafeProjectId(id)) { "Project id may contain only letters, numbers, '-' and '_'" }
        val root = File(projectsRoot, id)
        require(!root.exists()) { "A project with this id already exists" }
        require(root.mkdirs()) { "Unable to create project directory" }
        try {
            ProjectTemplate.write(root) { path ->
                appContext.assets.open("project-template/$path")
            }
            StorageUtils.writeTextAtomic(
                File(root, "conf.lua"),
                defaultProjectConf(id, cleanName),
            )
            StorageUtils.writeTextAtomic(
                File(root, LuaLanguageServerProjectConfig.FILE_NAME),
                LuaLanguageServerProjectConfig.content(),
            )
            val project = Project(id, cleanName, description.trim(), root, 0L, group = group.trim())
            writeMetadata(project)
            return project
        } catch (error: Throwable) {
            StorageUtils.deleteRecursively(root)
            throw error
        }
    }

    fun importLoveArchive(input: java.io.InputStream, archiveName: String, group: String = ""): Project {
        val cleanName = archiveName.substringAfterLast('/').substringBeforeLast('.')
            .trim().ifBlank { "Imported Project" }
        val id = uniqueProjectId(slugify(cleanName))
        val root = File(projectsRoot, id)
        try {
            LoveArchiveTransfer.import(input, root)
            val project = Project(id, cleanName, "", root, 0L, group = group.trim())
            writeMetadata(project)
            return project
        } catch (error: Throwable) {
            if (root.exists()) StorageUtils.deleteRecursively(root)
            throw error
        }
    }

    fun renameProject(project: Project, newDisplayName: String, requestedId: String): Project {
        val cleanName = newDisplayName.trim()
        val newId = requestedId.trim()
        require(cleanName.isNotEmpty()) { "Project name is required" }
        require(isSafeProjectId(newId)) { "Project id is invalid" }
        val newRoot = File(projectsRoot, newId)
        require(newId == project.id || !newRoot.exists()) { "A project with this id already exists" }
        val movedRoot = if (newId == project.id) {
            project.root
        } else {
            require(project.root.renameTo(newRoot)) { "Unable to rename project directory" }
            newRoot
        }
        val updated = project.copy(id = newId, displayName = cleanName, root = movedRoot)
        iconStore.renameProject(project.id, newId)
        DebugWatchStore.renameProject(appContext, project.id, newId)
        writeMetadata(updated)
        if (preferences.getString("last_project_id", null) == project.id) {
            preferences.edit().putString("last_project_id", newId).apply()
        }
        return updated
    }

    fun deleteProject(project: Project) {
        require(StorageUtils.isWithin(projectsRoot, project.root)) { "Project is outside storage root" }
        StorageUtils.deleteRecursively(project.root)
        iconStore.deleteProject(project.id)
        DebugWatchStore.deleteProject(appContext, project.id)
        if (preferences.getString("last_project_id", null) == project.id) {
            preferences.edit().remove("last_project_id").apply()
        }
    }

    fun readProject(root: File): Project? {
        if (!root.isDirectory || !StorageUtils.isWithin(projectsRoot, root)) return null
        val metadataFile = File(root, StorageUtils.METADATA_FILE)
        val metadata = runCatching { JSONObject(metadataFile.readText(Charsets.UTF_8)) }.getOrNull()
        val displayName = metadata?.optString("displayName").orEmpty().ifBlank { root.name }
        val description = metadata?.optString("description").orEmpty()
        val lastOpened = metadata?.optLong("lastOpened", 0L) ?: 0L
        val group = metadata?.optString("group").orEmpty()
        val androidProperties = AndroidProjectProperties.fromJson(
            metadata?.optJSONObject("android"),
            root.name,
            displayName,
        )
        val breakpoints = buildList {
            val values = metadata?.optJSONArray("breakpoints") ?: JSONArray()
            for (index in 0 until values.length()) {
                val value = values.optJSONObject(index) ?: continue
                add(ProjectBreakpoint(value.optString("file"), value.optInt("line")))
            }
        }
        return Project(
            root.name,
            displayName,
            description,
            root,
            lastOpened,
            androidProperties,
            normalizeProjectBreakpoints(root, breakpoints),
            group,
        )
    }
    private fun uniqueProjectId(base: String): String {
        if (!File(projectsRoot, base).exists()) return base
        for (suffix in 2..9999) {
            val suffixText = "-$suffix"
            val candidate = base.take((64 - suffixText.length).coerceAtLeast(1)) + suffixText
            if (!File(projectsRoot, candidate).exists()) return candidate
        }
        throw IOException("无法为导入项目分配目录 ID")
    }

    private fun writeMetadata(project: Project) {
        val breakpoints = JSONArray()
        project.breakpoints.forEach { breakpoint ->
            breakpoints.put(
                JSONObject()
                    .put("file", breakpoint.file)
                    .put("line", breakpoint.line),
            )
        }
        val metadata = JSONObject()
            .put("id", project.id)
            .put("displayName", project.displayName)
            .put("description", project.description)
            .put("lastOpened", project.lastOpened)
            .put("group", project.group)
            .put("android", project.androidProperties.toJson())
            .put("breakpoints", breakpoints)
        StorageUtils.writeTextAtomic(File(project.root, StorageUtils.METADATA_FILE), metadata.toString(2))
    }

    companion object {
        fun isSafeProjectId(id: String): Boolean = id.matches(Regex("[A-Za-z0-9_-]{1,64}"))

        private fun slugify(name: String): String {
            val slug = name.lowercase(Locale.getDefault())
                .replace(Regex("[^a-z0-9_-]+"), "-")
                .trim('-')
            return slug.ifBlank { "project-${System.currentTimeMillis()}" }.take(64)
        }
    }
}
