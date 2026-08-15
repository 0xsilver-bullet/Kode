package com.silverbullet.kode.feature.threads.domain

import com.silverbullet.kode.core.model.ActivityTone
import com.silverbullet.kode.core.model.ApprovalRequestKind
import com.silverbullet.kode.core.model.OrchestrationThreadActivity
import com.silverbullet.kode.core.model.ToolItemType
import com.silverbullet.kode.core.model.ToolLifecycleStatus

/**
 * The twelve activity icons T3 Code's mobile feed uses, from
 * `ThreadFeedActivity["icon"]` in `apps/mobile/src/lib/threadActivity.ts`.
 */
enum class ActivityIcon { Agent, Alert, Check, Command, Edit, Eye, Globe, Hammer, Message, Warning, Wrench, Zap }

enum class ActivityStatus { Success, Failure, Neutral }

/**
 * A tool/activity row, presentation-ready.
 *
 * Derived once per activity in the reducer rather than during composition, so
 * scrolling and streaming never re-run this.
 */
data class ActivityPresentation(
    val id: String,
    val createdAt: String,
    /** Groups adjacent rows into one work-log block per turn. */
    val turnId: String?,
    /** One row per subagent: identity-collapsed rather than adjacency-collapsed. */
    val taskId: String?,
    /**
     * Adjacency-collapse key for tool lifecycle rows, from
     * `deriveToolLifecycleCollapseKey`. Null means "never collapse".
     */
    val collapseKey: String?,
    val kind: String,
    val summary: String,
    val preview: String?,
    val expandedDetail: String?,
    val icon: ActivityIcon,
    val status: ActivityStatus?,
    val isToolLike: Boolean,
) {
    val canExpand: Boolean get() = expandedDetail != null
}

/**
 * Effective tone, matching `toDerivedWorkLogEntry`: progress on a subagent task
 * reads as "thinking", and approvals are demoted to "info" so they do not render
 * as tool output.
 */
private const val TONE_THINKING = "thinking"

private fun effectiveTone(activity: OrchestrationThreadActivity): String = when {
    activity.kind == "task.progress" -> TONE_THINKING
    activity.tone == ActivityTone.APPROVAL -> ActivityTone.INFO
    else -> activity.tone
}

/**
 * Activities T3 Code drops before rendering, from `deriveWorkLogEntries`.
 *
 * These are lifecycle bookends and noise: keeping them would double every tool
 * row (a `started` and a `completed` for the same call) and add checkpoint
 * chatter the user never asked to see.
 */
fun OrchestrationThreadActivity.isRenderable(): Boolean {
    if (kind in HIDDEN_KINDS) return false
    if (summary == "Checkpoint captured") return false
    // ExitPlanMode is a plan-mode boundary marker, not user-facing work.
    if ((kind == "tool.updated" || kind == "tool.completed") &&
        payload?.detail?.startsWith("ExitPlanMode:") == true
    ) {
        return false
    }
    return true
}

private val HIDDEN_KINDS = setOf(
    "tool.started",
    "task.started",
    "task.updated",
    "tool.progress",
    "context-window.updated",
)

fun OrchestrationThreadActivity.toPresentation(): ActivityPresentation {
    val toolLike = isToolLike()
    return ActivityPresentation(
        id = id,
        createdAt = createdAt,
        turnId = turnId,
        taskId = payload?.taskId,
        collapseKey = collapseKey(),
        kind = kind,
        summary = payload?.title?.takeIf { it.isNotBlank() } ?: summary,
        preview = preview,
        expandedDetail = expandedDetail,
        icon = resolveIcon(),
        status = if (toolLike) resolveStatus() else null,
        isToolLike = toolLike,
    )
}

/** Port of `workLogEntryIsToolLike`. */
private fun OrchestrationThreadActivity.isToolLike(): Boolean {
    val tone = effectiveTone(this)
    if (tone == ActivityTone.TOOL || tone == TONE_THINKING || tone == ActivityTone.ERROR) return true
    if (!command.isNullOrBlank()) return true
    if (requestKind != null) return true
    return itemType in TOOL_ITEM_TYPES
}

private val TOOL_ITEM_TYPES = setOf(
    ToolItemType.COMMAND_EXECUTION,
    ToolItemType.FILE_CHANGE,
    ToolItemType.WEB_SEARCH,
    ToolItemType.IMAGE_VIEW,
    ToolItemType.MCP_TOOL_CALL,
    ToolItemType.DYNAMIC_TOOL_CALL,
    ToolItemType.COLLAB_AGENT_TOOL_CALL,
)

/** Port of `workEntryIcon`, preserving its precedence order exactly. */
private fun OrchestrationThreadActivity.resolveIcon(): ActivityIcon {
    if (kind == "user-input.requested" || kind == "user-input.resolved") return ActivityIcon.Message
    if (kind == "runtime.warning") return ActivityIcon.Warning

    when (requestKind) {
        ApprovalRequestKind.COMMAND -> return ActivityIcon.Command
        ApprovalRequestKind.FILE_READ -> return ActivityIcon.Eye
        ApprovalRequestKind.FILE_CHANGE -> return ActivityIcon.Edit
    }

    if (itemType == ToolItemType.COMMAND_EXECUTION || !command.isNullOrBlank()) {
        return ActivityIcon.Command
    }
    if (itemType == ToolItemType.FILE_CHANGE || changedFiles.isNotEmpty()) return ActivityIcon.Edit
    if (itemType == ToolItemType.WEB_SEARCH) return ActivityIcon.Globe
    if (itemType == ToolItemType.IMAGE_VIEW) return ActivityIcon.Eye
    if (itemType == ToolItemType.MCP_TOOL_CALL) return ActivityIcon.Wrench
    if (itemType == ToolItemType.DYNAMIC_TOOL_CALL ||
        itemType == ToolItemType.COLLAB_AGENT_TOOL_CALL
    ) {
        return ActivityIcon.Hammer
    }

    return when (effectiveTone(this)) {
        ActivityTone.ERROR -> ActivityIcon.Alert
        TONE_THINKING -> ActivityIcon.Agent
        ActivityTone.INFO -> ActivityIcon.Check
        else -> ActivityIcon.Zap
    }
}

/** Port of `workEntryStatus`. */
private fun OrchestrationThreadActivity.resolveStatus(): ActivityStatus = when {
    indicatesFailure() -> ActivityStatus.Failure
    payload?.status == ToolLifecycleStatus.COMPLETED -> ActivityStatus.Success
    else -> ActivityStatus.Neutral
}

private fun OrchestrationThreadActivity.indicatesFailure(): Boolean {
    if (effectiveTone(this) == ActivityTone.ERROR) return true
    val status = payload?.status
    if (status == ToolLifecycleStatus.FAILED ||
        status == ToolLifecycleStatus.DECLINED ||
        status == ToolLifecycleStatus.STOPPED
    ) {
        return true
    }
    return payload?.detail?.looksLikeToolFailure() == true
}

/**
 * Port of `toolDetailTextLooksLikeFailure`.
 *
 * Providers do not report a machine-readable status for every shell failure, so
 * T3 Code sniffs the output. Matching this keeps a failed command from showing a
 * success tick.
 */
private fun String.looksLikeToolFailure(): Boolean {
    val normalized = lowercase()
    if (FAILURE_PHRASES.any { it in normalized }) return true
    if ("cannot find path" in normalized && "because it does not exist" in normalized) return true
    if ("is not recognized" in normalized && "the term '" in normalized) return true
    return NON_ZERO_EXIT.containsMatchIn(this)
}

private val FAILURE_PHRASES = listOf(
    "file not found",
    "no files found",
    "enoent",
    "no such file or directory",
    "no such file",
    "commandnotfoundexception",
    "command not found",
    "is not recognized as the name of a cmdlet",
    "a parameter cannot be found that matches parameter name",
)

/** Matches `exit code 1`, `<exited with exit code 2>`, and similar. */
private val NON_ZERO_EXIT = Regex(
    "exit(?:ed)?\\s+with\\s+exit\\s+code\\s+[1-9]\\d*|exit\\s+code\\s*[:\\s]\\s*[1-9]\\d*\\b",
    RegexOption.IGNORE_CASE,
)

/**
 * Port of `deriveToolLifecycleCollapseKey`.
 *
 * Adjacent tool lifecycle rows that agree on item type, label and detail are the
 * same call being reported repeatedly, so they collapse into one row. Returns
 * null when there is nothing to key on, which disables collapsing.
 */
private fun OrchestrationThreadActivity.collapseKey(): String? {
    if (kind != "tool.updated" && kind != "tool.completed") return null

    val label = (payload?.title ?: summary).normalizeCompactToolLabel()
    val detail = payload?.detail?.trim().orEmpty()
    val type = itemType.orEmpty()
    if (type.isEmpty() && label.isEmpty() && detail.isEmpty()) return null

    return listOf(type, label, detail).joinToString("\u001f")
}

/** Strips a trailing "complete"/"completed" so a call and its completion match. */
private fun String.normalizeCompactToolLabel(): String =
    trim().removeSuffix("completed").removeSuffix("complete").trim()

/** A terminal row does not absorb further updates. */
fun ActivityPresentation.isTerminal(): Boolean = kind == "tool.completed"
