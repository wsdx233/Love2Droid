package top.wsdx233.love2droid

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale
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
        t.window.resizable = true
    end
""".trimIndent() + "\n"

 data class Project(
    val id: String,
    val displayName: String,
    val description: String,
    val root: File,
    val lastOpened: Long,
)

class ProjectRepository(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("project-state", Context.MODE_PRIVATE)
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

    fun createProject(displayName: String, requestedId: String, description: String): Project {
        val cleanName = displayName.trim()
        require(cleanName.isNotEmpty()) { "Project name is required" }
        val id = requestedId.trim().ifEmpty { slugify(cleanName) }
        require(isSafeProjectId(id)) { "Project id may contain only letters, numbers, '-' and '_'" }
        val root = File(projectsRoot, id)
        require(!root.exists()) { "A project with this id already exists" }
        require(root.mkdirs()) { "Unable to create project directory" }
        try {
            File(root, "assets").mkdirs()
            StorageUtils.writeTextAtomic(
                File(root, "main.lua"),
                "function love.load()\nend\n\nfunction love.draw()\n    love.graphics.print(\"Hello from Love2Droid\", 32, 32)\nend\n",
            )
            StorageUtils.writeTextAtomic(
                File(root, "conf.lua"),
                defaultProjectConf(id, cleanName),
            )
            StorageUtils.writeTextAtomic(
                File(root, LuaLanguageServerProjectConfig.FILE_NAME),
                LuaLanguageServerProjectConfig.content(),
            )
            val project = Project(id, cleanName, description.trim(), root, 0L)
            writeMetadata(project)
            return project
        } catch (error: Throwable) {
            StorageUtils.deleteRecursively(root)
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
        writeMetadata(updated)
        if (preferences.getString("last_project_id", null) == project.id) {
            preferences.edit().putString("last_project_id", newId).apply()
        }
        return updated
    }

    fun deleteProject(project: Project) {
        require(StorageUtils.isWithin(projectsRoot, project.root)) { "Project is outside storage root" }
        StorageUtils.deleteRecursively(project.root)
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
        return Project(root.name, displayName, description, root, lastOpened)
    }

    private fun writeMetadata(project: Project) {
        val metadata = JSONObject()
            .put("id", project.id)
            .put("displayName", project.displayName)
            .put("description", project.description)
            .put("lastOpened", project.lastOpened)
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
