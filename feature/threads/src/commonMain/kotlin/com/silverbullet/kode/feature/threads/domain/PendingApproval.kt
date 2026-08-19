package com.silverbullet.kode.feature.threads.domain

import androidx.compose.runtime.Immutable
import com.silverbullet.kode.core.model.ApprovalRequestKind
import com.silverbullet.kode.core.model.OrchestrationThreadActivity

/**
 * An action the agent is asking permission to perform.
 *
 * Distinct from a [PendingUserInput]: an approval is a decision about one
 * concrete action the agent is about to take, not a questionnaire. This is what
 * a thread in `approval-required` runtime mode blocks on.
 */
@Immutable
data class PendingApproval(
    val requestId: String,
    /** One of [ApprovalRequestKind]. */
    val requestKind: String,
    val createdAt: String,
    /** The command or file path in question, when the provider supplies one. */
    val detail: String? = null,
) {
    /** `file-change` is not a title. */
    val title: String
        get() = when (requestKind) {
            ApprovalRequestKind.COMMAND -> "Run a command"
            ApprovalRequestKind.FILE_READ -> "Read a file"
            ApprovalRequestKind.FILE_CHANGE -> "Edit a file"
            else -> "Approve this action"
        }

    /**
     * Whether [detail] should render as monospace. Commands and paths both do;
     * anything else is prose.
     */
    val isDetailLiteral: Boolean
        get() = requestKind == ApprovalRequestKind.COMMAND ||
            requestKind == ApprovalRequestKind.FILE_READ ||
            requestKind == ApprovalRequestKind.FILE_CHANGE
}

private const val KIND_REQUESTED = "approval.requested"
private const val KIND_RESOLVED = "approval.resolved"
private const val KIND_RESPOND_FAILED = "provider.approval.respond.failed"

/**
 * Folds one activity into the set of open approvals.
 *
 * Same lifecycle as user input: opened by `approval.requested`, closed by
 * `approval.resolved`, and closed by a respond failure **only** when the server
 * calls the request stale or unknown — any other failure means it is still open
 * and the user needs to retry.
 */
fun Map<String, PendingApproval>.applyApprovalActivity(
    activity: OrchestrationThreadActivity,
): Map<String, PendingApproval> {
    val payload = activity.payload ?: return this
    val requestId = payload.requestId ?: return this

    return when (activity.kind) {
        KIND_REQUESTED -> {
            // Without a kind there is nothing to describe to the user, and T3
            // Code drops the request outright rather than showing a blank card.
            val kind = resolveRequestKind(payload.requestKind, payload.requestType)
                ?: return this
            this + (
                requestId to PendingApproval(
                    requestId = requestId,
                    requestKind = kind,
                    createdAt = activity.createdAt,
                    detail = payload.detail?.takeIf { it.isNotBlank() },
                )
                )
        }

        KIND_RESOLVED -> this - requestId

        KIND_RESPOND_FAILED ->
            if (payload.detail.isStalePendingRequest()) this - requestId else this

        else -> this
    }
}

/**
 * Rebuilds open approvals from scratch, for a snapshot.
 *
 * [activities] must already be in `sortedInActivityOrder`: a resolve has to be
 * able to close a request opened earlier in the same snapshot. The caller sorts
 * once and hands the same list to every derivation rather than each sorting its
 * own copy, mirroring how T3 Code passes one `sortThreadActivities` result to
 * both this and `derivePendingUserInputs`.
 */
fun derivePendingApprovals(
    activities: List<OrchestrationThreadActivity>,
): Map<String, PendingApproval> {
    var open = emptyMap<String, PendingApproval>()
    activities.forEach { open = open.applyApprovalActivity(it) }
    return open
}

/**
 * Port of `requestKindFromRequestType`.
 *
 * Providers disagree on the field: some send `requestKind` directly, others only
 * a provider-specific `requestType`. Falling back keeps Codex-style approvals
 * from being silently dropped.
 */
internal fun resolveRequestKind(requestKind: String?, requestType: String?): String? {
    if (requestKind == ApprovalRequestKind.COMMAND ||
        requestKind == ApprovalRequestKind.FILE_READ ||
        requestKind == ApprovalRequestKind.FILE_CHANGE
    ) {
        return requestKind
    }

    return when (requestType) {
        "command_execution_approval", "exec_command_approval" -> ApprovalRequestKind.COMMAND
        "file_read_approval" -> ApprovalRequestKind.FILE_READ
        "file_change_approval", "apply_patch_approval" -> ApprovalRequestKind.FILE_CHANGE
        else -> null
    }
}
