package top.wsdx233.love2droid

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.launch

class SetupActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ProotRuntime.isEnvironmentReady(this)) {
            openEditor()
            return
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_setup)

        val root = findViewById<View>(R.id.setup_root)
        val topInset = findViewById<View>(R.id.setup_top_inset)
        val actions = findViewById<View>(R.id.setup_actions)
        val toolbar = findViewById<MaterialToolbar>(R.id.setup_toolbar)
        val progress = findViewById<LinearProgressIndicator>(R.id.setup_progress)
        val status = findViewById<TextView>(R.id.setup_status)
        val logs = findViewById<TextView>(R.id.setup_logs)
        val logScroll = findViewById<ScrollView>(R.id.setup_log_scroll)
        val retry = findViewById<MaterialButton>(R.id.setup_retry)
        val continueButton = findViewById<MaterialButton>(R.id.setup_continue)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            topInset.layoutParams = topInset.layoutParams.apply { height = systemBars.top }
            actions.setPadding(
                actions.paddingLeft,
                actions.paddingTop,
                actions.paddingRight,
                systemBars.bottom + resources.getDimensionPixelSize(R.dimen.setup_actions_bottom_padding),
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)

        retry.setOnClickListener { startInstall() }
        continueButton.setOnClickListener { openEditor() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!ProotInstaller.state.value.isWorking) finish()
            }
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ProotInstaller.state.collect { state ->
                    toolbar.title = when (state.status) {
                        ProotInstallState.Status.DONE -> getString(R.string.proot_setup_done_title)
                        ProotInstallState.Status.ERROR -> getString(R.string.proot_setup_error_title)
                        else -> getString(R.string.proot_setup_title)
                    }
                    progress.setProgressCompat(state.progress, true)
                    status.text = state.message.ifBlank { getString(R.string.proot_install_preparing) }
                    logs.text = if (state.logs.isEmpty()) {
                        getString(R.string.proot_setup_logs_empty)
                    } else {
                        state.logs.joinToString("\n")
                    }
                    retry.visibility = if (state.status == ProotInstallState.Status.ERROR) View.VISIBLE else View.GONE
                    continueButton.visibility = if (state.status == ProotInstallState.Status.DONE) View.VISIBLE else View.GONE
                    logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
                }
            }
        }

        if (!ProotInstaller.state.value.isWorking &&
            ProotInstaller.state.value.status != ProotInstallState.Status.ERROR
        ) {
            startInstall()
        }
    }

    private fun startInstall() {
        ProotInstaller.start(applicationContext)
    }

    private fun openEditor() {
        startActivity(Intent(this, EditorActivity::class.java))
        finish()
    }
}
