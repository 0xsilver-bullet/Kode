package com.silverbullet.kode.feature.threads.domain

import androidx.compose.runtime.Immutable
import com.silverbullet.kode.core.model.MessageRole
import com.silverbullet.kode.core.model.OrchestrationCheckpointSummary
import com.silverbullet.kode.core.model.OrchestrationEvent
import com.silverbullet.kode.core.model.OrchestrationLatestTurn
import com.silverbullet.kode.core.model.OrchestrationMessage
import com.silverbullet.kode.core.model.OrchestrationSession
import com.silverbullet.kode.core.model.OrchestrationThread
import com.silverbullet.kode.core.model.SessionStatus
import com.silverbullet.kode.core.model.ThreadStreamItem
import com.silverbullet.kode.core.model.TurnState

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
    /**
     * Per-turn diff checkpoints, ascending by `checkpointTurnCount`. Seeded from
     * the snapshot and kept live by `thread.turn-diff-completed` events; the
     * review screen derives its turn sections from these.
     */
    val checkpoints: List<OrchestrationCheckpointSummary> = emptyList(),
    /**
     * The highest event sequence folded in so far, seeded from the snapshot's
     * `snapshotSequence`. The server attaches its live tap *before* loading the
     * snapshot, so events already baked into the snapshot can be re-delivered
     * right after it; replaying one would double-append a streamed delta.
     */
    val lastSequence: Int = 0,
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
 * The important case is [OrchestrationEvent.MessageSent]: a streaming assistant
 * event carries only the **newly produced chunk** (`decider.ts` maps each
 * `thread.message.assistant.delta` to a `message-sent` whose `text` is the
 * delta), so the client accumulates by appending — mirroring
 * `applyThreadDetailEvent` in T3 Code's `threadReducer.ts`. The closing
 * `streaming: false` event carries *empty* text and merely settles the message.
 */
fun ThreadDetailState.reduce(item: ThreadStreamItem): ThreadDetailState = when (item) {
    is ThreadStreamItem.Snapshot -> {
        val thread = item.snapshot.thread
        // Sorted once, for every consumer below: collapsing and the two
        // lifecycle derivations all assume `activityOrder`, and a snapshot
        // makes no promise about the order it lists activities in.
        val ordered = thread.activities.sortedInActivityOrder()
        copy(
            thread = thread,
            messages = thread.messages,
            activities = ordered.filter { it.isRenderable() }.map { it.toPresentation() },
            session = thread.session,
            // Derived from the raw activities: `toPresentation` drops the
            // payload, and the questions live there.
            pendingUserInputs = derivePendingUserInputs(ordered),
            pendingApprovals = derivePendingApprovals(ordered),
            checkpoints = thread.checkpoints.sortedBy { it.checkpointTurnCount },
            lastSequence = item.snapshot.snapshotSequence,
            status = SyncStatus.Synchronizing,
            error = null,
        )
    }

    ThreadStreamItem.Synchronized -> copy(status = SyncStatus.Live)

    is ThreadStreamItem.Event ->
        // Events at or below the cursor were already folded in — either baked
        // into the snapshot or replayed across a resubscribe. With append
        // semantics for streamed text, applying one twice corrupts the reply.
        if (item.event.sequence <= lastSequence) {
            this
        } else {
            reduce(item.event).copy(status = SyncStatus.Live, lastSequence = item.event.sequence)
        }

    is ThreadStreamItem.Unsupported -> this
}

private fun ThreadDetailState.reduce(event: OrchestrationEvent): ThreadDetailState = when (event) {
    is OrchestrationEvent.MessageSent ->
        upsertMessage(event.message).settleTurnForMessage(event.message)

    is OrchestrationEvent.ActivityAppended -> {
        val activity = event.activity
        // Request tracking is independent of whether the row renders: a
        // resolution may arrive as an activity we never display.
        val pending = pendingUserInputs.applyUserInputActivity(activity)
        val approvals = pendingApprovals.applyApprovalActivity(activity)
        // Renderability first: `tool.progress` and `context-window.updated` are
        // the densest events on the wire and none of them reach the list, so
        // they should not pay for the id scan below.
        if (!activity.isRenderable()) {
            copy(pendingUserInputs = pending, pendingApprovals = approvals)
        } else {
            // Not every activity id is unique. Subagent rows are emitted under
            // a *stable* id (`task-progress:<thread>:<taskId>`) precisely so a
            // new tick replaces the last known state instead of stacking a row
            // per tick — so an id we have already seen is an update in place,
            // not a duplicate to discard. Replacing at the original index also
            // keeps a replay across a resubscribe idempotent, which is what the
            // old outright skip was there for.
            val existing = activities.indexOfFirst { it.id == activity.id }
            copy(
                activities = if (existing < 0) {
                    activities + activity.toPresentation()
                } else {
                    activities.toMutableList()
                        .also { it[existing] = activity.toPresentation() }
                },
                pendingUserInputs = pending,
                pendingApprovals = approvals,
            )
        }
    }

    is OrchestrationEvent.SessionSet -> applySession(event.session)

    // Without these three the thread we render from never changed, so a
    // configuration change looked like it had silently failed.
    is OrchestrationEvent.MetaUpdated -> copy(
        thread = thread?.copy(
            modelSelection = event.modelSelection ?: thread.modelSelection,
            title = event.title ?: thread.title,
        ),
    )

    is OrchestrationEvent.RuntimeModeSet -> copy(
        thread = thread?.copy(runtimeMode = event.runtimeMode),
    )

    is OrchestrationEvent.InteractionModeSet -> copy(
        thread = thread?.copy(interactionMode = event.interactionMode),
    )

    // Upsert by turn: a checkpoint can be re-emitted for the same turn (e.g.
    // after a revert re-captures it), and must replace, not duplicate.
    is OrchestrationEvent.TurnDiffCompleted -> event.checkpoint?.let { checkpoint ->
        copy(
            checkpoints = (checkpoints.filterNot { it.turnId == checkpoint.turnId } + checkpoint)
                .sortedBy { it.checkpointTurnCount },
        )
    } ?: this

    is OrchestrationEvent.Unsupported -> this
}

/**
 * Merges a message into the list by id, or appends a new one.
 *
 * The merge path is the streaming hot path: a `streaming: true` event's text is
 * a delta to append, a `streaming: false` one closes the message — with the
 * full text when the server sends it, otherwise keeping what was accumulated
 * (the finalize event carries empty text). A message keeps its `createdAt` as
 * it grows, so its position cannot change and the list needs no re-sort.
 */
private fun ThreadDetailState.upsertMessage(message: OrchestrationMessage): ThreadDetailState {
    val index = messages.indexOfFirst { it.id == message.id }
    if (index < 0) return copy(messages = messages + message)

    val existing = messages[index]
    val merged = existing.copy(
        text = when {
            message.streaming -> existing.text + message.text
            message.text.isNotEmpty() -> message.text
            else -> existing.text
        },
        streaming = message.streaming,
        turnId = message.turnId ?: existing.turnId,
        // Streaming deltas carry no attachments, so an empty list means "not
        // mentioned" rather than "removed" — dropping them here would blank the
        // images on a user message the moment the reply started arriving.
        attachments = message.attachments.ifEmpty { existing.attachments },
        // Deltas reuse the command timestamp; only the closing event is a
        // meaningful "last touched" time.
        updatedAt = if (message.streaming) existing.updatedAt else message.updatedAt,
    )
    return copy(messages = messages.toMutableList().apply { set(index, merged) })
}

/**
 * Keeps `thread.latestTurn` truthful while events stream in.
 *
 * The snapshot's `latestTurn` describes the *previous* turn; without this the
 * presenter treats the turn that started after subscribing as settled and folds
 * its live tool activity out of view. Ported from `thread.session-set` handling
 * in T3 Code's `threadReducer.ts`: entering `running` (re)points the turn at
 * `activeTurnId`; leaving `running` is the authoritative turn end and settles a
 * still-running turn.
 */
private fun ThreadDetailState.applySession(session: OrchestrationSession): ThreadDetailState {
    val current = thread?.latestTurn
    val activeTurnId = session.activeTurnId
    val settledState = settledTurnStateForSessionStatus(session.status)
    val latestTurn = when {
        session.status == SessionStatus.RUNNING && activeTurnId != null -> {
            // Carry the turn's own timestamps across repeated session updates.
            val carried = current?.takeIf { it.turnId == activeTurnId }
            OrchestrationLatestTurn(
                turnId = activeTurnId,
                state = TurnState.RUNNING,
                requestedAt = carried?.requestedAt ?: session.updatedAt,
                startedAt = carried?.startedAt ?: session.updatedAt,
                completedAt = null,
                assistantMessageId = carried?.assistantMessageId,
            )
        }

        current?.state == TurnState.RUNNING && settledState != null ->
            current.copy(state = settledState, completedAt = session.updatedAt)

        else -> current
    }
    return copy(session = session, thread = thread?.copy(latestTurn = latestTurn))
}

/**
 * Turn state to settle a still-running turn with when its session leaves
 * `running`, or null while the session is (re)starting or running and the turn
 * must stay unsettled. Mirrors `settledTurnStateForSessionStatus` in T3 Code.
 */
private fun settledTurnStateForSessionStatus(status: String): String? = when (status) {
    SessionStatus.IDLE, SessionStatus.READY -> TurnState.COMPLETED
    SessionStatus.ERROR -> TurnState.ERROR
    SessionStatus.INTERRUPTED, SessionStatus.STOPPED -> TurnState.INTERRUPTED
    else -> null
}

/**
 * Tracks `latestTurn` from assistant messages, mirroring the `message-sent`
 * branch of T3 Code's reducer. A completed assistant message only settles the
 * turn once the session is no longer running it — providers emit several
 * assistant messages per turn (commentary between tool calls), and the turn
 * must stay unsettled until the provider reports turn end.
 */
private fun ThreadDetailState.settleTurnForMessage(
    message: OrchestrationMessage,
): ThreadDetailState {
    val thread = thread ?: return this
    val turnId = message.turnId
    if (message.role != MessageRole.ASSISTANT || turnId == null) return this

    // From here `current` is either null or this message's own turn.
    val current = thread.latestTurn
    if (current != null && current.turnId != turnId) return this

    val turnStillRunning = session.let {
        it != null && it.status == SessionStatus.RUNNING && it.activeTurnId == turnId
    }
    val settlesTurn = !message.streaming && !turnStillRunning
    val latestTurn = OrchestrationLatestTurn(
        turnId = turnId,
        state = when {
            !settlesTurn -> TurnState.RUNNING
            current?.state == TurnState.INTERRUPTED -> TurnState.INTERRUPTED
            current?.state == TurnState.ERROR -> TurnState.ERROR
            else -> TurnState.COMPLETED
        },
        requestedAt = current?.requestedAt ?: message.createdAt,
        startedAt = current?.startedAt ?: message.createdAt,
        completedAt = if (settlesTurn) message.updatedAt else current?.completedAt,
        assistantMessageId = message.id,
    )
    return copy(thread = thread.copy(latestTurn = latestTurn))
}
