package top.wsdx233.love2droid

import android.animation.LayoutTransition
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.chip.Chip
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.launch

class SetupActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TARGET_COMPONENT = "extra_target_component"
        const val EXTRA_FROM_SETTINGS = "extra_from_settings"

        fun start(context: Context, targetComponent: String? = null, fromSettings: Boolean = false) {
            val intent = Intent(context, SetupActivity::class.java).apply {
                if (targetComponent != null) {
                    putExtra(EXTRA_TARGET_COMPONENT, targetComponent)
                }
                if (fromSettings) {
                    putExtra(EXTRA_FROM_SETTINGS, true)
                }
            }
            context.startActivity(intent)
        }
    }

    private val selectedComponentIds = mutableSetOf<String>()
    private var lastInstalledComponents: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settingsStore = SettingsStore(this)
        val isExplicitLaunch = intent.hasExtra(EXTRA_TARGET_COMPONENT) ||
            intent.getBooleanExtra("extra_from_settings", false) ||
            intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK == 0

        if (!isExplicitLaunch && settingsStore.hasShownInitialSetup) {
            startActivity(Intent(this, EditorActivity::class.java))
            finish()
            return
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_setup)

        val root = findViewById<View>(R.id.setup_root)
        val toolbar = findViewById<MaterialToolbar>(R.id.setup_toolbar)
        val selectionContainer = findViewById<View>(R.id.setup_selection_container)
        val progressContainer = findViewById<View>(R.id.setup_progress_container)
        val componentsListContainer = findViewById<LinearLayout>(R.id.setup_components_list)

        val startInstallButton = findViewById<Button>(R.id.setup_start_install_button)
        val skipInstallButton = findViewById<Button>(R.id.setup_skip_install_button)

        val setupTitle = findViewById<TextView>(R.id.setup_title)
        val setupSubtitle = findViewById<TextView>(R.id.setup_subtitle)
        val progressStatusIcon = findViewById<ImageView>(R.id.setup_progress_status_icon)
        val setupProgress = findViewById<LinearProgressIndicator>(R.id.setup_progress)
        val setupProgressPercent = findViewById<TextView>(R.id.setup_progress_percent)
        val setupProgressDetail = findViewById<TextView>(R.id.setup_progress_detail)
        val setupProgressFile = findViewById<TextView>(R.id.setup_progress_file)
        val setupLogs = findViewById<TextView>(R.id.setup_logs)
        val setupLogsScroll = findViewById<NestedScrollView>(R.id.setup_logs_scroll)
        val setupRetryButton = findViewById<Button>(R.id.setup_retry_button)
        val setupContinueButton = findViewById<Button>(R.id.setup_continue_button)

        // 启用平滑过渡动画
        componentsListContainer.layoutTransition = LayoutTransition().apply {
            enableTransitionType(LayoutTransition.CHANGING)
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        toolbar.setNavigationOnClickListener {
            if (!ProotInstaller.state.value.isWorking) {
                finish()
            }
        }

        val targetComponent = intent.getStringExtra(EXTRA_TARGET_COMPONENT)
        val allComponents: List<InstallComponent> = InstallRegistry.availableComponents

        // 初始选择逻辑：如果指定了目标组件，预先选定该组件及其依赖；否则默认勾选全部未安装组件
        if (targetComponent != null) {
            val target = InstallRegistry.find(targetComponent)
            if (target != null) {
                selectedComponentIds.addAll(target.dependencies)
                selectedComponentIds.add(target.id)
            }
        } else {
            allComponents.forEach { comp: InstallComponent ->
                if (!comp.isInstalled(this)) {
                    selectedComponentIds.add(comp.id)
                }
            }
            if (selectedComponentIds.isEmpty()) {
                selectedComponentIds.addAll(allComponents.map { it.id })
            }
        }
        if (BuildConfig.BUNDLED_ROOTFS) {
            selectedComponentIds.addAll(allComponents.map { it.id })
            findViewById<TextView>(R.id.selection_header_subtitle).setText(R.string.components_offline_subtitle)
            startInstallButton.setText(R.string.components_offline_install)
        }

        fun populateComponentList() {
            componentsListContainer.removeAllViews()
            val inflater = LayoutInflater.from(this)

            allComponents.forEach { comp: InstallComponent ->
                val itemView = inflater.inflate(R.layout.item_install_component, componentsListContainer, false)
                val card = itemView.findViewById<MaterialCardView>(R.id.component_card)
                val icon = itemView.findViewById<ImageView>(R.id.component_icon)
                val name = itemView.findViewById<TextView>(R.id.component_name)
                val size = itemView.findViewById<TextView>(R.id.component_size)
                val desc = itemView.findViewById<TextView>(R.id.component_description)
                val requiredBadge = itemView.findViewById<Chip>(R.id.component_required_badge)
                val checkBox = itemView.findViewById<MaterialCheckBox>(R.id.component_checkbox)
                val statusIcon = itemView.findViewById<ImageView>(R.id.component_status_icon)

                name.setText(comp.displayNameRes)
                desc.setText(comp.descriptionRes)
                icon.setImageResource(comp.iconRes)
                val isInstalled = comp.isInstalled(this)

                if (comp.isRequired) {
                    requiredBadge.visibility = View.VISIBLE
                } else {
                    requiredBadge.visibility = View.GONE
                }

                if (isInstalled) {
                    if (BuildConfig.BUNDLED_ROOTFS) size.setText(R.string.components_installed_tag)
                    else size.text = getString(R.string.components_size_installed_format, comp.diskSizeEstimateMb)
                    checkBox.visibility = View.GONE
                    statusIcon.visibility = View.VISIBLE
                    card.isClickable = false
                } else {
                    if (BuildConfig.BUNDLED_ROOTFS) size.setText(R.string.components_offline_bundled)
                    else size.text = getString(R.string.components_size_format, comp.downloadSizeEstimateMb, comp.diskSizeEstimateMb)
                    checkBox.visibility = View.VISIBLE
                    statusIcon.visibility = View.GONE
                    checkBox.isChecked = selectedComponentIds.contains(comp.id)

                    val toggleSelection = {
                        val willCheck = !checkBox.isChecked
                        checkBox.isChecked = willCheck
                        if (willCheck) {
                            selectedComponentIds.add(comp.id)
                            // 级联选中其依赖项
                            selectedComponentIds.addAll(comp.dependencies)
                        } else {
                            selectedComponentIds.remove(comp.id)
                            // 如果取消了 rootfs，且其它勾选项依赖 rootfs，则自动联动
                            if (comp.isRequired) {
                                val dependents = allComponents.filter { it.dependencies.contains(comp.id) }
                                selectedComponentIds.removeAll(dependents.map { it.id }.toSet())
                            }
                        }
                        populateComponentList()
                    }

                    card.setOnClickListener { toggleSelection() }
                    checkBox.setOnClickListener { toggleSelection() }
                    card.isEnabled = !BuildConfig.BUNDLED_ROOTFS
                    checkBox.isEnabled = !BuildConfig.BUNDLED_ROOTFS
                }

                componentsListContainer.addView(itemView)
            }
        }

        populateComponentList()

        startInstallButton.setOnClickListener {
            val toInstall = allComponents.filter { selectedComponentIds.contains(it.id) && !it.isInstalled(this) }
            if (toInstall.isEmpty()) {
                if (allComponents.any { selectedComponentIds.contains(it.id) }) {
                    Toast.makeText(this, R.string.components_all_ready, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, R.string.components_none_selected, Toast.LENGTH_SHORT).show()
                }
                return@setOnClickListener
            }
            val componentIds = toInstall.map { it.id }.toSet()
            lastInstalledComponents = toInstall.map { it.id }
            selectionContainer.visibility = View.GONE
            progressContainer.visibility = View.VISIBLE
            toolbar.navigationIcon = null
            ProotInstaller.start(applicationContext, componentIds)
        }

        skipInstallButton.setOnClickListener {
            openEditor()
        }

        setupRetryButton.setOnClickListener {
            if (lastInstalledComponents.isNotEmpty()) {
                ProotInstaller.start(applicationContext, lastInstalledComponents.toSet())
            }
        }

        setupContinueButton.setOnClickListener {
            openEditor()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (ProotInstaller.state.value.isWorking) return
                if (progressContainer.visibility == View.VISIBLE &&
                    ProotInstaller.state.value.status != ProotInstallState.Status.DONE) {
                    progressContainer.visibility = View.GONE
                    selectionContainer.visibility = View.VISIBLE
                    toolbar.setNavigationIcon(R.drawable.arrow_back_rounded)
                    populateComponentList()
                } else {
                    finish()
                }
            }
        })

        var displayedLogs: List<String>? = null
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ProotInstaller.state.collect { state ->
                    if (state.isWorking || state.status == ProotInstallState.Status.DONE || state.status == ProotInstallState.Status.ERROR) {
                        selectionContainer.visibility = View.GONE
                        progressContainer.visibility = View.VISIBLE
                    }
                    when (state.status) {
                        ProotInstallState.Status.DONE -> {
                            setupTitle.setText(R.string.proot_setup_done_title)
                            progressStatusIcon.setImageResource(R.drawable.check_circle_rounded)
                            setupRetryButton.visibility = View.GONE
                            setupContinueButton.visibility = View.VISIBLE
                        }
                        ProotInstallState.Status.ERROR -> {
                            setupTitle.setText(R.string.proot_setup_error_title)
                            progressStatusIcon.setImageResource(R.drawable.cancel_rounded)
                            setupRetryButton.visibility = View.VISIBLE
                            setupContinueButton.visibility = View.GONE
                        }
                        else -> {
                            setupTitle.setText(R.string.proot_setup_title)
                            progressStatusIcon.setImageResource(R.drawable.download_rounded)
                            setupRetryButton.visibility = View.GONE
                            setupContinueButton.visibility = View.GONE
                        }
                    }
                    setupSubtitle.text = state.message.ifBlank { getString(R.string.proot_install_preparing) }
                    setupProgress.setProgressCompat(state.progress, true)
                    setupProgressPercent.visibility = if (BuildConfig.BUNDLED_ROOTFS) View.VISIBLE else View.GONE
                    if (BuildConfig.BUNDLED_ROOTFS) {
                        setupProgressPercent.text = getString(R.string.proot_install_progress, state.progress)
                    }
                    setupProgressDetail.visibility = if (state.detail.isEmpty()) View.GONE else View.VISIBLE
                    setupProgressDetail.text = state.detail
                    setupProgressFile.visibility = if (state.currentFile.isEmpty()) View.GONE else View.VISIBLE
                    setupProgressFile.text = if (state.currentFile.isEmpty()) "" else {
                        getString(R.string.proot_offline_current_file, state.currentFile)
                    }
                    if (displayedLogs !== state.logs) {
                        displayedLogs = state.logs
                        setupLogs.text = if (state.logs.isEmpty()) {
                            getString(R.string.proot_setup_logs_empty)
                        } else {
                            state.logs.joinToString("\n")
                        }
                        setupLogsScroll.post { setupLogsScroll.fullScroll(View.FOCUS_DOWN) }
                    }
                }
            }
        }
    }

    private fun openEditor() {
        SettingsStore(this).hasShownInitialSetup = true
        startActivity(Intent(this, EditorActivity::class.java))
        finish()
    }
}
