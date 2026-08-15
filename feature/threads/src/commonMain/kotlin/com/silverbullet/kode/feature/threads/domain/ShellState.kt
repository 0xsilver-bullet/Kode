package com.silverbullet.kode.feature.threads.domain

import com.silverbullet.kode.core.model.OrchestrationProjectShell
import com.silverbullet.kode.core.model.OrchestrationThreadShell
import com.silverbullet.kode.core.model.ProjectId
import com.silverbullet.kode.core.model.ShellStreamItem
import com.silverbullet.kode.core.model.ThreadId

/**
 * The projects/threads read model, folded from the shell subscription.
 *
 * `SyncStatus` is kept explicit and separate from connection health, matching
 * `docs/internals/connection-runtime.md`: a healthy socket with a failed shell
 * subscription is "connected with a synchronization error", never a reconnect
 * that is not actually scheduled.
 */
data class ShellState(
    val status: SyncStatus = SyncStatus.Empty,
    val projects: Map<ProjectId, OrchestrationProjectShell> = emptyMap(),
    val threads: Map<ThreadId, OrchestrationThreadShell> = emptyMap(),
    val error: String? = null,
) {
    /**
     * Newest activity first, archived threads excluded.
     *
     * Ordering prefers the latest user message and falls back to `updatedAt`,
     * so a thread the agent is still working in does not jump around while its
     * assistant message streams.
     */
    val visibleThreads: List<OrchestrationThreadShell>
        get() = threads.values
            .filterNot { it.isArchived }
            .sortedByDescending { it.latestUserMessageAt ?: it.updatedAt }

    fun projectFor(thread: OrchestrationThreadShell): OrchestrationProjectShell? =
        projects[thread.projectId]
}

/**
 * Applies one shell stream item.
 *
 * Pure, so the ordering and replacement rules are directly testable without a
 * socket. Events replace whole objects by id — the shell stream is coarse by
 * design, which is why no per-field merging is needed.
 */
fun ShellState.reduce(item: ShellStreamItem): ShellState = when (item) {
    is ShellStreamItem.Snapshot -> copy(
        // A snapshot is authoritative: replace rather than merge, so entries
        // deleted while we were away do not survive.
        projects = item.snapshot.projects.associateBy { it.id },
        threads = item.snapshot.threads.associateBy { it.id },
        status = SyncStatus.Synchronizing,
        error = null,
    )

    ShellStreamItem.Synchronized -> copy(status = SyncStatus.Live)

    is ShellStreamItem.ThreadUpserted -> copy(
        threads = threads + (item.thread.id to item.thread),
        // Live events only arrive after catch-up completes.
        status = SyncStatus.Live,
    )

    is ShellStreamItem.ThreadRemoved -> copy(
        threads = threads - item.threadId,
        status = SyncStatus.Live,
    )

    is ShellStreamItem.ProjectUpserted -> copy(
        projects = projects + (item.project.id to item.project),
        status = SyncStatus.Live,
    )

    is ShellStreamItem.ProjectRemoved -> copy(
        projects = projects - item.projectId,
        status = SyncStatus.Live,
    )

    is ShellStreamItem.Unsupported -> this
}

/**
 * Data synchronization state, tracked separately from connection health.
 *
 * A healthy socket with a failed subscription is "connected with a
 * synchronization error", never a reconnect that is not actually scheduled.
 */
enum class SyncStatus { Empty, Synchronizing, Live }
