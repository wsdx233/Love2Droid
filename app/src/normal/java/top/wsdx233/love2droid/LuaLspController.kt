package top.wsdx233.love2droid

import android.content.Context
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.lsp.client.languageserver.serverdefinition.CustomLanguageServerDefinition
import io.github.rosemoe.sora.lsp.editor.LspEditor
import io.github.rosemoe.sora.lsp.editor.LspProject
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.lsp.utils.createTextDocumentIdentifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.WorkspaceSymbol
import org.eclipse.lsp4j.WorkspaceSymbolParams
import java.util.concurrent.TimeUnit

class LuaLspController(
    context: Context,
    private val codeEditor: CodeEditor,
    private val onConnectionError: (Throwable) -> Unit,
    private val onFileLink: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private var projectRoot: File? = null
    private var project: LspProject? = null
    @Volatile
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
                withContext(Dispatchers.Main.immediate) {
                    lspEditor.hoverWindow?.layout = SafeHoverLayout(onFileLink)
                }
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

    internal fun dismissHover() {
        activeEditor?.hoverWindow?.dismiss()
    }

    internal fun isNavigationAvailable(file: File): Boolean = activeEditorFor(file) != null

    internal suspend fun findDefinitions(file: File, line: Int, column: Int): List<LuaSymbolLocation> {
        val current = activeEditorFor(file) ?: return emptyList()
        val params = DefinitionParams(
            current.uri.createTextDocumentIdentifier(),
            Position(line.coerceAtLeast(0), column.coerceAtLeast(0)),
        )
        val response = withContext(Dispatchers.IO) {
            current.requestManager.definition(params)?.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } ?: return emptyList()
        val locations = if (response.isLeft) {
            response.left.orEmpty().map(::toSymbolLocation)
        } else {
            response.right.orEmpty().map(::toSymbolLocation)
        }
        return locations.distinctAndSorted()
    }

    internal suspend fun findReferences(file: File, line: Int, column: Int): List<LuaSymbolLocation> {
        val current = activeEditorFor(file) ?: return emptyList()
        val params = ReferenceParams(
            current.uri.createTextDocumentIdentifier(),
            Position(line.coerceAtLeast(0), column.coerceAtLeast(0)),
            ReferenceContext(false),
        )
        val locations = withContext(Dispatchers.IO) {
            current.requestManager.references(params)?.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }.orEmpty()
        return locations.filterNotNull().map(::toSymbolLocation).distinctAndSorted()
    }

    internal suspend fun searchWorkspaceSymbols(query: String): List<LuaWorkspaceSymbol> {
        val current = activeEditor?.takeIf { it.isConnected } ?: return emptyList()
        val response = withContext(Dispatchers.IO) {
            current.requestManager.symbol(WorkspaceSymbolParams(query))
                ?.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } ?: return emptyList()
        val symbols = if (response.isLeft) {
            response.left.orEmpty().map(::toWorkspaceSymbol)
        } else {
            response.right.orEmpty().filterNotNull().map(::toWorkspaceSymbol)
        }
        return symbols.distinctBy { listOf(it.name, it.location.uri, it.location.startLine, it.location.startColumn) }
            .take(MAX_WORKSPACE_SYMBOLS)
    }

    private fun activeEditorFor(file: File): LspEditor? {
        val current = activeEditor ?: return null
        if (!current.isConnected) return null
        val matches = runCatching {
            File(current.uri.path).canonicalFile == file.canonicalFile
        }.getOrDefault(false)
        return current.takeIf { matches }
    }

    private fun toSymbolLocation(location: Location): LuaSymbolLocation =
        toSymbolLocation(location.uri, location.range)

    private fun toSymbolLocation(location: LocationLink): LuaSymbolLocation =
        toSymbolLocation(location.targetUri, location.targetSelectionRange ?: location.targetRange)

    private fun toSymbolLocation(uri: String, range: Range): LuaSymbolLocation = LuaSymbolLocation(
        uri = uri,
        startLine = range.start.line,
        startColumn = range.start.character,
        endLine = range.end.line,
        endColumn = range.end.character,
    )

    private fun toWorkspaceSymbol(symbol: SymbolInformation): LuaWorkspaceSymbol = LuaWorkspaceSymbol(
        name = symbol.name,
        kind = symbol.kind.toString(),
        location = toSymbolLocation(symbol.location),
    )

    private fun toWorkspaceSymbol(symbol: WorkspaceSymbol): LuaWorkspaceSymbol {
        val location = if (symbol.location.isLeft) {
            toSymbolLocation(symbol.location.left)
        } else {
            LuaSymbolLocation(symbol.location.right.uri, 0, 0, 0, 0)
        }
        return LuaWorkspaceSymbol(symbol.name, symbol.kind.toString(), location)
    }

    private fun List<LuaSymbolLocation>.distinctAndSorted(): List<LuaSymbolLocation> =
        distinctBy { listOf(it.uri, it.startLine, it.startColumn, it.endLine, it.endColumn) }
            .sortedWith(compareBy({ it.uri }, { it.startLine }, { it.startColumn }))

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

    private companion object {
        const val REQUEST_TIMEOUT_SECONDS = 10L
        const val MAX_WORKSPACE_SYMBOLS = 1_000
    }

    private suspend fun disposeActive() {
        val current = activeEditor ?: return
        activeEditor = null
        current.hoverWindow?.dismiss()
        runCatching { current.disposeAsync() }
    }
}
