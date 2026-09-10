package top.wsdx233.love2droid

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class AppUpdateState(
    val checking: Boolean = false,
    val result: AppUpdateResult? = null,
    val manual: Boolean = false,
)

/** One session per app process. All access is confined to the main dispatcher. */
internal class AppUpdateSession(
    private val scope: CoroutineScope,
    private val loadRelease: suspend () -> AppUpdateResult,
) {
    private val mutableState = MutableStateFlow(AppUpdateState())
    val state = mutableState.asStateFlow()
    private var startupHandled = false
    private var manualRequested = false

    fun checkAtStartup(enabled: Boolean) {
        if (startupHandled) return
        startupHandled = true
        if (enabled) check(manual = false)
    }

    fun checkManually() = check(manual = true)

    private fun check(manual: Boolean) {
        if (mutableState.value.checking) {
            manualRequested = manualRequested || manual
            return
        }
        manualRequested = manual
        mutableState.value = AppUpdateState(checking = true)
        scope.launch {
            val result = try {
                loadRelease()
            } catch (cancelled: CancellationException) {
                mutableState.value = AppUpdateState()
                throw cancelled
            } catch (_: Exception) {
                AppUpdateResult.Failed
            }
            mutableState.value = AppUpdateState(result = result, manual = manualRequested)
        }
    }

    /** Results remain pending while no update UI is resumed, and are presented at most once. */
    fun takeNotice(automaticEnabled: Boolean): AppUpdateResult? {
        val completed = mutableState.value
        val result = completed.result ?: return null
        mutableState.value = AppUpdateState()
        return result.takeIf {
            completed.manual || (automaticEnabled && result is AppUpdateResult.Available)
        }
    }
}
