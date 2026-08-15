package com.silverbullet.kode.feature.threads.presentation

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbullet.kode.core.model.OrchestrationThreadShell
import com.silverbullet.kode.core.session.ConnectionState
import com.silverbullet.kode.core.session.EnvironmentSupervisor
import com.silverbullet.kode.feature.threads.domain.SyncStatus
import com.silverbullet.kode.feature.threads.domain.ThreadsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class ThreadListViewModel(
    repository: ThreadsRepository,
    supervisor: EnvironmentSupervisor,
) : ViewModel() {

    val uiState: StateFlow<ThreadListUiState> =
        combine(repository.shell, supervisor.state) { shell, connection ->
            ThreadListUiState(
                connection = connection,
                syncStatus = shell.status,
                rows = shell.visibleThreads.map { thread ->
                    val project = shell.projectFor(thread)
                    ThreadRow(
                        thread = thread,
                        // Precomputed here rather than in the row: it allocated
                        // a list and two strings per row per composition.
                        subtitle = listOfNotNull(project?.title, thread.branch)
                            .joinToString(" · ")
                            .ifEmpty { thread.updatedAt },
                    )
                },
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

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

@Immutable
data class ThreadListUiState(
    val connection: ConnectionState = ConnectionState.Unpaired,
    val syncStatus: SyncStatus = SyncStatus.Empty,
    val rows: List<ThreadRow> = emptyList(),
    val error: String? = null,
) {
    /**
     * Distinguishes "still loading" from "genuinely nothing here", so a
     * connected environment with no threads does not look like a hang.
     */
    val isLoading: Boolean
        get() = rows.isEmpty() && error == null &&
            syncStatus != SyncStatus.Live

    val isConnected: Boolean get() = connection is ConnectionState.Connected
}

@Immutable
data class ThreadRow(
    val thread: OrchestrationThreadShell,
    val subtitle: String,
)
