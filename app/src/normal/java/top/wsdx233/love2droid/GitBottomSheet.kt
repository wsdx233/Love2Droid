package top.wsdx233.love2droid

import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class GitBottomSheet(
    private val activity: AppCompatActivity,
    private val scope: CoroutineScope,
    private val project: Project,
) {
    private val client = GitClient(activity)
    private val dialog = BottomSheetDialog(activity)
    private val progress = ProgressBar(activity)
    private val message = TextView(activity)
    private val list = RecyclerView(activity)
    private val statusAdapter = GitStatusAdapter(::showStatusDiff)
    private val historyAdapter = GitHistoryAdapter(::showCommit)
    private var selectedTab = TAB_STATUS
    private var repositoryReady = false
    private var loadJob: Job? = null

    fun show() {
        dialog.setContentView(buildContent())
        dialog.setOnShowListener {
            dialog.behavior.skipCollapsed = true
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
        dialog.setOnDismissListener { loadJob?.cancel() }
        dialog.show()
        prepareRepository()
    }

    private fun buildContent(): View {
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(4), dp(12), dp(4))
        }
        header.addView(TextView(activity).apply {
            text = activity.getString(R.string.git_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(MaterialButton(activity).apply {
            text = activity.getString(R.string.git_unstaged_diff)
            setOnClickListener { showAllUnstagedDiff() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
        content.addView(header)

        val tabs = TabLayout(activity).apply {
            addTab(newTab().setText(R.string.git_status_tab))
            addTab(newTab().setText(R.string.git_history_tab))
            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    selectedTab = tab.position
                    if (repositoryReady) loadSelectedTab()
                }

                override fun onTabUnselected(tab: TabLayout.Tab) = Unit
                override fun onTabReselected(tab: TabLayout.Tab) = Unit
            })
        }
        content.addView(tabs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        progress.visibility = View.GONE
        content.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)))
        message.apply {
            gravity = android.view.Gravity.CENTER
            setTextColor(MaterialColors.getColor(activity, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY))
            setPadding(dp(24), dp(28), dp(24), dp(28))
        }
        content.addView(message, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        list.layoutManager = LinearLayoutManager(activity)
        content.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxSheetHeight()))
        return content
    }

    private fun prepareRepository() {
        loadJob?.cancel()
        loadJob = scope.launch {
            showLoading(activity.getString(R.string.git_preparing))
            try {
                client.ensureInstalled(::showLoading)
                if (!client.isRepository(project.root)) {
                    repositoryReady = false
                    showMessage(R.string.git_not_repository)
                    return@launch
                }
                repositoryReady = true
                loadSelectedTab(cancelCurrent = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                showMessage(activity.getString(R.string.git_failed, error.rootMessage()))
            }
        }
    }

    private fun loadSelectedTab(cancelCurrent: Boolean = true) {
        if (cancelCurrent) loadJob?.cancel()
        loadJob = scope.launch {
            showLoading(activity.getString(R.string.git_loading))
            try {
                when (selectedTab) {
                    TAB_STATUS -> {
                        val entries = client.status(project.root)
                        list.adapter = statusAdapter
                        statusAdapter.submitEntries(entries)
                        showListOrMessage(entries.isEmpty(), R.string.git_clean)
                    }
                    TAB_HISTORY -> {
                        val entries = client.history(project.root)
                        list.adapter = historyAdapter
                        historyAdapter.submitEntries(entries)
                        showListOrMessage(entries.isEmpty(), R.string.git_no_history)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                showMessage(activity.getString(R.string.git_failed, error.rootMessage()))
            }
        }
    }

    private fun showStatusDiff(entry: GitStatusEntry) {
        showTextSheet(
            title = activity.getString(R.string.git_file_diff_title, entry.relativePath),
            emptyMessage = activity.getString(R.string.git_no_unstaged_diff),
        ) { client.unstagedDiff(project.root, entry.relativePath) }
    }

    private fun showAllUnstagedDiff() {
        if (!repositoryReady) return
        showTextSheet(
            title = activity.getString(R.string.git_unstaged_diff),
            emptyMessage = activity.getString(R.string.git_no_unstaged_diff),
        ) { client.unstagedDiff(project.root) }
    }

    private fun showCommit(entry: GitCommitEntry) {
        showTextSheet(
            title = activity.getString(R.string.git_commit_title, entry.hash.take(8)),
            emptyMessage = activity.getString(R.string.git_no_commit_detail),
        ) { client.commitDetails(project.root, entry.hash) }
    }

    private fun showTextSheet(
        title: String,
        emptyMessage: String,
        load: suspend () -> String,
    ) {
        val detailDialog = BottomSheetDialog(activity)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        content.addView(TextView(activity).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(20), dp(8), dp(20), dp(12))
        })
        val detailProgress = ProgressBar(activity)
        content.addView(detailProgress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)))
        val text = TextView(activity).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextIsSelectable(true)
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }
        content.addView(ScrollView(activity).apply { addView(text) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxSheetHeight()))
        detailDialog.setContentView(content)
        detailDialog.setOnShowListener {
            detailDialog.behavior.skipCollapsed = true
            detailDialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
        val detailJob = scope.launch {
            try {
                val value = load()
                text.text = value.ifBlank { emptyMessage }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                text.text = activity.getString(R.string.git_failed, error.rootMessage())
            } finally {
                detailProgress.visibility = View.GONE
            }
        }
        detailDialog.setOnDismissListener { detailJob.cancel() }
        detailDialog.show()
    }

    private fun showLoading(text: String) {
        progress.visibility = View.VISIBLE
        list.visibility = View.GONE
        message.visibility = View.VISIBLE
        message.text = text
    }

    private fun showListOrMessage(empty: Boolean, messageRes: Int) {
        progress.visibility = View.GONE
        list.visibility = if (empty) View.GONE else View.VISIBLE
        message.visibility = if (empty) View.VISIBLE else View.GONE
        if (empty) message.setText(messageRes)
    }

    private fun showMessage(messageRes: Int) = showMessage(activity.getString(messageRes))

    private fun showMessage(value: String) {
        progress.visibility = View.GONE
        list.visibility = View.GONE
        message.visibility = View.VISIBLE
        message.text = value
    }

    private fun Throwable.rootMessage(): String {
        var value: Throwable = this
        while (value.cause != null && value.cause !== value) value = checkNotNull(value.cause)
        return value.message ?: value.javaClass.simpleName
    }

    private fun maxSheetHeight(): Int = minOf(
        (activity.resources.displayMetrics.heightPixels * 0.65f).toInt(),
        dp(600),
    )

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    private companion object {
        const val TAB_STATUS = 0
        const val TAB_HISTORY = 1
    }
}
