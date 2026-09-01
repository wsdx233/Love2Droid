package top.wsdx233.love2droid

import android.content.Context
import android.util.Log
import io.github.rosemoe.sora.lsp.client.connection.StreamConnectionProvider
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class LuaLanguageServerConnection(
    context: Context,
    private val projectRoot: File,
) : StreamConnectionProvider {
    private val appContext = context.applicationContext

    @Volatile
    private var process: Process? = null

    override fun start() {
        check(process == null) { "Lua Language Server connection already started" }
        val spec = ProotRuntime.languageServerLaunch(appContext, projectRoot)
        val started = ProcessBuilder(spec.command)
            .directory(spec.workingDirectory)
            .apply { environment().putAll(spec.environment) }
            .start()
        process = started
        Thread(
            {
                started.errorStream.bufferedReader().useLines { lines ->
                    lines.forEach { line -> Log.w(TAG, line) }
                }
            },
            "lua-lsp-stderr",
        ).apply {
            isDaemon = true
            start()
        }
    }

    override val inputStream: InputStream
        get() = checkNotNull(process) { "Lua Language Server is not started" }.inputStream

    override val outputStream: OutputStream
        get() = checkNotNull(process) { "Lua Language Server is not started" }.outputStream

    override val isClosed: Boolean
        get() = process?.isAlive != true

    override fun close() {
        val current = process ?: return
        runCatching { current.outputStream.close() }
        runCatching { current.inputStream.close() }
        runCatching { current.errorStream.close() }
        current.destroy()
        process = null
    }

    private companion object {
        const val TAG = "LuaLanguageServer"
    }
}
