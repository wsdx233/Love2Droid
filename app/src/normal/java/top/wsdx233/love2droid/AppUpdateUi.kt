package top.wsdx233.love2droid

import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal object AppUpdateUi {
    val session = AppUpdateSession(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)) {
        withContext(Dispatchers.IO) { AppUpdateChecker.check(BuildConfig.VERSION_NAME) }
    }

    fun bind(activity: AppCompatActivity, onCheckingChanged: (Boolean) -> Unit = {}) {
        val settings = SettingsStore(activity)
        session.checkAtStartup(settings.autoCheckUpdates)
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                session.state.collect { state ->
                    onCheckingChanged(state.checking)
                    if (activity.isFinishing || activity.supportFragmentManager.isStateSaved) return@collect
                    when (val notice = session.takeNotice(settings.autoCheckUpdates)) {
                        is AppUpdateResult.Available -> AppUpdateDialog().apply {
                            arguments = Bundle().apply {
                                putString("version", notice.tag)
                                putString("url", notice.releaseUrl)
                            }
                        }.show(activity.supportFragmentManager, "app-update")
                        AppUpdateResult.UpToDate -> Toast.makeText(
                            activity, R.string.update_up_to_date, Toast.LENGTH_LONG,
                        ).show()
                        AppUpdateResult.NoRelease -> Toast.makeText(
                            activity, R.string.update_no_release, Toast.LENGTH_LONG,
                        ).show()
                        AppUpdateResult.Failed -> Toast.makeText(
                            activity, R.string.update_check_failed, Toast.LENGTH_LONG,
                        ).show()
                        null -> Unit
                    }
                }
            }
        }
    }
}

/** Fragment arguments preserve an already-presented update across configuration changes. */
class AppUpdateDialog : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val args = requireArguments()
        return MaterialAlertDialogBuilder(context)
            .setTitle(R.string.update_available)
            .setMessage(getString(R.string.update_available_message, BuildConfig.VERSION_NAME, args.getString("version")))
            .setNegativeButton(R.string.update_later, null)
            .setPositiveButton(R.string.update_open_release) { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(args.getString("url"))))
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(context, R.string.update_browser_unavailable, Toast.LENGTH_LONG).show()
                }
            }
            .create()
    }
}
