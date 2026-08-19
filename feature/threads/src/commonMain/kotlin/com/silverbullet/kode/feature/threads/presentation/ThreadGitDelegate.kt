package com.silverbullet.kode.feature.threads.presentation

import androidx.compose.runtime.Immutable
import com.silverbullet.kode.core.common.DispatcherProvider
import com.silverbullet.kode.core.common.IdGenerator
import com.silverbullet.kode.core.model.EnvironmentId
import com.silverbullet.kode.core.model.GitActionProgressEvent
import com.silverbullet.kode.core.model.GitActionToastCta
import com.silverbullet.kode.core.model.GitRunStackedActionInput
import com.silverbullet.kode.core.model.GitRunStackedActionResult
import com.silverbullet.kode.core.model.ThreadId
import com.silverbullet.kode.core.model.VcsPullStatus
import com.silverbullet.kode.core.model.VcsStatus
import com.silverbullet.kode.feature.threads.domain.git.GitRepository
import com.silverbullet.kode.feature.threads.domain.git.gitFailureMessage
import com.silverbullet.kode.feature.threads.domain.git.resolveAutoFeatureBranchName
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The result toast shown after a git action settles. */
@Immutable
data class GitActionNotice(
    val success: Boolean,
    val title: String,
    val description: String? = null,
    /** Set when the action produced/found a PR the user can open. */
    val prUrl: String? = null,
)

/**
 * The progress banner's state: a label while an action runs, then a notice.
 * Mirrors t3code's `useGitActionProgress` phases (`running`/`success`/`error`).
 */
@Immutable
data class GitActionUiState(
    val runningLabel: String? = null,
    /** The last hook output line, shown under the label while running. */
    val runningDetail: String? = null,
    val notice: GitActionNotice? = null,
) {
    val isRunning: Boolean get() = runningLabel != null
    val isVisible: Boolean get() = isRunning || notice != null
}

/**
 * The git slice of the thread screen: folded `subscribeVcsStatus` state plus
 * the stacked-action runner. Owned by [ThreadDetailViewModel] rather than being
 * its own ViewModel so the sheet, the top bar and the banner all read the one
 * instance the screen already has — and so [projectDir] is not derived twice
 * from a second thread subscription.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadGitDelegate(
    private val scope: CoroutineScope,
    private val environmentId: EnvironmentId,
    private val threadId: ThreadId,
    private val gitRepository: GitRepository,
    private val idGenerator: IdGenerator,
    private val projectDir: StateFlow<String?>,
    /** The thread's worktree path right now — for re-pointing after a branch move. */
    private val worktreePath: () -> String?,
    dispatchers: DispatcherProvider,
) {

    /**
     * The folded git status for the thread's directory. Subscribed only while
     * something collects it (the sheet, the commit pane), so an idle thread
     * screen keeps no VCS stream open — matching how t3code's status atom
     * idles out.
     */
    val status: StateFlow<VcsStatus?> = projectDir
        .flatMapLatest { dir ->
            if (dir == null) flowOf(null) else gitRepository.vcsStatus(environmentId, dir)
        }
        .flowOn(dispatchers.default)
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = null,
        )

    private val _action = MutableStateFlow(GitActionUiState())
    val action: StateFlow<GitActionUiState> = _action.asStateFlow()

    private var dismissJob: Job? = null

    /** Quiet status refresh; failures are ignored, the stream stays truthful. */
    fun refreshStatus() {
        val cwd = projectDir.value ?: return
        scope.launch { gitRepository.refreshStatus(environmentId, cwd) }
    }

    fun dismissNotice() {
        dismissJob?.cancel()
        _action.value = _action.value.copy(notice = null)
    }

    /**
     * Runs one stacked action (commit / push / create_pr / …), reducing its
     * progress stream into the banner and finishing with a server-built toast.
     */
    fun runAction(
        action: String,
        commitMessage: String? = null,
        featureBranch: Boolean = false,
        filePaths: List<String>? = null,
    ) {
        if (_action.value.isRunning) return
        val cwd = projectDir.value ?: return
        scope.launch {
            executeStackedAction(cwd, action, commitMessage, featureBranch, filePaths)
        }
    }

    /**
     * The "Feature branch & continue" path for a pure push/create_pr: create
     * and switch to an auto-named `feature/…` branch, re-point the thread, then
     * run the action there — t3code's `movePendingActionToFeatureBranch`.
     * (A confirm that *includes* a commit uses `featureBranch = true` on the
     * stacked action instead; the server owns branch creation in that case.)
     */
    fun runActionOnNewFeatureBranch(action: String) {
        if (_action.value.isRunning) return
        val cwd = projectDir.value ?: return
        scope.launch {
            setRunning("Creating branch")
            val existing = gitRepository.localBranchNames(environmentId, cwd)
                .getOrElse { emptyList() }
            val created = gitRepository.createAndSwitchBranch(
                environmentId = environmentId,
                cwd = cwd,
                refName = resolveAutoFeatureBranchName(existing),
            )
            created.fold(
                onSuccess = { branchName ->
                    gitRepository.updateThreadBranch(
                        environmentId = environmentId,
                        threadId = threadId,
                        branch = branchName,
                        worktreePath = worktreePath(),
                    )
                    executeStackedAction(cwd, action, null, featureBranch = false, filePaths = null)
                },
                onFailure = { failure ->
                    finishWith(
                        GitActionNotice(
                            success = false,
                            title = "Git action failed",
                            description = gitFailureMessage(failure),
                        ),
                    )
                },
            )
        }
    }

    /** The conditional "Pull latest" row — `onPullSelectedThreadBranch`. */
    fun pullLatest() {
        if (_action.value.isRunning) return
        val cwd = projectDir.value ?: return
        scope.launch {
            setRunning("Pulling latest changes")
            gitRepository.pull(environmentId, cwd).fold(
                onSuccess = { result ->
                    gitRepository.refreshStatus(environmentId, cwd)
                    finishWith(
                        GitActionNotice(
                            success = true,
                            title = if (result.status == VcsPullStatus.SKIPPED_UP_TO_DATE) {
                                "Already up to date"
                            } else {
                                "Pulled latest on ${result.refName}"
                            },
                        ),
                    )
                },
                onFailure = { failure ->
                    finishWith(
                        GitActionNotice(
                            success = false,
                            title = "Git action failed",
                            description = gitFailureMessage(failure),
                        ),
                    )
                },
            )
        }
    }

    // ------------------------------------------------------------------ internals

    private suspend fun executeStackedAction(
        cwd: String,
        action: String,
        commitMessage: String?,
        featureBranch: Boolean,
        filePaths: List<String>?,
    ) {
        setRunning("Running source control action")
        val actionId = idGenerator.newId()
        val input = GitRunStackedActionInput(
            actionId = actionId,
            cwd = cwd,
            action = action,
            commitMessage = commitMessage?.trim()?.takeIf { it.isNotEmpty() },
            featureBranch = featureBranch.takeIf { it },
            filePaths = filePaths?.takeIf { it.isNotEmpty() },
        )

        var result: GitRunStackedActionResult? = null
        var failureMessage: String? = null
        try {
            gitRepository.runStackedAction(environmentId, input).collect { event ->
                // Progress from another in-flight action on the same socket is
                // not ours to render — the same guard as t3code's
                // `normalizeVcsActionProgressEvent`.
                if (event.actionId != actionId) return@collect
                when (event) {
                    is GitActionProgressEvent.PhaseStarted -> updateRunning(label = event.label)
                    is GitActionProgressEvent.HookStarted ->
                        updateRunning(label = "Running ${event.hookName}...")

                    is GitActionProgressEvent.HookOutput ->
                        updateRunning(detail = event.text.lineSequence().lastOrNull { it.isNotBlank() })

                    is GitActionProgressEvent.ActionFinished -> result = event.result
                    is GitActionProgressEvent.ActionFailed -> failureMessage = event.message
                    else -> Unit
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            failureMessage = gitFailureMessage(failure)
        }

        val finished = result
        when {
            finished != null -> {
                // A branch the server created must be adopted as the thread's
                // branch; otherwise a quiet refresh keeps the sheet truthful.
                val createdBranch = finished.branch.name
                    .takeIf { finished.branch.status == "created" }
                if (createdBranch != null) {
                    gitRepository.updateThreadBranch(
                        environmentId = environmentId,
                        threadId = threadId,
                        branch = createdBranch,
                        worktreePath = worktreePath(),
                    )
                }
                gitRepository.refreshStatus(environmentId, cwd)
                finishWith(
                    GitActionNotice(
                        success = true,
                        title = finished.toast.title,
                        description = finished.toast.description,
                        prUrl = finished.toast.cta.url
                            .takeIf { finished.toast.cta.kind == GitActionToastCta.KIND_OPEN_PR },
                    ),
                )
            }

            else -> finishWith(
                GitActionNotice(
                    success = false,
                    title = "Git action failed",
                    description = failureMessage ?: "The action did not complete.",
                ),
            )
        }
    }

    private fun setRunning(label: String) {
        dismissJob?.cancel()
        _action.value = GitActionUiState(runningLabel = label)
    }

    private fun updateRunning(label: String? = null, detail: String? = null) {
        val current = _action.value
        _action.value = current.copy(
            runningLabel = label ?: current.runningLabel,
            runningDetail = detail ?: current.runningDetail,
        )
    }

    private fun finishWith(notice: GitActionNotice) {
        _action.value = GitActionUiState(notice = notice)
        dismissJob?.cancel()
        dismissJob = scope.launch {
            delay(RESULT_DISMISS_MILLIS)
            _action.value = _action.value.copy(notice = null)
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L

        /** Same auto-dismiss as t3code's `RESULT_DISMISS_MS`. */
        const val RESULT_DISMISS_MILLIS = 5_000L
    }
}
