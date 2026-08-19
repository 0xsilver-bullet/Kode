package com.silverbullet.kode.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Source-control types, mirroring `packages/contracts/src/git.ts`.
 *
 * The same wire discipline as `Orchestration.kt` applies: optional fields carry
 * defaults so payloads from older or newer servers still decode, literal unions
 * decode as `String` with known values exposed as constants, and tagged unions
 * are decoded by hand so an unrecognised discriminator degrades to an
 * `Unsupported` variant instead of failing the stream.
 */

// ------------------------------------------------------------------- status

@Serializable
data class VcsWorkingTreeFile(
    val path: String,
    val insertions: Int = 0,
    val deletions: Int = 0,
)

@Serializable
data class VcsWorkingTree(
    val files: List<VcsWorkingTreeFile> = emptyList(),
    val insertions: Int = 0,
    val deletions: Int = 0,
)

/** `VcsStatusChangeRequestState`. */
object VcsChangeRequestState {
    const val OPEN = "open"
    const val CLOSED = "closed"
    const val MERGED = "merged"
}

/** The pull/change request attached to the current branch, when one exists. */
@Serializable
data class VcsStatusChangeRequest(
    val number: Int,
    val title: String,
    val url: String,
    val baseRef: String,
    val headRef: String,
    val state: String,
)

/** The locally derivable half of a repository's status. */
@Serializable
data class VcsStatusLocal(
    val isRepo: Boolean = false,
    val hasPrimaryRemote: Boolean = false,
    val isDefaultRef: Boolean = false,
    val refName: String? = null,
    val hasWorkingTreeChanges: Boolean = false,
    val workingTree: VcsWorkingTree = VcsWorkingTree(),
)

/** The remote-derived half: upstream tracking, ahead/behind, the open PR. */
@Serializable
data class VcsStatusRemote(
    val hasUpstream: Boolean = false,
    val aheadCount: Int = 0,
    val behindCount: Int = 0,
    val aheadOfDefaultCount: Int? = null,
    val pr: VcsStatusChangeRequest? = null,
)

/**
 * The merged `VcsStatusResult` — what the UI reads. Produced only by
 * [applyVcsStatusStreamEvent]; never decoded directly, because the wire carries
 * the two halves separately.
 */
data class VcsStatus(
    val local: VcsStatusLocal,
    val remote: VcsStatusRemote,
) {
    val isRepo: Boolean get() = local.isRepo
    val hasPrimaryRemote: Boolean get() = local.hasPrimaryRemote
    val isDefaultRef: Boolean get() = local.isDefaultRef
    val refName: String? get() = local.refName
    val hasWorkingTreeChanges: Boolean get() = local.hasWorkingTreeChanges
    val workingTree: VcsWorkingTree get() = local.workingTree
    val hasUpstream: Boolean get() = remote.hasUpstream
    val aheadCount: Int get() = remote.aheadCount
    val behindCount: Int get() = remote.behindCount
    val pr: VcsStatusChangeRequest? get() = remote.pr
}

/**
 * Events on the `subscribeVcsStatus` stream, tagged by `_tag`.
 *
 * `remote` is nullable on [Snapshot] and [RemoteUpdated]: the server emits the
 * local half immediately and fills the remote half in when its poller has
 * fetched, so a null remote means "not known yet", not "no remote".
 */
sealed interface VcsStatusStreamEvent {
    data class Snapshot(val local: VcsStatusLocal, val remote: VcsStatusRemote?) :
        VcsStatusStreamEvent

    data class LocalUpdated(val local: VcsStatusLocal) : VcsStatusStreamEvent
    data class RemoteUpdated(val remote: VcsStatusRemote?) : VcsStatusStreamEvent
    data class Unsupported(val tag: String) : VcsStatusStreamEvent
}

/**
 * Folds one stream event into the merged status, ported from
 * `applyGitStatusStreamEvent` in t3code's `packages/shared/src/git.ts` —
 * including the edge case where a `remoteUpdated` arrives before any local
 * part and a neutral local half is fabricated.
 */
fun applyVcsStatusStreamEvent(current: VcsStatus?, event: VcsStatusStreamEvent): VcsStatus? =
    when (event) {
        is VcsStatusStreamEvent.Snapshot ->
            VcsStatus(event.local, event.remote ?: VcsStatusRemote())

        is VcsStatusStreamEvent.LocalUpdated ->
            VcsStatus(event.local, current?.remote ?: VcsStatusRemote())

        is VcsStatusStreamEvent.RemoteUpdated ->
            VcsStatus(
                current?.local ?: VcsStatusLocal(isRepo = true),
                event.remote ?: VcsStatusRemote(),
            )

        is VcsStatusStreamEvent.Unsupported -> current
    }

// ------------------------------------------------------------------- refs

@Serializable
data class VcsRef(
    val name: String,
    val isRemote: Boolean = false,
    val remoteName: String? = null,
    val current: Boolean = false,
    val isDefault: Boolean = false,
    val worktreePath: String? = null,
)

@Serializable
data class VcsListRefsInput(
    val cwd: String,
    val refKind: String? = null,
    val limit: Int? = null,
)

@Serializable
data class VcsListRefsResult(
    val refs: List<VcsRef> = emptyList(),
    val isRepo: Boolean = false,
    val hasPrimaryRemote: Boolean = false,
    val nextCursor: Int? = null,
    val totalCount: Int = 0,
)

@Serializable
data class VcsCreateRefInput(
    val cwd: String,
    val refName: String,
    val switchRef: Boolean? = null,
)

@Serializable
data class VcsCreateRefResult(
    val refName: String,
)

// ------------------------------------------------------------------- pull

@Serializable
data class VcsCwdInput(
    val cwd: String,
)

/** `VcsPullResult.status` values. */
object VcsPullStatus {
    const val PULLED = "pulled"
    const val SKIPPED_UP_TO_DATE = "skipped_up_to_date"
}

@Serializable
data class VcsPullResult(
    val status: String,
    val refName: String,
    val upstreamRef: String? = null,
)

// ------------------------------------------------------------- stacked action

/** `GitStackedAction`. */
object GitStackedAction {
    const val COMMIT = "commit"
    const val PUSH = "push"
    const val CREATE_PR = "create_pr"
    const val COMMIT_PUSH = "commit_push"
    const val COMMIT_PUSH_PR = "commit_push_pr"
}

@Serializable
data class GitRunStackedActionInput(
    val actionId: String,
    val cwd: String,
    val action: String,
    val commitMessage: String? = null,
    val featureBranch: Boolean? = null,
    val filePaths: List<String>? = null,
)

@Serializable
data class GitActionBranchStep(
    /** `created` | `skipped_not_requested`. */
    val status: String,
    val name: String? = null,
)

@Serializable
data class GitActionCommitStep(
    /** `created` | `skipped_no_changes` | `skipped_not_requested`. */
    val status: String,
    val commitSha: String? = null,
    val subject: String? = null,
)

@Serializable
data class GitActionPushStep(
    /** `pushed` | `skipped_not_requested` | `skipped_up_to_date`. */
    val status: String,
    val branch: String? = null,
    val upstreamBranch: String? = null,
    val setUpstream: Boolean? = null,
)

@Serializable
data class GitActionPrStep(
    /** `created` | `opened_existing` | `skipped_not_requested`. */
    val status: String,
    val url: String? = null,
    val number: Int? = null,
    val baseBranch: String? = null,
    val headBranch: String? = null,
    val title: String? = null,
)

/** `GitRunStackedActionToast.cta` — a tagged union flattened onto one class. */
@Serializable
data class GitActionToastCta(
    val kind: String = "none",
    val label: String? = null,
    val url: String? = null,
) {
    companion object {
        const val KIND_NONE = "none"
        const val KIND_OPEN_PR = "open_pr"
        const val KIND_RUN_ACTION = "run_action"
    }
}

@Serializable
data class GitActionToast(
    val title: String,
    val description: String? = null,
    val cta: GitActionToastCta = GitActionToastCta(),
)

@Serializable
data class GitRunStackedActionResult(
    val action: String,
    val branch: GitActionBranchStep,
    val commit: GitActionCommitStep,
    val push: GitActionPushStep,
    val pr: GitActionPrStep,
    val toast: GitActionToast,
)

/**
 * Progress events streamed by `git.runStackedAction`, tagged by `kind`.
 *
 * Every variant carries [actionId] so a consumer can drop events that belong
 * to a different in-flight action on the same connection.
 */
sealed interface GitActionProgressEvent {
    val actionId: String

    data class ActionStarted(
        override val actionId: String,
        /** `branch` | `commit` | `push` | `pr`, in execution order. */
        val phases: List<String>,
    ) : GitActionProgressEvent

    data class PhaseStarted(
        override val actionId: String,
        val phase: String,
        val label: String,
    ) : GitActionProgressEvent

    data class HookStarted(
        override val actionId: String,
        val hookName: String,
    ) : GitActionProgressEvent

    data class HookOutput(
        override val actionId: String,
        val hookName: String?,
        /** `stdout` | `stderr`. */
        val stream: String,
        val text: String,
    ) : GitActionProgressEvent

    data class HookFinished(
        override val actionId: String,
        val hookName: String,
        val exitCode: Int?,
        val durationMs: Int?,
    ) : GitActionProgressEvent

    data class ActionFinished(
        override val actionId: String,
        val result: GitRunStackedActionResult,
    ) : GitActionProgressEvent

    data class ActionFailed(
        override val actionId: String,
        val phase: String?,
        val message: String,
    ) : GitActionProgressEvent

    data class Unsupported(
        override val actionId: String,
        val kind: String,
    ) : GitActionProgressEvent
}

// ------------------------------------------------------------------- decoding

/**
 * Decodes VCS stream payloads. Same tolerance rules as
 * [OrchestrationStreamDecoder]: a malformed *known* item throws, an unknown
 * discriminator degrades to `Unsupported`.
 */
class VcsStreamDecoder(private val json: Json) {

    fun decodeStatusEvent(element: JsonElement): VcsStatusStreamEvent {
        val obj = element as? JsonObject ?: throw OrchestrationDecodeException("vcs status event")
        return when (val tag = obj.stringOrNull("_tag")) {
            "snapshot" -> VcsStatusStreamEvent.Snapshot(
                local = decodeField(obj, "local"),
                remote = decodeNullableField(obj, "remote"),
            )

            "localUpdated" -> VcsStatusStreamEvent.LocalUpdated(decodeField(obj, "local"))
            "remoteUpdated" -> VcsStatusStreamEvent.RemoteUpdated(decodeNullableField(obj, "remote"))
            else -> VcsStatusStreamEvent.Unsupported(tag ?: "<missing>")
        }
    }

    fun decodeActionProgressEvent(element: JsonElement): GitActionProgressEvent {
        val obj = element as? JsonObject ?: throw OrchestrationDecodeException("git action event")
        val actionId = obj.stringOrNull("actionId") ?: ""
        return when (val kind = obj.stringOrNull("kind")) {
            "action_started" -> GitActionProgressEvent.ActionStarted(
                actionId = actionId,
                phases = (obj["phases"] as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.content }
                    .orEmpty(),
            )

            "phase_started" -> GitActionProgressEvent.PhaseStarted(
                actionId = actionId,
                phase = obj.stringOrNull("phase") ?: "",
                label = obj.stringOrNull("label") ?: "Working…",
            )

            "hook_started" -> GitActionProgressEvent.HookStarted(
                actionId = actionId,
                hookName = obj.stringOrNull("hookName") ?: "",
            )

            "hook_output" -> GitActionProgressEvent.HookOutput(
                actionId = actionId,
                hookName = obj.stringOrNull("hookName"),
                stream = obj.stringOrNull("stream") ?: "stdout",
                text = obj.stringOrNull("text") ?: "",
            )

            "hook_finished" -> GitActionProgressEvent.HookFinished(
                actionId = actionId,
                hookName = obj.stringOrNull("hookName") ?: "",
                exitCode = obj.intOrNull("exitCode"),
                durationMs = obj.intOrNull("durationMs"),
            )

            "action_finished" -> GitActionProgressEvent.ActionFinished(
                actionId = actionId,
                result = decodeField(obj, "result"),
            )

            "action_failed" -> GitActionProgressEvent.ActionFailed(
                actionId = actionId,
                phase = obj.stringOrNull("phase"),
                message = obj.stringOrNull("message") ?: "Git action failed.",
            )

            else -> GitActionProgressEvent.Unsupported(actionId, kind ?: "<missing>")
        }
    }

    private inline fun <reified T> decodeField(obj: JsonObject, key: String): T {
        val element = obj[key] ?: throw OrchestrationDecodeException(key)
        return runCatching {
            json.decodeFromJsonElement(kotlinx.serialization.serializer<T>(), element)
        }.getOrElse { throw OrchestrationDecodeException(key, it) }
    }

    private inline fun <reified T> decodeNullableField(obj: JsonObject, key: String): T? {
        val element = obj[key] ?: return null
        if (element is JsonNull) return null
        return runCatching {
            json.decodeFromJsonElement(kotlinx.serialization.serializer<T>(), element)
        }.getOrElse { throw OrchestrationDecodeException(key, it) }
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun JsonObject.intOrNull(key: String): Int? = stringOrNull(key)?.toIntOrNull()
}
