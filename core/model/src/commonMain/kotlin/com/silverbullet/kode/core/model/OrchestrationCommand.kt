package com.silverbullet.kode.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

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
 * Starts a turn by sending user input into a thread.
 *
 * [runtimeMode] and [interactionMode] are required on the client variant — the
 * server's decoding defaults apply only to its internal command type. Callers
 * should pass the thread's *current* modes rather than a constant: forcing
 * `approval-required` on a thread would strand the turn behind an approval this
 * client cannot yet answer.
 *
 * With [bootstrap] the thread need not exist yet: the server creates it from
 * `bootstrap.createThread` and runs this turn as its first, which is how T3
 * Code's mobile client creates threads. [titleSeed] feeds the server's title
 * generation; both are `Schema.optional`, so they are omitted when null.
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
    val titleSeed: String? = null,
    val bootstrap: ThreadTurnStartBootstrap? = null,
) : ClientOrchestrationCommand

/**
 * `ThreadTurnStartBootstrap` — work the server performs before the turn runs.
 *
 * `prepareWorktree` and `runSetupScript` exist in the contract but need the
 * worktree flow this client does not have, so they are not modelled yet.
 */
@Serializable
data class ThreadTurnStartBootstrap(
    val createThread: ThreadTurnStartBootstrapCreateThread? = null,
)

/**
 * `ThreadTurnStartBootstrapCreateThread`.
 *
 * `branch` and `worktreePath` are [JsonElement]s for the same reason as on
 * [ThreadCreateCommand]: the contract types them `NullOr`, so the keys must be
 * present even when null, and `explicitNulls = false` would drop a Kotlin null.
 */
@Serializable
data class ThreadTurnStartBootstrapCreateThread(
    val projectId: ProjectId,
    val title: String,
    val modelSelection: ModelSelection,
    val runtimeMode: String,
    val interactionMode: String,
    val createdAt: String,
    val branch: JsonElement = JsonNull,
    val worktreePath: JsonElement = JsonNull,
)

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
 *
 * They are typed as [JsonElement] rather than `String?` because the contract
 * declares them `NullOr`, not optional: the keys must be present on the wire
 * even when null. Our encoder uses `explicitNulls = false` (required so
 * `Schema.optional` fields are omitted instead of sent as null), which drops
 * nullable Kotlin properties holding null — a [JsonNull] value survives it.
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
    val branch: JsonElement = JsonNull,
    val worktreePath: JsonElement = JsonNull,
) : ClientOrchestrationCommand

/**
 * Updates a thread's metadata.
 *
 * Note there is deliberately no `createdAt`: the contract does not carry one
 * for this command.
 *
 * [branch] and [worktreePath] are `Schema.optional(Schema.NullOr(…))` in the
 * contract — omitting a key means "leave unchanged", so `String?` with
 * `explicitNulls = false` is the right shape here (unlike the always-present
 * `NullOr` fields on [ThreadCreateCommand]). Explicitly *clearing* a branch is
 * not modelled because no client flow needs it.
 */
@Serializable
@SerialName("thread.meta.update")
data class ThreadMetaUpdateCommand(
    override val commandId: String,
    override val threadId: ThreadId,
    val modelSelection: ModelSelection? = null,
    val title: String? = null,
    val branch: String? = null,
    val worktreePath: String? = null,
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

/**
 * Files a thread away as finished business.
 *
 * `ThreadSettleCommand` in `packages/contracts/src/orchestration.ts` carries
 * nothing but the ids — no timestamp, because the server stamps `settledAt`
 * itself so two devices cannot disagree about when a thread was put down.
 *
 * The decider rejects a settle on a thread with a live session, an open
 * approval / user-input request, or a just-queued turn, so callers should
 * pre-check the same conditions rather than surface the error
 * (see `isSettleable`). Settling an already-settled thread is a silent no-op.
 *
 * Only send this to an environment advertising
 * [ExecutionEnvironmentCapabilities.threadSettlement].
 */
@Serializable
@SerialName("thread.settle")
data class ThreadSettleCommand(
    override val commandId: String,
    override val threadId: ThreadId,
) : ClientOrchestrationCommand {
    // Not on the wire schema, and a custom getter has no backing field, so
    // nothing is serialized for it.
    override val createdAt: String get() = ""
}

/**
 * The user message a turn starts from.
 *
 * [attachments] takes the *upload* shape, not the one messages carry: the bytes
 * travel inline as data URLs on this one command, and the server answers with
 * [ChatAttachment]s that have ids. There is no separate upload endpoint to call
 * first.
 */
@Serializable
data class UserMessageInput(
    val messageId: String,
    val text: String,
    val role: String = MessageRole.USER,
    val attachments: List<UploadChatImageAttachment> = emptyList(),
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
