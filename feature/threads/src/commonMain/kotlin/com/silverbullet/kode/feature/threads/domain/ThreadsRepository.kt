package com.silverbullet.kode.feature.threads.domain

import com.silverbullet.kode.core.common.IdGenerator
import com.silverbullet.kode.core.common.TimeProvider
import com.silverbullet.kode.core.model.AssetResource
import com.silverbullet.kode.core.model.ClientOrchestrationCommand
import com.silverbullet.kode.core.model.DispatchResult
import com.silverbullet.kode.core.model.EnvironmentId
import com.silverbullet.kode.core.model.ExecutionEnvironmentCapabilities
import com.silverbullet.kode.core.model.MessageRole
import com.silverbullet.kode.core.model.ThreadId
import com.silverbullet.kode.core.model.ModelSelection
import com.silverbullet.kode.core.model.ProjectId
import com.silverbullet.kode.core.model.ThreadApprovalRespondCommand
import com.silverbullet.kode.core.model.ThreadInteractionModeSetCommand
import com.silverbullet.kode.core.model.ThreadMetaUpdateCommand
import com.silverbullet.kode.core.model.ThreadRuntimeModeSetCommand
import com.silverbullet.kode.core.model.ThreadSettleCommand
import com.silverbullet.kode.core.model.ThreadTurnInterruptCommand
import com.silverbullet.kode.core.model.ThreadTurnStartBootstrap
import com.silverbullet.kode.core.model.ThreadTurnStartBootstrapCreateThread
import com.silverbullet.kode.core.model.ThreadTurnStartCommand
import com.silverbullet.kode.core.model.ThreadUserInputRespondCommand
import com.silverbullet.kode.core.model.UploadChatImageAttachment
import com.silverbullet.kode.core.model.UserMessageInput
import com.silverbullet.kode.core.network.T3EnvironmentClient
import com.silverbullet.kode.core.session.ConnectionState
import com.silverbullet.kode.core.session.EnvironmentFleet
import com.silverbullet.kode.core.session.EnvironmentHandle
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement

/**
 * One environment's slice of the merged thread list: who it is, whether it is
 * reachable, and its projects/threads read model.
 */
data class EnvironmentShell(
    val environmentId: EnvironmentId,
    val label: String,
    val connection: ConnectionState,
    val shell: ShellState,
    /**
     * What this server admits to understanding. Absent flags mean unsupported,
     * so a feature gated on one stays hidden under version skew rather than
     * dispatching a command the server would reject.
     */
    val capabilities: ExecutionEnvironmentCapabilities = ExecutionEnvironmentCapabilities(),
) {
    val isConnected: Boolean get() = connection is ConnectionState.Connected
}

/**
 * Live orchestration state across every connected environment.
 *
 * `flatMapLatest` over the fleet's sessions is what makes these subscriptions
 * durable across reconnects: when a supervisor swaps in a replacement client,
 * the old stream is cancelled and a fresh subscription opens against the new
 * socket. Nothing here retries — that would duplicate the supervisors' job and
 * fight their backoff.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadsRepository(
    private val fleet: EnvironmentFleet,
    private val idGenerator: IdGenerator,
    private val timeProvider: TimeProvider,
    appScope: CoroutineScope,
) {

    /**
     * Every environment's projects and threads, in catalog order.
     *
     * Shared in [appScope] so the thread list and the new-thread form observe
     * one shell subscription per environment instead of opening one each —
     * with several environments connected, duplicate subscriptions would
     * multiply socket traffic for identical data. `WhileSubscribed` still
     * closes the streams shortly after the last screen leaves.
     */
    val shells: Flow<List<EnvironmentShell>> = fleet.environments
        .flatMapLatest { handles ->
            val list = handles.orEmpty()
            if (list.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(list.map { it.environmentShell() }) { it.toList() }
            }
        }
        .shareIn(
            scope = appScope,
            started = SharingStarted.WhileSubscribed(SHELL_STOP_TIMEOUT_MILLIS),
            replay = 1,
        )

    private fun EnvironmentHandle.environmentShell(): Flow<EnvironmentShell> {
        val shellStates = session.flatMapLatest { client ->
            if (client == null) {
                // No session: report "not synchronized" rather than stale data.
                flowOf(ShellState())
            } else {
                client.subscribeShell()
                    .scan(ShellState(status = SyncStatus.Synchronizing)) { state, item ->
                        state.reduce(item)
                    }
                    .catchSubscriptionFailure { message ->
                        ShellState(status = SyncStatus.Empty, error = message)
                    }
            }
        }
        return combine(state, shellStates, serverConfig) { connection, shell, config ->
            EnvironmentShell(
                environmentId = record.environmentId,
                label = record.label,
                connection = connection,
                shell = shell,
                // Null until the handshake lands, which reads as "no optional
                // capabilities" — the same conservative default as an old
                // server, and it flips to the real set on the next emission.
                capabilities = config?.environment?.capabilities
                    ?: ExecutionEnvironmentCapabilities(),
            )
        }
    }

    /** One thread's timeline on one environment. */
    fun thread(environmentId: EnvironmentId, threadId: ThreadId): Flow<ThreadDetailState> =
        fleet.sessionFor(environmentId).flatMapLatest { client ->
            if (client == null) {
                flowOf(ThreadDetailState())
            } else {
                client.subscribeThread(threadId)
                    .scan(
                        ThreadDetailState(status = SyncStatus.Synchronizing),
                    ) { state, item -> state.reduce(item) }
                    .catchSubscriptionFailure { message ->
                        ThreadDetailState(
                            status = SyncStatus.Empty,
                            error = message,
                        )
                    }
            }
        }

    /**
     * Sends user input into an existing thread.
     *
     * [runtimeMode] and [interactionMode] are taken from the thread rather than
     * defaulted: forcing `approval-required` would strand the turn behind an
     * approval this client cannot answer yet.
     */
    suspend fun sendMessage(
        environmentId: EnvironmentId,
        threadId: ThreadId,
        text: String,
        runtimeMode: String,
        interactionMode: String,
        attachments: List<UploadChatImageAttachment> = emptyList(),
    ): Result<DispatchResult> = runCatchingCancellable {
        connectedClient(environmentId).dispatchCommand(
            ThreadTurnStartCommand(
                commandId = idGenerator.newId(),
                threadId = threadId,
                message = UserMessageInput(
                    messageId = idGenerator.newId(),
                    text = text,
                    role = MessageRole.USER,
                    attachments = attachments,
                ),
                runtimeMode = runtimeMode,
                interactionMode = interactionMode,
                createdAt = timeProvider.nowIso(),
            ) as ClientOrchestrationCommand,
        )
    }

    /** One environment's provider catalogue, empty until its config arrives. */
    fun catalog(environmentId: EnvironmentId): Flow<ProviderCatalog> =
        fleet.serverConfigFor(environmentId).map { it?.providers.orEmpty().toCatalog() }

    /**
     * Creates a thread on [environmentId] and runs [text] as its first turn,
     * returning the id.
     *
     * This mirrors T3 Code's mobile client: one `thread.turn.start` carrying a
     * `bootstrap.createThread` payload, with the title derived from the prompt
     * and passed as `titleSeed` so the server can regenerate a better one.
     *
     * The id is generated here rather than by the server: the command carries
     * it, so the caller can navigate to the thread without waiting for the
     * shell subscription to catch up.
     */
    suspend fun startThread(
        environmentId: EnvironmentId,
        projectId: ProjectId,
        text: String,
        modelSelection: ModelSelection,
        runtimeMode: String,
        interactionMode: String,
        attachments: List<UploadChatImageAttachment> = emptyList(),
    ): Result<ThreadId> = runCatchingCancellable {
        val client = connectedClient(environmentId)
        val threadId = ThreadId(idGenerator.newId())
        val title = deriveThreadTitleFromPrompt(text)
        val createdAt = timeProvider.nowIso()

        client.dispatchCommand(
            ThreadTurnStartCommand(
                commandId = idGenerator.newId(),
                threadId = threadId,
                message = UserMessageInput(
                    messageId = idGenerator.newId(),
                    text = text,
                    role = MessageRole.USER,
                    attachments = attachments,
                ),
                modelSelection = modelSelection,
                titleSeed = title,
                runtimeMode = runtimeMode,
                interactionMode = interactionMode,
                bootstrap = ThreadTurnStartBootstrap(
                    createThread = ThreadTurnStartBootstrapCreateThread(
                        projectId = projectId,
                        title = title,
                        modelSelection = modelSelection,
                        runtimeMode = runtimeMode,
                        interactionMode = interactionMode,
                        createdAt = createdAt,
                    ),
                ),
                createdAt = createdAt,
            ) as ClientOrchestrationCommand,
        )
        threadId
    }

    suspend fun setModelSelection(
        environmentId: EnvironmentId,
        threadId: ThreadId,
        modelSelection: ModelSelection,
    ): Result<DispatchResult> = runCatchingCancellable {
        connectedClient(environmentId).dispatchCommand(
            ThreadMetaUpdateCommand(
                commandId = idGenerator.newId(),
                threadId = threadId,
                modelSelection = modelSelection,
            ) as ClientOrchestrationCommand,
        )
    }

    suspend fun setRuntimeMode(
        environmentId: EnvironmentId,
        threadId: ThreadId,
        runtimeMode: String,
    ): Result<DispatchResult> = runCatchingCancellable {
        connectedClient(environmentId).dispatchCommand(
            ThreadRuntimeModeSetCommand(
                commandId = idGenerator.newId(),
                threadId = threadId,
                runtimeMode = runtimeMode,
                createdAt = timeProvider.nowIso(),
            ) as ClientOrchestrationCommand,
        )
    }

    suspend fun setInteractionMode(
        environmentId: EnvironmentId,
        threadId: ThreadId,
        interactionMode: String,
    ): Result<DispatchResult> = runCatchingCancellable {
        connectedClient(environmentId).dispatchCommand(
            ThreadInteractionModeSetCommand(
                commandId = idGenerator.newId(),
                threadId = threadId,
                interactionMode = interactionMode,
                createdAt = timeProvider.nowIso(),
            ) as ClientOrchestrationCommand,
        )
    }

    /** Stops the running turn. */
    suspend fun interruptTurn(
        environmentId: EnvironmentId,
        threadId: ThreadId,
        turnId: String?,
    ): Result<DispatchResult> = runCatchingCancellable {
        connectedClient(environmentId).dispatchCommand(
            ThreadTurnInterruptCommand(
                commandId = idGenerator.newId(),
                threadId = threadId,
                turnId = turnId,
                createdAt = timeProvider.nowIso(),
            ) as ClientOrchestrationCommand,
        )
    }

    /**
     * Files a thread away as finished business.
     *
     * Nothing is written locally: the server projects `settledOverride` and
     * echoes the thread over the shell subscription, which is what moves the
     * row into the settled shelf. Failing loudly is the point — the decider
     * rejects a settle whose preconditions changed under us.
     */
    suspend fun settleThread(
        environmentId: EnvironmentId,
        threadId: ThreadId,
    ): Result<DispatchResult> = runCatchingCancellable {
        connectedClient(environmentId).dispatchCommand(
            ThreadSettleCommand(
                commandId = idGenerator.newId(),
                threadId = threadId,
            ) as ClientOrchestrationCommand,
        )
    }

    /** Decides a pending approval request. */
    suspend fun respondToApproval(
        environmentId: EnvironmentId,
        threadId: ThreadId,
        requestId: String,
        decision: String,
    ): Result<DispatchResult> = runCatchingCancellable {
        connectedClient(environmentId).dispatchCommand(
            ThreadApprovalRespondCommand(
                commandId = idGenerator.newId(),
                threadId = threadId,
                requestId = requestId,
                decision = decision,
                createdAt = timeProvider.nowIso(),
            ) as ClientOrchestrationCommand,
        )
    }

    /** Answers a pending user-input request. */
    suspend fun respondToUserInput(
        environmentId: EnvironmentId,
        threadId: ThreadId,
        requestId: String,
        answers: Map<String, JsonElement>,
    ): Result<DispatchResult> = runCatchingCancellable {
        connectedClient(environmentId).dispatchCommand(
            ThreadUserInputRespondCommand(
                commandId = idGenerator.newId(),
                threadId = threadId,
                requestId = requestId,
                answers = answers,
                createdAt = timeProvider.nowIso(),
            ) as ClientOrchestrationCommand,
        )
    }

    /**
     * An absolute, directly fetchable URL for one attachment, or null when the
     * environment is unreachable or the asset is gone.
     *
     * Two things make this worth caching. The URL is signed and valid for an
     * hour, so re-requesting it per recomposition would be a round trip for an
     * answer we already have; and the feed scrolls, so the same handful of
     * attachments are asked for again and again. Entries are expired a little
     * before the server's TTL so a URL is never handed out moments before it
     * dies.
     */
    suspend fun attachmentUrl(
        environmentId: EnvironmentId,
        attachmentId: String,
    ): String? {
        val key = environmentId.value + ":" + attachmentId
        val now = timeProvider.nowMillis()
        assetUrlCacheLock.withLock {
            assetUrlCache[key]?.takeIf { it.expiresAtMillis > now }?.let { return it.url }
        }

        val client = fleet.sessionNow(environmentId) ?: return null
        val record = fleet.recordNow(environmentId) ?: return null
        val result = runCatching {
            client.createAssetUrl(AssetResource(attachmentId = attachmentId))
        }.getOrNull() ?: return null

        val url = resolveAgainstBase(record.httpBaseUrl, result.relativeUrl) ?: return null
        assetUrlCacheLock.withLock {
            assetUrlCache[key] = CachedAssetUrl(
                url = url,
                // The server's own expiry, pulled in so a URL is never served
                // right on its deadline.
                expiresAtMillis = result.expiresAt - ASSET_URL_EXPIRY_MARGIN_MILLIS,
            )
        }
        return url
    }

    private class CachedAssetUrl(val url: String, val expiresAtMillis: Long)

    private val assetUrlCache = mutableMapOf<String, CachedAssetUrl>()
    private val assetUrlCacheLock = Mutex()

    private fun connectedClient(environmentId: EnvironmentId): T3EnvironmentClient =
        fleet.sessionNow(environmentId)
            ?: error("Not connected to this environment.")

    private companion object {
        const val SHELL_STOP_TIMEOUT_MILLIS = 5_000L

        /** Retire a signed asset URL a minute before the server would. */
        const val ASSET_URL_EXPIRY_MARGIN_MILLIS = 60_000L
    }
}

/**
 * Turns a subscription failure into a terminal error state.
 *
 * The stream is *not* resubscribed here. A transport failure means the
 * supervisor is already reconnecting and will emit a replacement session; a
 * domain failure would only fail again. Retrying in this layer would produce a
 * hot loop against a healthy socket.
 */
private fun <T> Flow<T>.catchSubscriptionFailure(onFailure: (String) -> T): Flow<T> =
    catch { failure -> emit(onFailure(failure.message ?: "Subscription failed.")) }

private inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (failure: Throwable) {
    Result.failure(failure)
}

/**
 * Joins a server-issued relative asset URL onto an environment's HTTP base.
 *
 * Deliberately not a URL parser: the contract guarantees a root-relative path
 * (`/api/assets/<token>/<name>`), so this is a string join that tolerates a
 * base with or without a trailing slash. Anything that is already absolute is
 * passed through untouched.
 */
internal fun resolveAgainstBase(httpBaseUrl: String, relativeUrl: String): String? {
    if (relativeUrl.isBlank()) return null
    if (relativeUrl.startsWith("http://") || relativeUrl.startsWith("https://")) return relativeUrl

    val base = httpBaseUrl.trimEnd('/')
    if (base.isEmpty()) return null
    return base + "/" + relativeUrl.trimStart('/')
}
