package com.silverbullet.kode.feature.threads.domain

import androidx.compose.runtime.Immutable
import com.silverbullet.kode.core.model.OrchestrationEvent
import com.silverbullet.kode.core.model.OrchestrationMessage
import com.silverbullet.kode.core.model.OrchestrationSession
import com.silverbullet.kode.core.model.OrchestrationThread
import com.silverbullet.kode.core.model.ThreadStreamItem

/**
 * One thread's timeline, folded from the thread subscription.
 *
 * Holds only raw, time-ordered data. The *presented* feed — turn folds,
 * collapsed tool runs — is derived in the view model, off the main thread,
 * because it depends on which rows the user has expanded.
 */
@Immutable
data class ThreadDetailState(
    val status: SyncStatus = SyncStatus.Empty,
    val thread: OrchestrationThread? = null,
    val messages: List<OrchestrationMessage> = emptyList(),
    val activities: List<ActivityPresentation> = emptyList(),
    val session: OrchestrationSession? = null,
    /** Open user-input requests, keyed by request id. */
    val pendingUserInputs: Map<String, PendingUserInput> = emptyMap(),
    /** Open approval requests, keyed by request id. */
    val pendingApprovals: Map<String, PendingApproval> = emptyMap(),
    val error: String? = null,
) {
    /** Whether the agent is working, which drives the working indicator. */
    val isBusy: Boolean get() = session?.isBusy == true

    /** The request to answer first: the oldest one still open. */
    val activeUserInput: PendingUserInput?
        get() = pendingUserInputs.values.minByOrNull { it.createdAt }

    /**
     * The approval to decide first.
     *
     * Approvals take precedence over questions in the UI: they gate one concrete
     * action the agent is mid-way through, and clearing one is a single tap.
     */
    val activeApproval: PendingApproval?
        get() = pendingApprovals.values.minByOrNull { it.createdAt }
}

/**
 * Applies one thread stream item.
 *
 * The important case is [OrchestrationEvent.MessageSent]: the server re-sends a
 * streaming assistant message with the **full accumulated text** on every
 * delta, so this upserts by message id rather than appending. Treating it as an
 * append would duplicate the reply on screen, one copy per token batch.
 */
fun ThreadDetailState.reduce(item: ThreadStreamItem): ThreadDetailState = when (item) {
    is ThreadStreamItem.Snapshot -> {
        val thread = item.snapshot.thread
        copy(
            thread = thread,
            messages = thread.messages,
            activities = thread.activities.filter { it.isRenderable() }.map { it.toPresentation() },
            session = thread.session,
            // Derived from the raw activities: `toPresentation` drops the
            // payload, and the questions live there.
            pendingUserInputs = derivePendingUserInputs(thread.activities),
            pendingApprovals = derivePendingApprovals(thread.activities),
            status = SyncStatus.Synchronizing,
            error = null,
        )
    }

    ThreadStreamItem.Synchronized -> copy(status = SyncStatus.Live)

    is ThreadStreamItem.Event -> reduce(item.event).copy(status = SyncStatus.Live)

    is ThreadStreamItem.Unsupported -> this
}

private fun ThreadDetailState.reduce(event: OrchestrationEvent): ThreadDetailState = when (event) {
    is OrchestrationEvent.MessageSent -> upsertMessage(event.message)

    is OrchestrationEvent.ActivityAppended -> {
        val activity = event.activity
        // Request tracking is independent of whether the row renders: a
        // resolution may arrive as an activity we never display.
        val pending = pendingUserInputs.applyUserInputActivity(activity)
        val approvals = pendingApprovals.applyApprovalActivity(activity)
        when {
            // A replay across a resubscribe must not duplicate the row.
            activities.any { it.id == activity.id } ->
                copy(pendingUserInputs = pending, pendingApprovals = approvals)

            !activity.isRenderable() ->
                copy(pendingUserInputs = pending, pendingApprovals = approvals)

            else -> copy(
                activities = activities + activity.toPresentation(),
                pendingUserInputs = pending,
                pendingApprovals = approvals,
            )
        }
    }

    is OrchestrationEvent.SessionSet -> copy(session = event.session)

    // Diff results are not rendered yet; the event still means the turn's
    // follow-up work settled.
    is OrchestrationEvent.TurnDiffCompleted -> this

    is OrchestrationEvent.Unsupported -> this
}

/**
 * Replaces a message with the same id, or appends a new one.
 *
 * The replace path is the streaming hot path: a message keeps its `createdAt`
 * as it grows, so its position cannot change and the list needs no re-sort.
 */
private fun ThreadDetailState.upsertMessage(message: OrchestrationMessage): ThreadDetailState {
    val index = messages.indexOfFirst { it.id == message.id }
    return if (index < 0) {
        copy(messages = messages + message)
    } else {
        copy(messages = messages.toMutableList().apply { set(index, message) })
    }
}
