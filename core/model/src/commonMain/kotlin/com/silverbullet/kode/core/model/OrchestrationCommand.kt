package com.silverbullet.kode.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Client-dispatchable orchestration commands, mirroring
 * `ClientOrchestrationCommand` in `packages/contracts/src/orchestration.ts`.
 *
 * The server is event-sourced: a command is a *request* to change state, turned
 * into events by the decider. It is not a mutation, and it is not guaranteed to
 * be accepted.
 *
 * Ids are plain non-empty strings on the wire (`makeEntityId` brands a
 * `TrimmedNonEmptyString`) and `IsoDateTime` is an unvalidated string, so a
 * UUID and an ISO-8601 instant are all that is required.
 */
@Serializable
sealed interface ClientOrchestrationCommand {
    val commandId: String
    val threadId: ThreadId
    val createdAt: String
}

/**
 * Starts a turn by sending user input into an existing thread.
 *
 * [runtimeMode] and [interactionMode] are required on the client variant — the
 * server's decoding defaults apply only to its internal command type. Callers
 * should pass the thread's *current* modes rather than a constant: forcing
 * `approval-required` on a thread would strand the turn behind an approval this
 * client cannot yet answer.
 */
@Serializable
@SerialName("thread.turn.start")
data class ThreadTurnStartCommand(
    override val commandId: String,
    override val threadId: ThreadId,
    val message: UserMessageInput,
    val runtimeMode: String,
    val interactionMode: String,
    override val createdAt: String,
    val modelSelection: ModelSelection? = null,
) : ClientOrchestrationCommand

/**
 * Answers one pending user-input request.
 *
 * `answers` maps question id to either a single label or, for multi-select, a
 * list of them — the contract types it as `Record<String, Unknown>`, so the
 * value shape is per-question. All questions in the request must be answered;
 * the server has no notion of a partial reply.
 */
@Serializable
@SerialName("thread.user-input.respond")
data class ThreadUserInputRespondCommand(
    override val commandId: String,
    override val threadId: ThreadId,
    val requestId: String,
    val answers: Map<String, JsonElement>,
    override val createdAt: String,
) : ClientOrchestrationCommand

/**
 * Creates a thread in a project.
 *
 * `branch` and `worktreePath` are always null here: choosing a branch or an
 * isolated worktree needs the `vcs.*` surface, which this client does not have
 * yet, so new threads run in the project's current checkout.
 */
@Serializable
@SerialName("thread.create")
data class ThreadCreateCommand(
    override val commandId: String,
    override val threadId: ThreadId,
    val projectId: ProjectId,
    val title: String,
    val modelSelection: ModelSelection,
    val runtimeMode: String,
    val interactionMode: String,
    override val createdAt: String,
    val branch: String? = null,
    val worktreePath: String? = null,
) : ClientOrchestrationCommand

/**
 * Updates a thread's metadata.
 *
 * Note there is deliberately no `createdAt`: the contract does not carry one
 * for this command.
 */
@Serializable
@SerialName("thread.meta.update")
data class ThreadMetaUpdateCommand(
    override val commandId: String,
    override val threadId: ThreadId,
    val modelSelection: ModelSelection? = null,
    val title: String? = null,
) : ClientOrchestrationCommand {
    override val createdAt: String get() = ""
}

@Serializable
@SerialName("thread.runtime-mode.set")
data class ThreadRuntimeModeSetCommand(
    override val commandId: String,
    override val threadId: ThreadId,
    val runtimeMode: String,
    override val createdAt: String,
) : ClientOrchestrationCommand

@Serializable
@SerialName("thread.interaction-mode.set")
data class ThreadInteractionModeSetCommand(
    override val commandId: String,
    override val threadId: ThreadId,
    val interactionMode: String,
    override val createdAt: String,
) : ClientOrchestrationCommand

/**
 * Stops the running turn.
 *
 * `turnId` is optional in the contract: omitting it interrupts whichever turn is
 * currently running, which is what a Stop button means.
 */
@Serializable
@SerialName("thread.turn.interrupt")
data class ThreadTurnInterruptCommand(
    override val commandId: String,
    override val threadId: ThreadId,
    override val createdAt: String,
    val turnId: String? = null,
) : ClientOrchestrationCommand

/**
 * Answers a pending approval request.
 *
 * `ProviderApprovalDecision` also defines `cancel`, which T3 Code's mobile
 * client does not surface either — it cancels the whole turn rather than
 * deciding this one action, so it belongs with turn interruption, not here.
 */
@Serializable
@SerialName("thread.approval.respond")
data class ThreadApprovalRespondCommand(
    override val commandId: String,
    override val threadId: ThreadId,
    val requestId: String,
    val decision: String,
    override val createdAt: String,
) : ClientOrchestrationCommand

/** `ProviderApprovalDecision`. */
object ApprovalDecision {
    /** Allow this one action. */
    const val ACCEPT = "accept"

    /** Allow this and every later action of the same kind for the session. */
    const val ACCEPT_FOR_SESSION = "acceptForSession"

    const val DECLINE = "decline"

    /** Cancels the turn outright. Not offered in the approval card. */
    const val CANCEL = "cancel"
}

@Serializable
data class UserMessageInput(
    val messageId: String,
    val text: String,
    val role: String = MessageRole.USER,
    /** Attachments are not implemented yet; the field is required on the wire. */
    val attachments: List<String> = emptyList(),
)

/**
 * `DispatchResult` — the event-log sequence the command committed at. The
 * resulting events arrive over the thread subscription, so this is only useful
 * as an acceptance acknowledgement.
 */
@Serializable
data class DispatchResult(
    val sequence: Int,
)
