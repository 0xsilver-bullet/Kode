package com.silverbullet.kode.feature.threads.domain

import com.silverbullet.kode.core.common.IdGenerator
import com.silverbullet.kode.core.common.TimeProvider
import com.silverbullet.kode.core.model.ClientOrchestrationCommand
import com.silverbullet.kode.core.model.DispatchResult
import com.silverbullet.kode.core.model.MessageRole
import com.silverbullet.kode.core.model.ThreadId
import com.silverbullet.kode.core.model.ModelSelection
import com.silverbullet.kode.core.model.ProjectId
import com.silverbullet.kode.core.model.ThreadApprovalRespondCommand
import com.silverbullet.kode.core.model.ThreadInteractionModeSetCommand
import com.silverbullet.kode.core.model.ThreadMetaUpdateCommand
import com.silverbullet.kode.core.model.ThreadRuntimeModeSetCommand
import com.silverbullet.kode.core.model.ThreadTurnInterruptCommand
import com.silverbullet.kode.core.model.ThreadTurnStartBootstrap
import com.silverbullet.kode.core.model.ThreadTurnStartBootstrapCreateThread
import com.silverbullet.kode.core.model.ThreadTurnStartCommand
import com.silverbullet.kode.core.model.ThreadUserInputRespondCommand
import com.silverbullet.kode.core.model.UserMessageInput
import com.silverbullet.kode.core.network.T3EnvironmentClient
import kotlinx.serialization.json.JsonElement
import com.silverbullet.kode.core.session.EnvironmentSupervisor
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan

/**
 * Live orchestration state, scoped to whichever session is currently up.
 *
 * `flatMapLatest` over [EnvironmentSupervisor.session] is what makes these
 * subscriptions durable across reconnects: when the supervisor swaps in a
 * replacement client, the old stream is cancelled and a fresh subscription
 * opens against the new socket. Nothing here retries — that would duplicate the
 * supervisor's job and fight its backoff.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadsRepository(
    private val supervisor: EnvironmentSupervisor,
    private val idGenerator: IdGenerator,
    private val timeProvider: TimeProvider,
) {

    /** Projects and threads for the thread list. */
    val shell: Flow<ShellState> = supervisor.session.flatMapLatest { client ->
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

    /** One thread's timeline. */
    fun thread(threadId: ThreadId): Flow<ThreadDetailState> =
        supervisor.session.flatMapLatest { client ->
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
        threadId: ThreadId,
        text: String,
        runtimeMode: String,
        interactionMode: String,
    ): Result<DispatchResult> = runCatchingCancellable {
        val client = supervisor.session.value
            ?: error("Not connected to an environment.")

        client.dispatchCommand(
            ThreadTurnStartCommand(
                commandId = idGenerator.newId(),
                threadId = threadId,
                message = UserMessageInput(
                    messageId = idGenerator.newId(),
                    text = text,
                    role = MessageRole.USER,
                ),
                runtimeMode = runtimeMode,
                interactionMode = interactionMode,
                createdAt = timeProvider.nowIso(),
            ) as ClientOrchestrationCommand,
        )
    }

    /** The live provider catalogue, empty until a session reports its config. */
    val catalog: Flow<ProviderCatalog> =
        supervisor.serverConfig.map { it?.providers.orEmpty().toCatalog() }

    /**
     * Creates a thread and runs [text] as its first turn, returning the id.
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
        projectId: ProjectId,
        text: String,
        modelSelection: ModelSelection,
        runtimeMode: String,
        interactionMode: String,
    ): Result<ThreadId> = runCatchingCancellable {
        val client = supervisor.session.value
            ?: error("Not connected to an environment.")
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
        threadId: ThreadId,
        modelSelection: ModelSelection,
    ): Result<DispatchResult> = runCatchingCancellable {
        val client = supervisor.session.value
            ?: error("Not connected to an environment.")

        client.dispatchCommand(
            ThreadMetaUpdateCommand(
                commandId = idGenerator.newId(),
                threadId = threadId,
                modelSelection = modelSelection,
            ) as ClientOrchestrationCommand,
        )
    }

    suspend fun setRuntimeMode(threadId: ThreadId, runtimeMode: String): Result<DispatchResult> =
        runCatchingCancellable {
            val client = supervisor.session.value
                ?: error("Not connected to an environment.")

            client.dispatchCommand(
                ThreadRuntimeModeSetCommand(
                    commandId = idGenerator.newId(),
                    threadId = threadId,
                    runtimeMode = runtimeMode,
                    createdAt = timeProvider.nowIso(),
                ) as ClientOrchestrationCommand,
            )
        }

    suspend fun setInteractionMode(
        threadId: ThreadId,
        interactionMode: String,
    ): Result<DispatchResult> = runCatchingCancellable {
        val client = supervisor.session.value
            ?: error("Not connected to an environment.")

        client.dispatchCommand(
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
        threadId: ThreadId,
        turnId: String?,
    ): Result<DispatchResult> = runCatchingCancellable {
        val client = supervisor.session.value
            ?: error("Not connected to an environment.")

        client.dispatchCommand(
            ThreadTurnInterruptCommand(
                commandId = idGenerator.newId(),
                threadId = threadId,
                turnId = turnId,
                createdAt = timeProvider.nowIso(),
            ) as ClientOrchestrationCommand,
        )
    }

    /** Decides a pending approval request. */
    suspend fun respondToApproval(
        threadId: ThreadId,
        requestId: String,
        decision: String,
    ): Result<DispatchResult> = runCatchingCancellable {
        val client = supervisor.session.value
            ?: error("Not connected to an environment.")

        client.dispatchCommand(
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
        threadId: ThreadId,
        requestId: String,
        answers: Map<String, JsonElement>,
    ): Result<DispatchResult> = runCatchingCancellable {
        val client = supervisor.session.value
            ?: error("Not connected to an environment.")

        client.dispatchCommand(
            ThreadUserInputRespondCommand(
                commandId = idGenerator.newId(),
                threadId = threadId,
                requestId = requestId,
                answers = answers,
                createdAt = timeProvider.nowIso(),
            ) as ClientOrchestrationCommand,
        )
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
