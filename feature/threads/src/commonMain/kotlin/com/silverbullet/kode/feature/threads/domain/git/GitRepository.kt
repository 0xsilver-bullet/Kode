package com.silverbullet.kode.feature.threads.domain.git

import com.silverbullet.kode.core.common.IdGenerator
import com.silverbullet.kode.core.model.ClientOrchestrationCommand
import com.silverbullet.kode.core.model.EnvironmentId
import com.silverbullet.kode.core.model.GetFullThreadDiffInput
import com.silverbullet.kode.core.model.GetTurnDiffInput
import com.silverbullet.kode.core.model.GitActionProgressEvent
import com.silverbullet.kode.core.model.GitRunStackedActionInput
import com.silverbullet.kode.core.model.ReviewDiffPreviewResult
import com.silverbullet.kode.core.model.ThreadId
import com.silverbullet.kode.core.model.ThreadMetaUpdateCommand
import com.silverbullet.kode.core.model.ThreadTurnDiff
import com.silverbullet.kode.core.model.VcsCreateRefInput
import com.silverbullet.kode.core.model.VcsListRefsInput
import com.silverbullet.kode.core.model.VcsPullResult
import com.silverbullet.kode.core.model.VcsStatus
import com.silverbullet.kode.core.model.applyVcsStatusStreamEvent
import com.silverbullet.kode.core.network.T3EnvironmentClient
import com.silverbullet.kode.core.rpc.RpcCallException
import com.silverbullet.kode.core.session.EnvironmentFleet
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.scan
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The git slice of the RPC surface, one call per t3code atom command.
 *
 * Status is a *subscription*, not a poll: the server pushes `localUpdated`
 * after every mutating git RPC and every agent turn, and runs its own remote
 * poller — mirroring t3code's `VcsStatusBroadcaster`. This class only folds
 * the stream; retry/reconnect stays with the environment supervisor, exactly
 * like [com.silverbullet.kode.feature.threads.domain.ThreadsRepository].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GitRepository(
    private val fleet: EnvironmentFleet,
    private val idGenerator: IdGenerator,
) {

    /**
     * One directory's folded git status; null while disconnected, before the
     * first snapshot, or after a subscription failure (the UI reads null as
     * "status unavailable", which is also what t3code shows).
     */
    fun vcsStatus(environmentId: EnvironmentId, cwd: String): Flow<VcsStatus?> =
        fleet.sessionFor(environmentId).flatMapLatest { client ->
            if (client == null) {
                flowOf(null)
            } else {
                client.subscribeVcsStatus(cwd)
                    .scan(null as VcsStatus?) { current, event ->
                        applyVcsStatusStreamEvent(current, event)
                    }
                    .catch { emit(null) }
            }
        }

    /** Fire-and-forget refresh; the result arrives over [vcsStatus]. */
    suspend fun refreshStatus(environmentId: EnvironmentId, cwd: String): Result<Unit> =
        runCatchingCancellable { connectedClient(environmentId).refreshVcsStatus(cwd) }

    suspend fun pull(environmentId: EnvironmentId, cwd: String): Result<VcsPullResult> =
        runCatchingCancellable { connectedClient(environmentId).pull(cwd) }

    /**
     * The streaming commit/push/PR pipeline. Collectors must treat
     * `action_finished`/`action_failed` as terminal; a stream ending with
     * neither means the transport died mid-action.
     */
    fun runStackedAction(
        environmentId: EnvironmentId,
        input: GitRunStackedActionInput,
    ): Flow<GitActionProgressEvent> =
        connectedClient(environmentId).runStackedGitAction(input)

    /** Local branch names, for uniquifying an auto feature-branch name. */
    suspend fun localBranchNames(
        environmentId: EnvironmentId,
        cwd: String,
    ): Result<List<String>> = runCatchingCancellable {
        connectedClient(environmentId)
            .listRefs(VcsListRefsInput(cwd = cwd, refKind = "local", limit = 100))
            .refs
            .filterNot { it.isRemote }
            .map { it.name }
    }

    /** Creates and checks out a branch, returning its final name. */
    suspend fun createAndSwitchBranch(
        environmentId: EnvironmentId,
        cwd: String,
        refName: String,
    ): Result<String> = runCatchingCancellable {
        connectedClient(environmentId)
            .createRef(VcsCreateRefInput(cwd = cwd, refName = refName, switchRef = true))
            .refName
    }

    /**
     * Points the thread at a branch it was moved to, so the shell row and the
     * header subtitle follow — t3code's `syncSelectedThreadBranchState`.
     */
    suspend fun updateThreadBranch(
        environmentId: EnvironmentId,
        threadId: ThreadId,
        branch: String,
        worktreePath: String?,
    ): Result<Unit> = runCatchingCancellable {
        connectedClient(environmentId).dispatchCommand(
            ThreadMetaUpdateCommand(
                commandId = idGenerator.newId(),
                threadId = threadId,
                branch = branch,
                worktreePath = worktreePath,
            ) as ClientOrchestrationCommand,
        )
    }

    suspend fun diffPreview(
        environmentId: EnvironmentId,
        cwd: String,
    ): Result<ReviewDiffPreviewResult> =
        runCatchingCancellable { connectedClient(environmentId).getDiffPreview(cwd) }

    /**
     * The diff one checkpoint captures: turns `(count-1, count]`, or the full
     * thread diff for the very first turn — the same split t3code's
     * `useCheckpointDiff` makes.
     */
    suspend fun checkpointDiff(
        environmentId: EnvironmentId,
        threadId: ThreadId,
        checkpointTurnCount: Int,
    ): Result<ThreadTurnDiff> = runCatchingCancellable {
        val client = connectedClient(environmentId)
        val fromTurnCount = (checkpointTurnCount - 1).coerceAtLeast(0)
        if (fromTurnCount == 0) {
            client.getFullThreadDiff(
                GetFullThreadDiffInput(threadId = threadId, toTurnCount = checkpointTurnCount),
            )
        } else {
            client.getTurnDiff(
                GetTurnDiffInput(
                    threadId = threadId,
                    fromTurnCount = fromTurnCount,
                    toTurnCount = checkpointTurnCount,
                ),
            )
        }
    }

    private fun connectedClient(environmentId: EnvironmentId): T3EnvironmentClient =
        fleet.sessionNow(environmentId)
            ?: error("Not connected to this environment.")

    private inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        Result.failure(failure)
    }
}

/**
 * A human-readable message for a failed git call.
 *
 * The server's error taxonomy (`GitCommandError`, `GitManagerError`, …) carries
 * the useful text in `detail` or `message`; the raw `RpcCallException` message
 * is the whole JSON payload, which is not something to put in a toast.
 */
fun gitFailureMessage(failure: Throwable, fallback: String = "Git action failed."): String {
    val call = failure as? RpcCallException ?: return failure.message ?: fallback
    val error = call.error as? JsonObject ?: return fallback
    for (key in listOf("detail", "message", "reason")) {
        val value = (error[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (!value.isNullOrBlank()) return value
    }
    return fallback
}
