package top.wsdx233.love2droid

import android.content.Context
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.lsp.client.languageserver.serverdefinition.CustomLanguageServerDefinition
import io.github.rosemoe.sora.lsp.editor.LspEditor
import io.github.rosemoe.sora.lsp.editor.LspProject
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class LuaLspController(
    context: Context,
    private val codeEditor: CodeEditor,
    private val onConnectionError: (Throwable) -> Unit,
) {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private var projectRoot: File? = null
    private var project: LspProject? = null
    private var activeEditor: LspEditor? = null

    suspend fun attach(projectRoot: File, file: File, wrapperLanguage: Language) {
        mutex.withLock {
            if (!ProotRuntime.isEnvironmentReady(appContext) || file.extension.lowercase() != "lua") {
                disposeActive()
                return
            }
            ensureProject(projectRoot)
            disposeActive()
            val lspProject = checkNotNull(project)
            val lspEditor = lspProject.createEditor(file.absolutePath)
            activeEditor = lspEditor
            withContext(Dispatchers.Main.immediate) {
                lspEditor.wrapperLanguage = wrapperLanguage
                lspEditor.editor = codeEditor
            }
            try {
                lspEditor.connectWithTimeout()
            } catch (cancelled: CancellationException) {
                disposeActive()
                throw cancelled
            } catch (error: Throwable) {
                disposeActive()
                withContext(Dispatchers.Main.immediate) {
                    codeEditor.setEditorLanguage(wrapperLanguage)
                    onConnectionError(error)
                }
            }
        }
    }

    suspend fun detach() {
        mutex.withLock { disposeActive() }
    }

    suspend fun notifySaved(file: File) {
        mutex.withLock {
            val current = activeEditor ?: return
            if (current.uri.path == file.absolutePath && current.isConnected) {
                current.saveDocument()
            }
        }
    }

    fun close() {
        runCatching { activeEditor?.dispose() }
        activeEditor = null
        runCatching { project?.dispose() }
        project = null
        projectRoot = null
    }

    private fun ensureProject(root: File) {
        val canonical = root.canonicalFile
        if (projectRoot == canonical && project != null) return
        runCatching { project?.dispose() }
        val definition = CustomLanguageServerDefinition(
            "lua",
            CustomLanguageServerDefinition.ServerConnectProvider {
                LuaLanguageServerConnection(appContext, canonical)
            },
            "lua-language-server",
        )
        project = LspProject(canonical.absolutePath).apply {
            addServerDefinition(definition)
        }
        projectRoot = canonical
    }

    private suspend fun disposeActive() {
        val current = activeEditor ?: return
        activeEditor = null
        runCatching { current.disposeAsync() }
    }
}
