package top.wsdx233.love2droid

import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
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
    private val statusControls = LinearLayout(activity)
    private val initButton = MaterialButton(activity)
    private val commitMessage = EditText(activity)
    private val commitButton = MaterialButton(activity)
    private val stageAllButton = MaterialButton(activity)
    private val unstageAllButton = MaterialButton(activity)
    private val stagedDiffButton = MaterialButton(activity)
    private val unstagedDiffButton = MaterialButton(activity)
    private val statusAdapter = GitStatusAdapter(::showStatusDiff, ::stageEntry, ::unstageEntry)
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
            text = activity.getString(R.string.refresh)
            minWidth = 0
            insetTop = 0
            insetBottom = 0
            setOnClickListener { if (repositoryReady) loadSelectedTab() else prepareRepository() }
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
        content.addView(buildStatusControls())
        initButton.apply {
            text = activity.getString(R.string.git_initialize)
            setOnClickListener { initializeRepository() }
            visibility = View.GONE
        }
        content.addView(initButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply {
            setMargins(dp(20), dp(8), dp(20), dp(8))
        })
        message.apply {
            gravity = android.view.Gravity.CENTER
            setTextColor(MaterialColors.getColor(activity, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY))
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        content.addView(message, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        list.layoutManager = LinearLayoutManager(activity)
        content.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxSheetHeight()))
        return content
    }

    private fun buildStatusControls(): View {
        statusControls.orientation = LinearLayout.VERTICAL
        statusControls.setPadding(dp(16), dp(4), dp(16), dp(4))

        val commitRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        commitMessage.apply {
            hint = activity.getString(R.string.git_commit_message_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 1
            maxLines = 3
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        commitRow.addView(commitMessage, LinearLayout.LayoutParams(0, dp(52), 1f))
        commitButton.apply {
            text = activity.getString(R.string.git_commit)
            minWidth = 0
            insetTop = 0
            insetBottom = 0
            setOnClickListener { commit() }
        }
        commitRow.addView(commitButton, LinearLayout.LayoutParams(dp(92), dp(48)).apply { marginStart = dp(8) })
        statusControls.addView(commitRow)

        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        stageAllButton.apply {
            text = activity.getString(R.string.git_stage_all)
            minWidth = 0
            insetTop = 0
            insetBottom = 0
            setOnClickListener { stageAll() }
        }
        unstageAllButton.apply {
            text = activity.getString(R.string.git_unstage_all)
            minWidth = 0
            insetTop = 0
            insetBottom = 0
            setOnClickListener { unstageAll() }
        }
        stagedDiffButton.apply {
            text = activity.getString(R.string.git_staged_diff)
            minWidth = 0
            insetTop = 0
            insetBottom = 0
            setOnClickListener { showAllDiff(staged = true) }
        }
        unstagedDiffButton.apply {
            text = activity.getString(R.string.git_unstaged_diff)
            minWidth = 0
            insetTop = 0
            insetBottom = 0
            setOnClickListener { showAllDiff(staged = false) }
        }
        actions.addView(stageAllButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
        actions.addView(unstageAllButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
        actions.addView(stagedDiffButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
        actions.addView(unstagedDiffButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
        statusControls.addView(HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            addView(actions)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        return statusControls
    }

    private fun prepareRepository() {
        loadJob?.cancel()
        loadJob = scope.launch {
            showLoading(activity.getString(R.string.git_preparing))
            try {
                client.ensureInstalled(::showLoading)
                if (!client.isRepository(project.root)) {
                    repositoryReady = false
                    showRepositoryMissing()
                    return@launch
                }
                repositoryReady = true
                loadSelectedTabContents()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                showMessage(activity.getString(R.string.git_failed, error.rootMessage()))
            }
        }
    }

    private fun initializeRepository() {
        loadJob?.cancel()
        loadJob = scope.launch {
            showLoading(activity.getString(R.string.git_initializing))
            try {
                client.initialize(project.root)
                repositoryReady = true
                loadSelectedTabContents()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                showMessage(activity.getString(R.string.git_failed, error.rootMessage()))
            }
        }
    }

    private fun loadSelectedTab(cancelCurrent: Boolean = true) {
        if (cancelCurrent) loadJob?.cancel()
        loadJob = scope.launch { loadSelectedTabContents() }
    }

    private suspend fun loadSelectedTabContents() {
        showLoading(activity.getString(R.string.git_loading))
        try {
            when (selectedTab) {
                TAB_STATUS -> {
                    val entries = client.status(project.root)
                    list.adapter = statusAdapter
                    statusAdapter.submitEntries(entries)
                    updateStatusControls(entries)
                    statusControls.visibility = View.VISIBLE
                    showListOrMessage(entries.isEmpty(), R.string.git_clean)
                }
                TAB_HISTORY -> {
                    val entries = client.history(project.root)
                    list.adapter = historyAdapter
                    historyAdapter.submitEntries(entries)
                    statusControls.visibility = View.GONE
                    showListOrMessage(entries.isEmpty(), R.string.git_no_history)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            showMessage(activity.getString(R.string.git_failed, error.rootMessage()))
        }
    }

    private fun updateStatusControls(entries: List<GitStatusEntry>) {
        val stagedCount = entries.count(GitStatusEntry::isStaged)
        val changesCount = entries.count(GitStatusEntry::hasWorkTreeChanges)
        commitButton.isEnabled = stagedCount > 0
        stageAllButton.isEnabled = changesCount > 0
        unstageAllButton.isEnabled = stagedCount > 0
        stagedDiffButton.isEnabled = stagedCount > 0
        unstagedDiffButton.isEnabled = changesCount > 0
    }

    private fun stageEntry(entry: GitStatusEntry) = runMutation(activity.getString(R.string.git_staging)) {
        client.stage(project.root, entry.relativePath)
    }

    private fun unstageEntry(entry: GitStatusEntry) = runMutation(activity.getString(R.string.git_unstaging)) {
        client.unstage(project.root, entry.relativePath)
    }

    private fun stageAll() = runMutation(activity.getString(R.string.git_staging)) {
        client.stageAll(project.root)
    }

    private fun unstageAll() = runMutation(activity.getString(R.string.git_unstaging)) {
        client.unstageAll(project.root)
    }

    private fun commit() {
        commitMessage.error = null
        val value = commitMessage.text.toString().trim()
        if (value.isBlank()) {
            commitMessage.error = activity.getString(R.string.git_commit_message_required)
            return
        }
        runMutation(activity.getString(R.string.git_committing)) {
            client.commit(project.root, value)
            commitMessage.setText("")
        }
    }

    private fun runMutation(progressText: String, operation: suspend () -> Unit) {
        if (!repositoryReady) return
        loadJob?.cancel()
        loadJob = scope.launch {
            showLoading(progressText)
            try {
                operation()
                loadSelectedTabContents()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                showMessage(activity.getString(R.string.git_failed, error.rootMessage()))
            }
        }
    }

    private fun showStatusDiff(entry: GitStatusEntry, staged: Boolean) {
        showDiffSheet(
            title = activity.getString(
                if (staged) R.string.git_staged_file_diff_title else R.string.git_file_diff_title,
                entry.relativePath,
            ),
            emptyMessage = activity.getString(R.string.git_no_diff),
        ) { client.diff(project.root, entry.relativePath, staged) }
    }

    private fun showAllDiff(staged: Boolean) {
        if (!repositoryReady) return
        showDiffSheet(
            title = activity.getString(if (staged) R.string.git_staged_diff else R.string.git_unstaged_diff),
            emptyMessage = activity.getString(R.string.git_no_diff),
        ) { client.diff(project.root, staged = staged) }
    }

    private fun showCommit(entry: GitCommitEntry) {
        showDiffSheet(
            title = activity.getString(R.string.git_commit_title, entry.hash.take(8)),
            emptyMessage = activity.getString(R.string.git_no_commit_detail),
        ) { client.commitDetails(project.root, entry.hash) }
    }

    private fun showDiffSheet(
        title: String,
        emptyMessage: String,
        load: suspend () -> String,
    ) {
        val detailDialog = BottomSheetDialog(activity)
        val detailAdapter = GitDiffAdapter()
        val detailProgress = ProgressBar(activity)
        val detailMessage = TextView(activity).apply {
            gravity = android.view.Gravity.CENTER
            setTextColor(MaterialColors.getColor(activity, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY))
            setPadding(dp(24), dp(24), dp(24), dp(24))
            visibility = View.GONE
        }
        val detailList = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = detailAdapter
        }
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
        content.addView(detailProgress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)))
        content.addView(detailMessage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(detailList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxSheetHeight()))
        detailDialog.setContentView(content)
        detailDialog.setOnShowListener {
            detailDialog.behavior.skipCollapsed = true
            detailDialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
        val detailJob = scope.launch {
            try {
                val value = load()
                detailAdapter.submitText(value)
                val empty = detailAdapter.itemCount == 0
                detailList.visibility = if (empty) View.GONE else View.VISIBLE
                detailMessage.visibility = if (empty) View.VISIBLE else View.GONE
                if (empty) detailMessage.text = emptyMessage
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                detailList.visibility = View.GONE
                detailMessage.visibility = View.VISIBLE
                detailMessage.text = activity.getString(R.string.git_failed, error.rootMessage())
            } finally {
                detailProgress.visibility = View.GONE
            }
        }
        detailDialog.setOnDismissListener { detailJob.cancel() }
        detailDialog.show()
    }

    private fun showLoading(text: String) {
        progress.visibility = View.VISIBLE
        statusControls.visibility = View.GONE
        initButton.visibility = View.GONE
        list.visibility = View.GONE
        message.visibility = View.VISIBLE
        message.text = text
    }

    private fun showRepositoryMissing() {
        progress.visibility = View.GONE
        statusControls.visibility = View.GONE
        list.visibility = View.GONE
        message.visibility = View.VISIBLE
        message.text = activity.getString(R.string.git_not_repository)
        initButton.visibility = View.VISIBLE
        initButton.isEnabled = true
    }

    private fun showListOrMessage(empty: Boolean, messageRes: Int) {
        progress.visibility = View.GONE
        list.visibility = if (empty) View.GONE else View.VISIBLE
        message.visibility = if (empty) View.VISIBLE else View.GONE
        if (empty) message.setText(messageRes)
        initButton.visibility = View.GONE
    }

    private fun showMessage(value: String) {
        progress.visibility = View.GONE
        statusControls.visibility = View.GONE
        initButton.visibility = View.GONE
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
        (activity.resources.displayMetrics.heightPixels * 0.72f).toInt(),
        dp(680),
    )

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    private companion object {
        const val TAB_STATUS = 0
        const val TAB_HISTORY = 1
    }
}
