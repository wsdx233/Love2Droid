package top.wsdx233.love2droid

import android.content.Context
import android.util.Log
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

object DshDaemon {
    private const val TAG = "DshDaemon"
    const val DEFAULT_PORT = 3080
    const val DEFAULT_URL = "http://127.0.0.1:3080"
    private const val DAEMON_COLUMNS = 1000
    private const val DAEMON_ROWS = 160
    private val AUTHENTICATED_URL_PATTERN = Regex(
        "(?m)^dsh web: (http://127\\.0\\.0\\.1:${DEFAULT_PORT}/\\?token=[A-Za-z0-9_-]+)(?:[ \\t]+[^\\r\\n]*)?\\r?\\n",
    )

    private var backgroundSession: TerminalSession? = null
    private var authenticatedWebUrl: String? = null
    private var authenticatedSession: TerminalSession? = null
    private val webUrlListeners = mutableSetOf<(String) -> Unit>()

    private val daemonClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            captureAuthenticatedUrl(changedSession)
        }
        override fun onTitleChanged(changedSession: TerminalSession) = Unit
        override fun onSessionFinished(finishedSession: TerminalSession) {
            Log.i(TAG, "Dsh background session finished")
            DshDaemon.onSessionFinished(finishedSession)
        }
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) = Unit
        override fun onPasteTextFromClipboard(session: TerminalSession?) = Unit
        override fun onBell(session: TerminalSession) = Unit
        override fun onColorsChanged(session: TerminalSession) = Unit
        override fun onTerminalCursorStateChange(state: Boolean) = Unit
        override fun setTerminalShellPid(session: TerminalSession, pid: Int) {
            if (pid > 0) {
                session.write("${ProotRuntime.dshStartupCommand()}\n")
            }
        }
        override fun getTerminalCursorStyle(): Int = 0
        override fun logError(tag: String?, message: String?) = Unit
        override fun logWarn(tag: String?, message: String?) = Unit
        override fun logInfo(tag: String?, message: String?) = Unit
        override fun logDebug(tag: String?, message: String?) = Unit
        override fun logVerbose(tag: String?, message: String?) = Unit
        override fun logStackTraceWithMessage(tag: String?, message: String?, error: Exception?) = Unit
        override fun logStackTrace(tag: String?, error: Exception?) = Unit
    }

    fun currentWebUrl(): String? = synchronized(this) { authenticatedWebUrl }

    fun observeWebUrl(listener: (String) -> Unit): () -> Unit {
        val current = synchronized(this) {
            webUrlListeners += listener
            authenticatedWebUrl
        }
        current?.let(listener)
        return { synchronized(this) { webUrlListeners -= listener } }
    }

    internal fun extractAuthenticatedWebUrl(output: String): String? =
        AUTHENTICATED_URL_PATTERN.findAll(output).lastOrNull()?.groupValues?.get(1)

    internal fun captureAuthenticatedUrl(session: TerminalSession) {
        val emulator = session.emulator ?: return
        val screen = emulator.screen
        // Include the cursor row without trim(): only newline-terminated banners
        // are complete. Join soft wraps, but never accept a partially emitted token.
        val startRow = maxOf(-screen.activeTranscriptRows, emulator.cursorRow - (512 / emulator.mColumns + 2))
        val output = screen.getSelectedText(0, startRow, emulator.mColumns, emulator.cursorRow, true, false)
        val url = extractAuthenticatedWebUrl(output) ?: return
        val listeners = synchronized(this) {
            if (authenticatedWebUrl == url) return
            authenticatedSession = session
            authenticatedWebUrl = url
            webUrlListeners.toList()
        }
        listeners.forEach { listener -> listener(url) }
    }

    internal fun onSessionFinished(session: TerminalSession) = synchronized(this) {
        if (backgroundSession === session) backgroundSession = null
        if (authenticatedSession === session) {
            authenticatedSession = null
            authenticatedWebUrl = null
        }
    }

    fun isRunning(): Boolean = synchronized(this) {
        backgroundSession?.isRunning == true || authenticatedSession?.isRunning == true
    }

    fun ensureStarted(context: Context): Boolean {
        if (!ProotRuntime.isDshReady(context)) {
            return false
        }
        if (isRunning()) {
            return true
        }

        return try {
            val spec = ProotRuntime.terminalLaunch(context, null)
            val session = TerminalSession(
                spec.executable,
                spec.workingDirectory,
                spec.arguments,
                spec.environment,
                1000,
                daemonClient,
            )
            synchronized(this) {
                backgroundSession = session
                authenticatedWebUrl = null
                authenticatedSession = null
            }
            // TerminalSession starts its PTY from initializeEmulator(). A daemon
            // has no TerminalView to perform that initialization for it.
            session.initializeEmulator(DAEMON_COLUMNS, DAEMON_ROWS, 1, 1)
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start DSH daemon", e)
            synchronized(this) {
                if (backgroundSession != null && !backgroundSession!!.isRunning) {
                    backgroundSession = null
                    authenticatedWebUrl = null
                    authenticatedSession = null
                }
            }
            false
        }
    }

    fun stop() {
        val session = synchronized(this) {
            val current = backgroundSession
            backgroundSession = null
            authenticatedWebUrl = null
            authenticatedSession = null
            current
        }
        try {
            session?.finishIfRunning()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to stop DSH session", e)
        }
    }
}
