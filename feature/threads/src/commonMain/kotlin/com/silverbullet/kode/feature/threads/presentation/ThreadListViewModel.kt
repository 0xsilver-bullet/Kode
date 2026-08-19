package com.silverbullet.kode.feature.threads.presentation

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbullet.kode.core.common.TimeProvider
import com.silverbullet.kode.core.model.EnvironmentId
import com.silverbullet.kode.core.model.OrchestrationThreadShell
import com.silverbullet.kode.core.model.ThreadId
import com.silverbullet.kode.feature.threads.domain.EnvironmentShell
import com.silverbullet.kode.feature.threads.domain.SettledOverride
import com.silverbullet.kode.feature.threads.domain.SyncStatus
import com.silverbullet.kode.feature.threads.domain.isEffectivelySettled
import com.silverbullet.kode.feature.threads.domain.isSettleable
import com.silverbullet.kode.feature.threads.domain.ThreadsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The merged inbox: every environment's threads in one list, newest activity
 * first, as T3 Code mobile renders its home list across all connections.
 */
class ThreadListViewModel(
    private val repository: ThreadsRepository,
    private val timeProvider: TimeProvider,
) : ViewModel() {

    private val settledExpanded = MutableStateFlow(false)
    private val actionError = MutableStateFlow<String?>(null)

    /**
     * Threads with a settle in flight.
     *
     * Not a `StateFlow`: nothing renders it, and making it observable would
     * recompose the list twice per tap for no visible change. It exists only so
     * a double tap — or a tap on a row the list has re-emitted underneath the
     * finger — cannot dispatch the command twice.
     */
    private val settling = mutableSetOf<String>()

    val uiState: StateFlow<ThreadListUiState> =
        combine(repository.shells, settledExpanded, actionError) { shells, expanded, error ->
            // One `now` for the whole partition, so two threads on either side
            // of the auto-settle boundary cannot be judged against different
            // clocks within a single list.
            val now = timeProvider.nowIso()
            // The environment label only earns a place in the subtitle once it
            // disambiguates anything.
            val multiEnvironment = shells.size > 1

            val merged = shells
                .flatMap { environment ->
                    environment.shell.visibleThreads.map { environment to it }
                }
                .sortedByDescending { (_, thread) ->
                    thread.latestUserMessageAt ?: thread.updatedAt
                }

            val active = ArrayList<Pair<EnvironmentShell, OrchestrationThreadShell>>(merged.size)
            val settled = ArrayList<Pair<EnvironmentShell, OrchestrationThreadShell>>()
            for (entry in merged) {
                if (entry.second.isEffectivelySettled(now)) settled += entry else active += entry
            }

            fun row(entry: Pair<EnvironmentShell, OrchestrationThreadShell>): ThreadRow {
                val (environment, thread) = entry
                val project = environment.shell.projectFor(thread)
                return ThreadRow(
                    environmentId = environment.environmentId,
                    thread = thread,
                    // Precomputed here rather than in the row: it allocated
                    // a list and two strings per row per composition.
                    subtitle = listOfNotNull(
                        project?.title,
                        thread.branch,
                        environment.label.takeIf { multiEnvironment },
                    )
                        .joinToString(" · ")
                        .ifEmpty { thread.updatedAt },
                    // Also precomputed: the row is the wrong place to be
                    // reading capabilities and re-deriving the same predicate
                    // on every scroll.
                    canSettle = environment.capabilities.threadSettlement &&
                        thread.settledOverride != SettledOverride.SETTLED &&
                        thread.isSettleable(now),
                )
            }

            val items = buildList {
                active.forEach { add(ThreadListItem.Thread(row(it))) }
                if (settled.isNotEmpty()) {
                    add(ThreadListItem.SettledShelf(settled.size, expanded))
                    if (expanded) {
                        settled.forEach { add(ThreadListItem.Thread(row(it))) }
                    }
                }
            }

            ThreadListUiState(
                syncStatus = aggregateStatus(shells),
                items = items,
                activeCount = active.size,
                settledCount = settled.size,
                error = shells.firstNotNullOfOrNull { it.shell.error },
                actionError = error,
            )
        }.stateIn(
            scope = viewModelScope,
            // The subscription is dropped shortly after the screen goes away and
            // re-established on return, rather than held open for the process
            // lifetime.
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = ThreadListUiState(),
        )

    /** The shelf starts collapsed: settled threads are history, not the inbox. */
    fun toggleSettledShelf() {
        settledExpanded.value = !settledExpanded.value
    }

    /**
     * Files a thread away as finished business.
     *
     * Nothing is held optimistically. A settled thread stays in the live shell
     * stream — settled is not archived — so the server's echo re-partitions the
     * list within a round trip, and inventing a local hold would only add a
     * state that could disagree with it.
     *
     * The command's preconditions are already checked when the row is built
     * ([ThreadRow.canSettle]), so a failure here means they changed under us or
     * the connection went away; either way it is worth saying out loud rather
     * than swallowing.
     */
    fun settleThread(environmentId: EnvironmentId, threadId: ThreadId) {
        val key = environmentId.value + ":" + threadId.value
        if (!settling.add(key)) return

        viewModelScope.launch {
            actionError.value = null
            val failure = repository.settleThread(environmentId, threadId).exceptionOrNull()
            settling.remove(key)
            if (failure != null) {
                actionError.value = failure.message ?: "Could not settle thread."
            }
        }
    }

    fun dismissActionError() {
        actionError.value = null
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L

        /**
         * The most optimistic honest summary: live only when every environment
         * has caught up, loading while any is still synchronizing.
         */
        fun aggregateStatus(shells: List<EnvironmentShell>): SyncStatus = when {
            shells.isNotEmpty() && shells.all { it.shell.status == SyncStatus.Live } ->
                SyncStatus.Live

            shells.any { it.shell.status == SyncStatus.Synchronizing } ->
                SyncStatus.Synchronizing

            else -> SyncStatus.Empty
        }
    }
}

@Immutable
data class ThreadListUiState(
    val syncStatus: SyncStatus = SyncStatus.Empty,
    val items: List<ThreadListItem> = emptyList(),
    val activeCount: Int = 0,
    val settledCount: Int = 0,
    val error: String? = null,
    /** A rejected row action, distinct from [error]'s synchronization failure. */
    val actionError: String? = null,
) {
    /**
     * Distinguishes "still loading" from "genuinely nothing here", so a
     * connected environment with no threads does not look like a hang.
     */
    val isLoading: Boolean
        get() = items.isEmpty() && error == null && syncStatus != SyncStatus.Live

    val isEmpty: Boolean get() = items.isEmpty()
}

/** A row in the thread list: a thread, or the settled shelf header. */
@Immutable
sealed interface ThreadListItem {
    val key: String

    @Immutable
    data class Thread(val row: ThreadRow) : ThreadListItem {
        // Keyed by environment too: thread ids are only unique per server.
        override val key: String =
            "thread:" + row.environmentId.value + ":" + row.thread.id.value
    }

    @Immutable
    data class SettledShelf(val count: Int, val expanded: Boolean) : ThreadListItem {
        override val key: String = "settled-shelf"
    }
}

@Immutable
data class ThreadRow(
    val environmentId: EnvironmentId,
    val thread: OrchestrationThreadShell,
    val subtitle: String,
    /**
     * Whether the swipe action should be offered at all: the server supports
     * settling, the thread is not already pinned settled, and none of the
     * decider's blockers apply. Offering a button that can only fail would be
     * worse than not offering one.
     */
    val canSettle: Boolean = false,
)
