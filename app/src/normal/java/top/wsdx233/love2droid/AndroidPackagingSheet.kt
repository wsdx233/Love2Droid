package top.wsdx233.love2droid

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

internal class AndroidPackagingSheet(
    private val activity: AppCompatActivity,
    private val scope: CoroutineScope,
    private val repository: ProjectRepository,
    private val project: Project,
    private val chooseSigningKey: (((Uri?) -> Unit) -> Unit),
) {
    private val dialog = BottomSheetDialog(activity)
    private val builder = AndroidApkBuilder(activity)
    private val signingStatus = TextView(activity)
    private val status = TextView(activity)
    private val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal)
    private val buildButton = MaterialButton(activity)
    private val shareButton = MaterialButton(activity)
    private val installButton = MaterialButton(activity)
    private var output: File? = null

    fun show() {
        dialog.setContentView(buildContent())
        dialog.setOnShowListener {
            dialog.behavior.skipCollapsed = true
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
        dialog.show()
    }

    private fun buildContent(): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(16), dp(20), dp(28))
        addView(TextView(activity).apply {
            setText(R.string.android_package_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        addView(TextView(activity).apply {
            val properties = latestProject().androidProperties
            text = activity.getString(
                R.string.android_package_summary,
                properties.appName,
                properties.applicationId,
                properties.versionName,
                properties.versionCode,
            )
            setPadding(0, dp(12), 0, dp(8))
        })
        addView(TextView(activity).apply {
            setText(R.string.android_signing_title)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(4))
        })
        updateSigningStatus()
        addView(signingStatus)
        addView(MaterialButton(activity).apply {
            setText(R.string.android_import_signing_key)
            setOnClickListener { chooseSigningKey(::promptSigningPassword) }
        }, matchWidth())
        progress.isIndeterminate = true
        progress.visibility = View.GONE
        addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)).apply {
            topMargin = dp(8)
        })
        status.setPadding(0, dp(8), 0, dp(8))
        addView(status, matchWidth(ViewGroup.LayoutParams.WRAP_CONTENT))
        buildButton.apply {
            setText(R.string.android_build_apk)
            setOnClickListener { buildApk() }
        }
        addView(buildButton, matchWidth())
        val actions = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        shareButton.apply {
            setText(R.string.share_apk)
            visibility = View.GONE
            setOnClickListener { output?.let(::share) }
        }
        installButton.apply {
            setText(R.string.install_apk)
            visibility = View.GONE
            setOnClickListener { output?.let(::install) }
        }
        actions.addView(shareButton, weightedButton())
        actions.addView(installButton, weightedButton())
        addView(actions, matchWidth())
    }

    private fun buildApk() {
        if (output != null) return
        setBusy(true)
        scope.launch {
            try {
                val apk = withContext(Dispatchers.IO) {
                    val current = latestProject()
                    ProjectValidator.validate(current)?.let { throw IOException(it) }
                    builder.build(current) { stage ->
                        activity.runOnUiThread { status.setText(stage.messageResource()) }
                    }
                }
                output = apk
                status.text = activity.getString(R.string.android_package_ready, apk.name)
                shareButton.visibility = View.VISIBLE
                installButton.visibility = View.VISIBLE
                updateSigningStatus()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                status.setText(R.string.android_package_failed)
                output = null
            } finally {
                setBusy(false)
            }
        }
    }

    private fun promptSigningPassword(uri: Uri?) {
        if (uri == null || !dialog.isShowing) return
        val password = EditText(activity).apply {
            hint = activity.getString(R.string.android_signing_password)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.android_import_signing_key)
            .setView(password)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.import_action) { _, _ -> importSigningKey(uri, password.text.toString()) }
            .show()
    }

    private fun importSigningKey(uri: Uri, passwordText: String) {
        setBusy(true)
        scope.launch {
            val password = passwordText.toCharArray()
            try {
                val fingerprint = withContext(Dispatchers.IO) {
                    builder.importSigningKey(latestProject(), uri, password)
                }
                signingStatus.text = activity.getString(R.string.android_signing_fingerprint, fingerprint)
                status.setText(R.string.android_signing_imported)
                output = null
                shareButton.visibility = View.GONE
                installButton.visibility = View.GONE
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                status.setText(R.string.android_signing_import_failed)
            } finally {
                password.fill('\u0000')
                setBusy(false)
            }
        }
    }

    private fun updateSigningStatus() {
        val fingerprint = runCatching { builder.signingFingerprint(latestProject()) }.getOrNull()
        signingStatus.text = if (fingerprint == null) {
            activity.getString(R.string.android_signing_automatic)
        } else {
            activity.getString(R.string.android_signing_fingerprint, fingerprint)
        }
    }

    private fun latestProject(): Project = repository.findProject(project.id) ?: project

    private fun share(apk: File) {
        val uri = apkUri(apk)
        activity.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND)
                    .setType(APK_MIME_TYPE)
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                activity.getString(R.string.share_apk),
            ),
        )
    }

    private fun install(apk: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")),
            )
            status.setText(R.string.android_install_permission_required)
            return
        }
        activity.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(apkUri(apk), APK_MIME_TYPE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun apkUri(apk: File): Uri =
        FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)

    private fun setBusy(value: Boolean) {
        progress.visibility = if (value) View.VISIBLE else View.GONE
        buildButton.isEnabled = !value && output == null
        shareButton.isEnabled = !value
        installButton.isEnabled = !value
    }

    private fun AndroidPackageStage.messageResource(): Int = when (this) {
        AndroidPackageStage.PACKAGE_GAME -> R.string.android_package_game
        AndroidPackageStage.ASSEMBLE_APK -> R.string.android_package_assembling
        AndroidPackageStage.SIGN_APK -> R.string.android_package_signing
    }


    private fun matchWidth(height: Int = dp(48)): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)

    private fun weightedButton(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(4) }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    private companion object {
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}
