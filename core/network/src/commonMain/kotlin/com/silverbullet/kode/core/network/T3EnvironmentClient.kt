package com.silverbullet.kode.core.network

import com.silverbullet.kode.core.model.AssetCreateUrlInput
import com.silverbullet.kode.core.model.AssetCreateUrlResult
import com.silverbullet.kode.core.model.AssetResource
import com.silverbullet.kode.core.model.ClientOrchestrationCommand
import com.silverbullet.kode.core.model.DispatchResult
import com.silverbullet.kode.core.model.GetFullThreadDiffInput
import com.silverbullet.kode.core.model.GetTurnDiffInput
import com.silverbullet.kode.core.model.GitActionProgressEvent
import com.silverbullet.kode.core.model.GitRunStackedActionInput
import com.silverbullet.kode.core.model.OrchestrationStreamDecoder
import com.silverbullet.kode.core.model.ReviewDiffPreviewInput
import com.silverbullet.kode.core.model.ReviewDiffPreviewResult
import com.silverbullet.kode.core.model.ServerConfig
import com.silverbullet.kode.core.model.ShellStreamItem
import com.silverbullet.kode.core.model.ThreadId
import com.silverbullet.kode.core.model.ThreadStreamItem
import com.silverbullet.kode.core.model.ThreadTurnDiff
import com.silverbullet.kode.core.model.VcsCreateRefInput
import com.silverbullet.kode.core.model.VcsCreateRefResult
import com.silverbullet.kode.core.model.VcsCwdInput
import com.silverbullet.kode.core.model.VcsListRefsInput
import com.silverbullet.kode.core.model.VcsListRefsResult
import com.silverbullet.kode.core.model.VcsPullResult
import com.silverbullet.kode.core.model.VcsStatusStreamEvent
import com.silverbullet.kode.core.model.VcsStreamDecoder
import com.silverbullet.kode.core.rpc.RpcConnection
import com.silverbullet.kode.core.rpc.RpcProtocolException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Typed facade over the untyped [RpcConnection], one function per RPC method.
 *
 * Method names mirror `WS_METHODS` in `packages/contracts/src/rpc.ts` and
 * `ORCHESTRATION_WS_METHODS` in `orchestration.ts`. Only the methods the app
 * actually calls are declared; the surface grows as features land.
 */
class T3EnvironmentClient(
    private val connection: RpcConnection,
    private val json: Json = ContractJson,
) {
    private val decoder = OrchestrationStreamDecoder(json)
    private val vcsDecoder = VcsStreamDecoder(json)

    /**
     * `server.getConfig` — the first call on every session. Its success is what
     * proves the server is responsive, which is why the connection is not
     * reported as `connected` until it returns.
     */
    suspend fun getConfig(): ServerConfig = decode(
        connection.request(Methods.SERVER_GET_CONFIG),
        Methods.SERVER_GET_CONFIG,
    )

    /**
     * A cheap liveness check, used when the app returns to the foreground
     * instead of tearing down a healthy socket.
     *
     * `server.probe` exists only on servers that advertise the
     * `connectionProbe` capability. Calling it unconditionally would fail on
     * older servers and be read as a dead session, so this falls back to
     * `server.getConfig`, which every server has — the same choice
     * `RpcSessionFactory` makes.
     */
    suspend fun probe(config: ServerConfig) {
        if (config.environment.capabilities.connectionProbe) {
            connection.request(Methods.SERVER_PROBE)
        } else {
            connection.request(Methods.SERVER_GET_CONFIG)
        }
    }

    /**
     * `orchestration.subscribeShell` — projects and threads, as an initial
     * snapshot followed by live upsert/remove events.
     *
     * We deliberately omit `afterSequence`, so the server always sends a full
     * snapshot. That costs bandwidth on reconnect but bounds how far our
     * partial event handling can drift from the truth. See `ROADMAP.md`.
     */
    fun subscribeShell(): Flow<ShellStreamItem> =
        connection.stream(Methods.SUBSCRIBE_SHELL)
            .map(decoder::decodeShellItem)

    /**
     * `orchestration.subscribeThread` — one thread's detail snapshot followed by
     * its domain events.
     */
    fun subscribeThread(threadId: ThreadId): Flow<ThreadStreamItem> =
        connection.stream(
            Methods.SUBSCRIBE_THREAD,
            buildJsonObject { put("threadId", threadId.value) },
        ).map(decoder::decodeThreadItem)

    /**
     * `orchestration.dispatchCommand` — requests a state change.
     *
     * Acceptance is not application success: the command is turned into events
     * by the server's decider, and the results arrive over [subscribeThread].
     */
    suspend fun dispatchCommand(command: ClientOrchestrationCommand): DispatchResult = decode(
        connection.request(
            Methods.DISPATCH_COMMAND,
            json.encodeToJsonElement(ClientOrchestrationCommand.serializer(), command),
        ),
        Methods.DISPATCH_COMMAND,
    )

    /**
     * `assets.createUrl` — a short-lived, signed URL for one attachment.
     *
     * The returned [AssetCreateUrlResult.relativeUrl] is relative to the
     * environment's HTTP base and carries its own signature, so fetching it
     * needs no bearer header. It expires after an hour, which is why callers
     * cache the URL rather than treat it as stable.
     */
    suspend fun createAssetUrl(resource: AssetResource): AssetCreateUrlResult = decode(
        connection.request(
            Methods.ASSETS_CREATE_URL,
            json.encodeToJsonElement(
                AssetCreateUrlInput.serializer(),
                AssetCreateUrlInput(resource),
            ),
        ),
        Methods.ASSETS_CREATE_URL,
    )

    // ---------------------------------------------------------------------- git

    /**
     * `subscribeVcsStatus` — one directory's git status, as a first snapshot
     * followed by `localUpdated`/`remoteUpdated` events. The server refreshes
     * the local half after every mutating git RPC and after every agent turn,
     * and polls the remote half itself — the client never polls.
     */
    fun subscribeVcsStatus(cwd: String): Flow<VcsStatusStreamEvent> =
        connection.stream(
            Methods.SUBSCRIBE_VCS_STATUS,
            json.encodeToJsonElement(VcsCwdInput.serializer(), VcsCwdInput(cwd)),
        ).map(vcsDecoder::decodeStatusEvent)

    /**
     * `vcs.refreshStatus` — asks the server to re-derive status now. The fresh
     * value arrives over [subscribeVcsStatus] as a `snapshot`, so the returned
     * payload is deliberately dropped.
     */
    suspend fun refreshVcsStatus(cwd: String) {
        connection.request(
            Methods.VCS_REFRESH_STATUS,
            json.encodeToJsonElement(VcsCwdInput.serializer(), VcsCwdInput(cwd)),
        )
    }

    /** `vcs.pull` — pulls the current branch's upstream. */
    suspend fun pull(cwd: String): VcsPullResult = decode(
        connection.request(
            Methods.VCS_PULL,
            json.encodeToJsonElement(VcsCwdInput.serializer(), VcsCwdInput(cwd)),
        ),
        Methods.VCS_PULL,
    )

    /** `vcs.listRefs` — branch list, local-only by default. */
    suspend fun listRefs(input: VcsListRefsInput): VcsListRefsResult = decode(
        connection.request(
            Methods.VCS_LIST_REFS,
            json.encodeToJsonElement(VcsListRefsInput.serializer(), input),
        ),
        Methods.VCS_LIST_REFS,
    )

    /** `vcs.createRef` — creates (and with `switchRef` checks out) a branch. */
    suspend fun createRef(input: VcsCreateRefInput): VcsCreateRefResult = decode(
        connection.request(
            Methods.VCS_CREATE_REF,
            json.encodeToJsonElement(VcsCreateRefInput.serializer(), input),
        ),
        Methods.VCS_CREATE_REF,
    )

    /**
     * `git.runStackedAction` — commit/push/PR as one server-orchestrated
     * pipeline, streamed as progress events. The stream ends after an
     * `action_finished` or `action_failed` event; a stream that ends with
     * neither means the transport died mid-action.
     */
    fun runStackedGitAction(input: GitRunStackedActionInput): Flow<GitActionProgressEvent> =
        connection.stream(
            Methods.GIT_RUN_STACKED_ACTION,
            json.encodeToJsonElement(GitRunStackedActionInput.serializer(), input),
        ).map(vcsDecoder::decodeActionProgressEvent)

    // ------------------------------------------------------------------- review

    /**
     * `review.getDiffPreview` — the working-tree and branch-range diffs for a
     * directory, as unified diff text (120 KB cap per source, marked
     * `truncated`).
     */
    suspend fun getDiffPreview(cwd: String): ReviewDiffPreviewResult = decode(
        connection.request(
            Methods.REVIEW_GET_DIFF_PREVIEW,
            json.encodeToJsonElement(ReviewDiffPreviewInput.serializer(), ReviewDiffPreviewInput(cwd)),
        ),
        Methods.REVIEW_GET_DIFF_PREVIEW,
    )

    /** `orchestration.getTurnDiff` — the diff one turn range produced. */
    suspend fun getTurnDiff(input: GetTurnDiffInput): ThreadTurnDiff = decode(
        connection.request(
            Methods.GET_TURN_DIFF,
            json.encodeToJsonElement(GetTurnDiffInput.serializer(), input),
        ),
        Methods.GET_TURN_DIFF,
    )

    /** `orchestration.getFullThreadDiff` — the thread's cumulative diff. */
    suspend fun getFullThreadDiff(input: GetFullThreadDiffInput): ThreadTurnDiff = decode(
        connection.request(
            Methods.GET_FULL_THREAD_DIFF,
            json.encodeToJsonElement(GetFullThreadDiffInput.serializer(), input),
        ),
        Methods.GET_FULL_THREAD_DIFF,
    )

    // ------------------------------------------------------------------ helpers

    private inline fun <reified T> decode(element: JsonElement?, method: String): T {
        val payload = element
            ?: throw RpcProtocolException("`$method` returned no payload.")
        return runCatching { json.decodeFromJsonElement(serializer<T>(), payload) }
            .getOrElse { throw RpcProtocolException("Could not decode the `$method` result.", it) }
    }

    object Methods {
        const val SERVER_GET_CONFIG = "server.getConfig"
        const val SERVER_PROBE = "server.probe"

        const val SUBSCRIBE_SHELL = "orchestration.subscribeShell"
        const val SUBSCRIBE_THREAD = "orchestration.subscribeThread"
        const val DISPATCH_COMMAND = "orchestration.dispatchCommand"

        const val ASSETS_CREATE_URL = "assets.createUrl"

        const val SUBSCRIBE_VCS_STATUS = "subscribeVcsStatus"
        const val VCS_REFRESH_STATUS = "vcs.refreshStatus"
        const val VCS_PULL = "vcs.pull"
        const val VCS_LIST_REFS = "vcs.listRefs"
        const val VCS_CREATE_REF = "vcs.createRef"
        const val GIT_RUN_STACKED_ACTION = "git.runStackedAction"

        const val REVIEW_GET_DIFF_PREVIEW = "review.getDiffPreview"
        const val GET_TURN_DIFF = "orchestration.getTurnDiff"
        const val GET_FULL_THREAD_DIFF = "orchestration.getFullThreadDiff"
    }

    companion object {
        val EmptyPayload: JsonElement = JsonObject(emptyMap())
    }
}
