package com.silverbullet.kode.feature.threads.presentation

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbullet.kode.core.common.TimeProvider
import com.silverbullet.kode.core.model.OrchestrationThreadShell
import com.silverbullet.kode.core.session.ConnectionState
import com.silverbullet.kode.core.session.EnvironmentSupervisor
import com.silverbullet.kode.feature.threads.domain.SyncStatus
import com.silverbullet.kode.feature.threads.domain.partitionBySettlement
import com.silverbullet.kode.feature.threads.domain.ThreadsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class ThreadListViewModel(
    repository: ThreadsRepository,
    supervisor: EnvironmentSupervisor,
    private val timeProvider: TimeProvider,
) : ViewModel() {

    private val settledExpanded = MutableStateFlow(false)

    val uiState: StateFlow<ThreadListUiState> =
        combine(repository.shell, supervisor.state, settledExpanded) { shell, connection, expanded ->
            // One `now` for the whole partition, so two threads on either side
            // of the auto-settle boundary cannot be judged against different
            // clocks within a single list.
            val now = timeProvider.nowIso()
            val partition = shell.visibleThreads.partitionBySettlement(now)

            fun row(thread: OrchestrationThreadShell): ThreadRow {
                val project = shell.projectFor(thread)
                return ThreadRow(
                    thread = thread,
                    // Precomputed here rather than in the row: it allocated
                    // a list and two strings per row per composition.
                    subtitle = listOfNotNull(project?.title, thread.branch)
                        .joinToString(" · ")
                        .ifEmpty { thread.updatedAt },
                )
            }

            val items = buildList {
                partition.active.forEach { add(ThreadListItem.Thread(row(it))) }
                if (partition.settled.isNotEmpty()) {
                    add(ThreadListItem.SettledShelf(partition.settled.size, expanded))
                    if (expanded) {
                        partition.settled.forEach { add(ThreadListItem.Thread(row(it))) }
                    }
                }
            }

            ThreadListUiState(
                connection = connection,
                syncStatus = shell.status,
                items = items,
                activeCount = partition.active.size,
                settledCount = partition.settled.size,
                error = shell.error,
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

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

@Immutable
data class ThreadListUiState(
    val connection: ConnectionState = ConnectionState.Unpaired,
    val syncStatus: SyncStatus = SyncStatus.Empty,
    val items: List<ThreadListItem> = emptyList(),
    val activeCount: Int = 0,
    val settledCount: Int = 0,
    val error: String? = null,
) {
    /**
     * Distinguishes "still loading" from "genuinely nothing here", so a
     * connected environment with no threads does not look like a hang.
     */
    val isLoading: Boolean
        get() = items.isEmpty() && error == null && syncStatus != SyncStatus.Live

    val isEmpty: Boolean get() = items.isEmpty()

    val isConnected: Boolean get() = connection is ConnectionState.Connected
}

/** A row in the thread list: a thread, or the settled shelf header. */
@Immutable
sealed interface ThreadListItem {
    val key: String

    @Immutable
    data class Thread(val row: ThreadRow) : ThreadListItem {
        override val key: String = "thread:" + row.thread.id.value
    }

    @Immutable
    data class SettledShelf(val count: Int, val expanded: Boolean) : ThreadListItem {
        override val key: String = "settled-shelf"
    }
}

@Immutable
data class ThreadRow(
    val thread: OrchestrationThreadShell,
    val subtitle: String,
)
