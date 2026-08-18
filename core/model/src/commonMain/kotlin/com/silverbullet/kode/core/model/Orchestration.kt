package com.silverbullet.kode.core.model

import kotlinx.serialization.Serializable

/**
 * Orchestration read-model types, mirroring
 * `packages/contracts/src/orchestration.ts`.
 *
 * Two rules govern every type here, both taken from T3 Code's own wire
 * discipline:
 *
 *  - **Absent means unsupported, not invalid.** Optional fields carry defaults
 *    so a snapshot from an older or newer server still decodes.
 *  - **Literal unions decode as `String`.** Modelling `runtimeMode` or
 *    `sessionStatus` as a Kotlin enum would turn a value added server-side into
 *    a hard decode failure for the whole thread. Known values are exposed as
 *    constants instead.
 */

// ---------------------------------------------------------------- shared bits

/**
 * Routing key for a configured provider instance plus the chosen model.
 *
 * The wire form is `{instanceId, model, options?}`. Legacy payloads used
 * `{provider, model}`; the server promotes those before they reach us, so no
 * compatibility handling is needed on this side.
 */
@Serializable
data class ModelSelection(
    val instanceId: String,
    val model: String,
    /**
     * Per-model tunables such as reasoning effort.
     *
     * Null rather than empty when unset: the contract marks the key optional,
     * and sending an empty array would overwrite server-side defaults.
     */
    val options: List<ProviderOptionSelection>? = null,
)

/** `RuntimeMode` — the safety/access mode for a thread or session. */
object RuntimeMode {
    const val APPROVAL_REQUIRED = "approval-required"
    const val AUTO_ACCEPT_EDITS = "auto-accept-edits"
    const val AUTO = "auto"
    const val FULL_ACCESS = "full-access"
}

/** `ProviderInteractionMode`. */
object InteractionMode {
    const val DEFAULT = "default"
    const val PLAN = "plan"
}

/** `OrchestrationSessionStatus`. */
object SessionStatus {
    const val IDLE = "idle"
    const val STARTING = "starting"
    const val RUNNING = "running"
    const val READY = "ready"
    const val INTERRUPTED = "interrupted"
    const val STOPPED = "stopped"
    const val ERROR = "error"
}

object MessageRole {
    const val USER = "user"
    const val ASSISTANT = "assistant"
    const val SYSTEM = "system"
}

/** `OrchestrationThreadActivityTone`. */
object ActivityTone {
    const val INFO = "info"
    const val TOOL = "tool"
    const val APPROVAL = "approval"
    const val ERROR = "error"
}

/** `OrchestrationLatestTurnState`. */
object TurnState {
    const val RUNNING = "running"
    const val INTERRUPTED = "interrupted"
    const val COMPLETED = "completed"
    const val ERROR = "error"
}

// ------------------------------------------------------------------- entities

@Serializable
data class OrchestrationSession(
    val threadId: ThreadId,
    val status: String,
    val providerName: String? = null,
    val providerInstanceId: String? = null,
    val runtimeMode: String = RuntimeMode.APPROVAL_REQUIRED,
    val activeTurnId: String? = null,
    val lastError: String? = null,
    val updatedAt: String,
) {
    /** A turn is in flight. This is what drives the "working" indicator. */
    val isBusy: Boolean
        get() = status == SessionStatus.RUNNING || status == SessionStatus.STARTING
}

@Serializable
data class OrchestrationLatestTurn(
    val turnId: String,
    val state: String,
    val requestedAt: String,
    val startedAt: String? = null,
    val completedAt: String? = null,
    val assistantMessageId: String? = null,
)

@Serializable
data class OrchestrationMessage(
    val id: String,
    val role: String,
    val text: String,
    val turnId: String? = null,
    /**
     * True while the assistant is still producing this message. A streaming
     * event's [text] is only the newly produced chunk — never the accumulated
     * reply — so folding it in is an append by [id]. Snapshots carry the full
     * projected text with [streaming] already settled.
     */
    val streaming: Boolean = false,
    /**
     * Images sent with this message. Bytes are never carried here — each entry
     * is resolved to a signed URL through `assets.createUrl` when it is
     * rendered.
     */
    val attachments: List<ChatAttachment> = emptyList(),
    val createdAt: String,
    val updatedAt: String,
)

/**
 * A user-visible non-message event: tool calls, approvals, failures.
 *
 * Note this is a flat struct, not a union — [kind] is an open string and
 * [summary] is already human readable, so rendering needs no per-kind
 * knowledge. `payload` is intentionally not modelled.
 */
@Serializable
data class OrchestrationThreadActivity(
    val id: String,
    val tone: String,
    val kind: String,
    val summary: String,
    val turnId: String? = null,
    val sequence: Int? = null,
    val createdAt: String,
    /**
     * The parts of the opaque `payload` that drive presentation.
     *
     * The contract types this as `Schema.Unknown`; T3 Code's mobile client digs
     * the same fields out of it in `threadActivity.ts`. Everything here is
     * optional because the shape varies per provider and per tool.
     */
    val payload: ActivityPayload? = null,
) {
    /** `ToolLifecycleItemType`, when this activity is a tool lifecycle event. */
    val itemType: String? get() = payload?.itemType

    /** The approval request kind, when this activity is an approval. */
    val requestKind: String? get() = payload?.requestKind

    val command: String? get() = payload?.resolveCommand()

    val changedFiles: List<String> get() = payload?.data?.collectChangedFiles().orEmpty()

    /**
     * Longer body shown when the row is expanded, assembled the same way as
     * `buildWorkEntryExpandedBody`: command first, then detail, then files.
     */
    val expandedDetail: String?
        get() {
            val blocks = LinkedHashSet<String>()
            command?.trim()?.takeIf { it.isNotEmpty() }?.let(blocks::add)
            payload?.detail?.trim()?.takeIf { it.isNotEmpty() }?.let(blocks::add)
            changedFiles.takeIf { it.isNotEmpty() }?.let { blocks.add(it.joinToString("\n")) }
            return blocks.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
        }

    /** Short preview line under the summary. */
    val preview: String?
        get() = (command ?: payload?.detail)
            ?.lineSequence()
            ?.firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it != summary }
}

/** Tool lifecycle item types, from `ToolLifecycleItemType` in the contracts. */
object ToolItemType {
    const val COMMAND_EXECUTION = "command_execution"
    const val FILE_CHANGE = "file_change"
    const val WEB_SEARCH = "web_search"
    const val IMAGE_VIEW = "image_view"
    const val MCP_TOOL_CALL = "mcp_tool_call"
    const val DYNAMIC_TOOL_CALL = "dynamic_tool_call"
    const val COLLAB_AGENT_TOOL_CALL = "collab_agent_tool_call"
}

/** Approval request kinds. */
object ApprovalRequestKind {
    const val COMMAND = "command"
    const val FILE_READ = "file-read"
    const val FILE_CHANGE = "file-change"
}

/** Tool lifecycle status, from `payload.status`. */
object ToolLifecycleStatus {
    const val IN_PROGRESS = "inProgress"
    const val COMPLETED = "completed"
    const val FAILED = "failed"
    const val DECLINED = "declined"
    const val STOPPED = "stopped"
}

/**
 * One question the agent is asking, from `UserInputQuestion` in
 * `packages/contracts/src/providerRuntime.ts`.
 */
@Serializable
data class UserInputQuestion(
    val id: String,
    val header: String,
    val question: String,
    val options: List<UserInputOption> = emptyList(),
    /** Absent means single-select. */
    val multiSelect: Boolean = false,
)

@Serializable
data class UserInputOption(
    val label: String,
    val description: String = "",
)

@Serializable
data class ActivityPayload(
    val itemType: String? = null,
    /** Correlates `user-input.requested` with its `resolved`/`failed` follow-up. */
    val requestId: String? = null,
    val questions: List<UserInputQuestion>? = null,
    val requestKind: String? = null,
    val requestType: String? = null,
    val status: String? = null,
    val title: String? = null,
    val detail: String? = null,
    val summary: String? = null,
    val taskId: String? = null,
    val data: ActivityPayloadData? = null,
) {
    /**
     * Command candidates in the same precedence order as `extractToolCommand`.
     */
    internal fun resolveCommand(): String? = sequenceOf(
        data?.item?.command,
        data?.item?.input?.command,
        data?.item?.result?.command,
        data?.command,
    ).firstOrNull { !it.isNullOrBlank() }?.trim()
}

@Serializable
data class ActivityPayloadData(
    val command: String? = null,
    val item: ActivityPayloadItem? = null,
    val changedFiles: List<String>? = null,
    val files: List<String>? = null,
) {
    internal fun collectChangedFiles(): List<String> {
        val collected = LinkedHashSet<String>()
        changedFiles?.forEach { it.trim().takeIf(String::isNotEmpty)?.let(collected::add) }
        files?.forEach { it.trim().takeIf(String::isNotEmpty)?.let(collected::add) }
        item?.changedFiles?.forEach { it.trim().takeIf(String::isNotEmpty)?.let(collected::add) }
        item?.path?.trim()?.takeIf(String::isNotEmpty)?.let(collected::add)
        return collected.toList()
    }
}

@Serializable
data class ActivityPayloadItem(
    val command: String? = null,
    val path: String? = null,
    val changedFiles: List<String>? = null,
    val input: ActivityPayloadCommandHolder? = null,
    val result: ActivityPayloadCommandHolder? = null,
)

@Serializable
data class ActivityPayloadCommandHolder(
    val command: String? = null,
)

@Serializable
data class PlanProgress(
    val step: String,
    val completedSteps: Int,
    val totalSteps: Int,
)

/**
 * The list-row projection of a thread, from `orchestration.subscribeShell`.
 *
 * Coarser than [OrchestrationThread]: no messages or activities, but everything
 * needed to render and sort a list.
 */
@Serializable
data class OrchestrationThreadShell(
    val id: ThreadId,
    val projectId: ProjectId,
    val title: String,
    val modelSelection: ModelSelection? = null,
    val runtimeMode: String = RuntimeMode.APPROVAL_REQUIRED,
    val interactionMode: String = InteractionMode.DEFAULT,
    val branch: String? = null,
    val worktreePath: String? = null,
    val latestTurn: OrchestrationLatestTurn? = null,
    val createdAt: String,
    val updatedAt: String,
    val archivedAt: String? = null,
    /**
     * The user's explicit pin: `"settled"` files the thread away, `"active"`
     * keeps it out of the settled shelf regardless of age. Absent on servers
     * predating the `threadSettlement` capability, which just means no pin.
     */
    val settledOverride: String? = null,
    /** When the server accepted a settle. Used to adjudicate races. */
    val settledAt: String? = null,
    val session: OrchestrationSession? = null,
    val latestUserMessageAt: String? = null,
    val hasPendingApprovals: Boolean = false,
    val hasPendingUserInput: Boolean = false,
    val hasActionableProposedPlan: Boolean = false,
    val backgroundLiveness: String? = null,
    val planProgress: PlanProgress? = null,
) {
    val isArchived: Boolean get() = archivedAt != null

    /**
     * Work is alive: either a turn is running or background work outlived it.
     * `backgroundLiveness` is absent on older servers, which correctly reads as
     * "no background work".
     */
    val isBusy: Boolean
        get() = session?.isBusy == true ||
            latestTurn?.state == TurnState.RUNNING ||
            backgroundLiveness != null

    val needsAttention: Boolean
        get() = hasPendingApprovals || hasPendingUserInput
}

@Serializable
data class OrchestrationProjectShell(
    val id: ProjectId,
    val title: String,
    val workspaceRoot: String,
    val createdAt: String,
    val updatedAt: String,
)

/** The full thread, from a `subscribeThread` snapshot. */
@Serializable
data class OrchestrationThread(
    val id: ThreadId,
    val projectId: ProjectId,
    val title: String,
    val modelSelection: ModelSelection? = null,
    val runtimeMode: String = RuntimeMode.APPROVAL_REQUIRED,
    val interactionMode: String = InteractionMode.DEFAULT,
    val branch: String? = null,
    val worktreePath: String? = null,
    val latestTurn: OrchestrationLatestTurn? = null,
    val createdAt: String,
    val updatedAt: String,
    val archivedAt: String? = null,
    val deletedAt: String? = null,
    val messages: List<OrchestrationMessage> = emptyList(),
    val activities: List<OrchestrationThreadActivity> = emptyList(),
    val session: OrchestrationSession? = null,
)

@Serializable
data class OrchestrationShellSnapshot(
    val snapshotSequence: Int,
    val projects: List<OrchestrationProjectShell> = emptyList(),
    val threads: List<OrchestrationThreadShell> = emptyList(),
    val updatedAt: String,
)

@Serializable
data class OrchestrationThreadDetailSnapshot(
    val snapshotSequence: Int,
    val thread: OrchestrationThread,
)
