package top.wsdx233.love2droid

import android.graphics.Typeface
import android.net.Uri
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class ProjectPropertiesSheet(
    private val activity: AppCompatActivity,
    private val scope: CoroutineScope,
    private val repository: ProjectRepository,
    private val project: Project,
    private val chooseIcon: (((Uri?) -> Unit) -> Unit),
    private val onSaved: (Project) -> Unit,
) {
    private val dialog = BottomSheetDialog(activity)
    private val appName = field(R.string.project_property_app_name)
    private val applicationId = field(R.string.project_property_package_name)
    private val versionName = field(R.string.project_property_version_name)
    private val versionCode = field(R.string.project_property_version_code, InputType.TYPE_CLASS_NUMBER)
    private val orientation = Spinner(activity)
    private val customPermissions = field(R.string.project_property_custom_permissions)
    private val permissionChecks = linkedMapOf<String, MaterialCheckBox>()
    private val iconPreview = ImageView(activity)
    private val progress = ProgressBar(activity)
    private var selectedIcon: Uri? = null

    fun show() {
        val content = buildContent()
        bindCurrentValues()
        dialog.setContentView(content)
        dialog.setOnShowListener {
            dialog.behavior.skipCollapsed = true
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
        dialog.show()
    }

    private fun buildContent(): View {
        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(28))
        }
        form.addView(TextView(activity).apply {
            text = activity.getString(R.string.project_properties)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(12))
        })
        val iconRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        iconPreview.apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            val icon = repository.projectIcon(project)
            if (icon != null) setImageURI(Uri.fromFile(icon)) else setImageResource(R.drawable.love)
        }
        iconRow.addView(iconPreview, LinearLayout.LayoutParams(dp(72), dp(72)))
        iconRow.addView(MaterialButton(activity).apply {
            text = activity.getString(R.string.project_property_choose_icon)
            setOnClickListener {
                chooseIcon { uri ->
                    if (uri != null && dialog.isShowing) {
                        selectedIcon = uri
                        iconPreview.setImageURI(uri)
                    }
                }
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply { marginStart = dp(16) })
        form.addView(iconRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        form.addView(appName)
        form.addView(applicationId)
        form.addView(versionName)
        form.addView(versionCode)
        form.addView(label(R.string.project_property_orientation))
        form.addView(orientation, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        form.addView(label(R.string.project_property_permissions))
        AndroidProjectProperties.COMMON_PERMISSIONS.forEach { permission ->
            val checkBox = MaterialCheckBox(activity).apply {
                text = permission
            }
            permissionChecks[permission] = checkBox
            form.addView(checkBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        form.addView(customPermissions)
        form.addView(TextView(activity).apply {
            setText(R.string.project_property_custom_permissions_summary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        })
        progress.visibility = View.GONE
        form.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)))
        form.addView(MaterialButton(activity).apply {
            text = activity.getString(R.string.save_changes)
            setOnClickListener { save() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(16) })
        return ScrollView(activity).apply { addView(form) }
    }

    private fun bindCurrentValues() {
        val value = project.androidProperties
        appName.setText(value.appName)
        applicationId.setText(value.applicationId)
        versionName.setText(value.versionName)
        versionCode.setText(value.versionCode.toString())
        orientation.adapter = ArrayAdapter(
            activity,
            android.R.layout.simple_spinner_dropdown_item,
            listOf(
                activity.getString(R.string.orientation_landscape),
                activity.getString(R.string.orientation_portrait),
                activity.getString(R.string.orientation_sensor_landscape),
                activity.getString(R.string.orientation_sensor_portrait),
                activity.getString(R.string.orientation_unspecified),
            ),
        )
        orientation.setSelection(GameScreenOrientation.entries.indexOf(value.orientation))
        AndroidProjectProperties.COMMON_PERMISSIONS.forEach { permission ->
            permissionChecks[permission]?.isChecked = permission in value.permissions
        }
        customPermissions.setText(
            value.permissions.filterNot(AndroidProjectProperties.COMMON_PERMISSIONS::contains).joinToString("\n"),
        )
    }

    private fun save() {
        clearErrors()
        val cleanName = appName.text.toString().trim()
        val cleanApplicationId = applicationId.text.toString().trim()
        val cleanVersionName = versionName.text.toString().trim()
        val cleanVersionCode = versionCode.text.toString().toIntOrNull()
        if (cleanName.isEmpty()) {
            appName.error = activity.getString(R.string.project_property_name_required)
            return
        }
        if (!AndroidProjectProperties.isValidApplicationId(cleanApplicationId)) {
            applicationId.error = activity.getString(R.string.project_property_package_invalid)
            return
        }
        if (cleanVersionName.isEmpty()) {
            versionName.error = activity.getString(R.string.project_property_version_name_required)
            return
        }
        if (cleanVersionCode == null || cleanVersionCode <= 0) {
            versionCode.error = activity.getString(R.string.project_property_version_code_invalid)
            return
        }
        val extraPermissions = customPermissions.text.toString()
            .split(Regex("[,\\s]+"))
            .filter(String::isNotBlank)
            .toSet()
        val invalidPermission = extraPermissions.firstOrNull {
            !AndroidProjectProperties.isValidPermissionName(it)
        }
        if (invalidPermission != null) {
            customPermissions.error = activity.getString(R.string.project_property_permission_invalid, invalidPermission)
            return
        }
        val permissions = buildSet {
            permissionChecks.filterValues(MaterialCheckBox::isChecked).keys.forEach(::add)
            addAll(extraPermissions)
        }
        if (permissions.size > AndroidProjectProperties.MAX_PERMISSIONS) {
            customPermissions.error = activity.getString(
                R.string.project_property_permissions_too_many,
                AndroidProjectProperties.MAX_PERMISSIONS,
            )
            return
        }
        val updatedProperties = AndroidProjectProperties(
            appName = cleanName,
            applicationId = cleanApplicationId,
            versionName = cleanVersionName,
            versionCode = cleanVersionCode,
            orientation = GameScreenOrientation.entries[orientation.selectedItemPosition],
            permissions = permissions,
            signingKeyId = project.androidProperties.signingKeyId,
        )
        progress.visibility = View.VISIBLE
        scope.launch {
            try {
                val updated = withContext(Dispatchers.IO) {
                    selectedIcon?.let { repository.importProjectIcon(project, it) }
                    repository.updateAndroidProperties(project, updatedProperties)
                }
                onSaved(updated)
                dialog.dismiss()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                progress.visibility = View.GONE
                customPermissions.error = activity.getString(R.string.project_properties_save_failed)
            }
        }
    }

    private fun field(hintRes: Int, inputTypeValue: Int = InputType.TYPE_CLASS_TEXT): EditText = EditText(activity).apply {
        hint = activity.getString(hintRes)
        inputType = inputTypeValue
        maxLines = if (hintRes == R.string.project_property_custom_permissions) 4 else 1
        setPadding(dp(8), dp(8), dp(8), dp(8))
    }

    private fun label(textRes: Int): TextView = TextView(activity).apply {
        setText(textRes)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(14), 0, dp(4))
    }

    private fun clearErrors() {
        listOf(appName, applicationId, versionName, versionCode, customPermissions).forEach { it.error = null }
    }


    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
}
