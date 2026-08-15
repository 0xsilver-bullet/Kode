package com.silverbullet.kode.core.session

import com.silverbullet.kode.core.common.AlwaysOnlineNetworkMonitor
import com.silverbullet.kode.core.common.AppActivation
import com.silverbullet.kode.core.common.AppLifecycleMonitor
import com.silverbullet.kode.core.common.NetworkMonitor
import com.silverbullet.kode.core.common.NoOpAppLifecycleMonitor
import com.silverbullet.kode.core.datastore.EnvironmentRecord
import com.silverbullet.kode.core.datastore.EnvironmentStore
import com.silverbullet.kode.core.network.EnvironmentAuthApi
import com.silverbullet.kode.core.network.EnvironmentAuthException
import com.silverbullet.kode.core.network.T3EnvironmentClient
import com.silverbullet.kode.core.model.ServerConfig
import com.silverbullet.kode.core.network.WebSocketRpcTransport
import com.silverbullet.kode.core.rpc.RpcConnection
import com.silverbullet.kode.core.rpc.RpcCallException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns connectivity for one environment: desired state, retry scheduling, and
 * the active session.
 *
 * This is the single retry owner in the app. Nothing above it — no view model,
 * no screen — opens sockets, builds RPC clients, or schedules reconnects. The
 * policy is ported from `EnvironmentSupervisor` in
 * `packages/client-runtime/src/connection/supervisor.ts`:
 *
 *  - transient failures retry forever with exponential backoff capped at 16s;
 *  - a connection stable for 30s resets the accumulated backoff;
 *  - authentication and configuration failures stay blocked, consuming no
 *    retries and running no timer, until an external wakeup changes the input;
 *  - an explicit retry interrupts backoff immediately.
 */
class EnvironmentSupervisor(
    private val authApi: EnvironmentAuthApi,
    private val transport: WebSocketRpcTransport,
    private val environmentStore: EnvironmentStore,
    private val networkMonitor: NetworkMonitor = AlwaysOnlineNetworkMonitor(),
    private val appLifecycleMonitor: AppLifecycleMonitor = NoOpAppLifecycleMonitor(),
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Unpaired)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _session = MutableStateFlow<T3EnvironmentClient?>(null)

    /**
     * The client for the currently live session, or `null` when there is none.
     *
     * Repositories `flatMapLatest` over this so a durable subscription
     * automatically moves to a replacement session after a reconnect, instead
     * of holding a reference to a dead socket. Emitting `null` between sessions
     * is what tears the old subscription down.
     */
    val session: StateFlow<T3EnvironmentClient?> = _session.asStateFlow()

    /** Conflated so a burst of taps collapses into a single wakeup. */
    private val wakeups = Channel<Wakeup>(Channel.CONFLATED)

    /** Set when a session reaches [ConnectionState.Connected]. */
    private var connectedAt: TimeMark? = null

    /**
     * Runs the supervisor for the lifetime of [scope].
     *
     * `collectLatest` is load-bearing: re-pairing emits a new record, which must
     * cancel the in-flight session rather than queue behind it.
     */
    fun start(scope: CoroutineScope) {
        scope.launch {
            networkMonitor.isOnline.collect { online.value = it }
        }
        scope.launch {
            appLifecycleMonitor.activations.collect { activation ->
                wakeups.trySend(
                    when (activation) {
                        AppActivation.Resumed -> Wakeup.ApplicationActive
                        AppActivation.ResumedAfterSuspension -> Wakeup.ApplicationActiveReconnect
                    },
                )
            }
        }
        scope.launch {
            environmentStore.environment
                .distinctUntilChanged()
                .collectLatest { record ->
                    if (record == null) {
                        _state.value = ConnectionState.Unpaired
                    } else {
                        superviseEnvironment(record)
                    }
                }
        }
    }

    /** Interrupts backoff, or a blocked state, and attempts immediately. */
    fun retryNow() {
        wakeups.trySend(Wakeup.ExplicitRetry)
    }

    /**
     * Signals a return to the foreground.
     *
     * Normally driven by [AppLifecycleMonitor]; exposed for hosts that track
     * lifecycle themselves. [afterSuspension] decides whether the live session
     * is probed or replaced — see [AppActivation].
     */
    fun onApplicationActive(afterSuspension: Boolean = false) {
        wakeups.trySend(
            if (afterSuspension) Wakeup.ApplicationActiveReconnect else Wakeup.ApplicationActive,
        )
    }

    private suspend fun superviseEnvironment(record: EnvironmentRecord) {
        var attempt = 0

        while (true) {
            // Offline: release everything and wait for a signal. Deliberately
            // consumes no retry attempt and runs no timer — a backoff ladder
            // spent against a dead radio would be exhausted by the time the
            // network returns.
            if (!online.value) {
                _state.value = ConnectionState.Offline
                online.first { it }
                attempt = 0
            }

            if (attempt == 0) _state.value = ConnectionState.Connecting

            val failure = try {
                runSession(record)
                // A clean end is still an ended session: reconnect.
                SessionEnded
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (thrown: Throwable) {
                thrown
            }

            if (failure.isBlocking()) {
                _state.value = ConnectionState.Blocked(failure.describe())
                // No retry attempt consumed and no timer running.
                wakeups.receive()
                attempt = 0
                continue
            }

            attempt = nextAttempt(attempt)
            val delay = backoffFor(attempt)
            _state.value = ConnectionState.Reconnecting(
                attempt = attempt,
                retryInMillis = delay.inWholeMilliseconds,
                detail = failure.describe(),
            )

            // An explicit retry resets the ladder; a plain foreground wakeup
            // only skips the remaining delay.
            val wakeup = withTimeoutOrNull(delay) { wakeups.receive() }
            if (wakeup == Wakeup.ExplicitRetry) attempt = 0
        }
    }

    /**
     * Opens one session and holds it until the socket ends.
     *
     * `server.getConfig` gates the transition to [ConnectionState.Connected]:
     * an open socket alone does not prove the server is responsive.
     */
    private suspend fun runSession(record: EnvironmentRecord) {
        val ticket = authApi.issueWebSocketTicket(
            httpBaseUrl = record.httpBaseUrl,
            accessToken = record.accessToken,
        )
        val socketUrl = authApi.socketUrl(record.wsBaseUrl, ticket.ticket)

        transport.connect(socketUrl) { connection ->
            val client = T3EnvironmentClient(connection)
            val config = client.getConfig()

            _state.value = ConnectionState.Connected(
                environment = config.environment,
                workingDirectory = config.cwd,
            )
            connectedAt = timeSource.markNow()

            // Published only after `getConfig` succeeds, so subscribers never
            // see a socket that has not proven itself responsive.
            _session.value = client
            try {
                monitorConnectedLease(connection, client, config)
            } finally {
                // Drop the dead client before any retry, so subscribers tear
                // down rather than issuing calls that can only fail.
                _session.compareAndSet(expect = client, update = null)
            }
        }
    }

    /**
     * Holds a live session, reacting to wakeups without needlessly dropping it.
     *
     * The rules are from `monitorConnectedLease` in `supervisor.ts`:
     *  - a plain foreground **probes** the socket rather than replacing it, so
     *    a healthy session survives switching apps;
     *  - a foreground after a long absence **replaces** it, because the OS may
     *    have killed it silently and probing would only wait for a timeout;
     *  - going offline releases it immediately rather than waiting for the ping
     *    watchdog to notice.
     *
     * Returns normally to mean "replace this lease"; throws to mean the
     * transport failed.
     */
    private suspend fun monitorConnectedLease(
        connection: RpcConnection,
        client: T3EnvironmentClient,
        config: ServerConfig,
    ): Unit = coroutineScope {
        val closed = async { connection.awaitClosed() }
        val wentOffline = async { online.first { !it } }

        try {
            while (true) {
                val outcome = select {
                    closed.onAwait { LeaseOutcome.Closed(it) }
                    wentOffline.onAwait { LeaseOutcome.Offline }
                    wakeups.onReceive { LeaseOutcome.Woken(it) }
                }

                when (outcome) {
                    is LeaseOutcome.Closed -> throw outcome.cause

                    LeaseOutcome.Offline ->
                        throw ConnectionReleasedException("The device went offline.")

                    is LeaseOutcome.Woken -> when (outcome.wakeup) {
                        Wakeup.ApplicationActive -> {
                            // Probing costs one round trip; being wrong costs a
                            // full reconnect, so probe first and only replace
                            // the lease if it actually fails.
                            val healthy = runCatching { client.probe(config) }.isSuccess
                            if (!healthy) {
                                throw ConnectionReleasedException(
                                    "The session stopped responding after returning to the foreground.",
                                )
                            }
                        }

                        Wakeup.ApplicationActiveReconnect, Wakeup.ExplicitRetry ->
                            throw ConnectionReleasedException("Replacing the session.")
                    }
                }
            }
        } finally {
            closed.cancel()
            wentOffline.cancel()
        }
    }

    private sealed interface LeaseOutcome {
        data class Closed(val cause: Throwable) : LeaseOutcome
        data object Offline : LeaseOutcome
        data class Woken(val wakeup: Wakeup) : LeaseOutcome
    }

    /** A connection that stayed up for 30s clears accumulated backoff. */
    private fun nextAttempt(attempt: Int): Int {
        val mark = connectedAt
        connectedAt = null
        val wasStable = mark != null && mark.elapsedNow() >= STABILITY_RESET
        return if (wasStable) 1 else attempt + 1
    }

    private val online = MutableStateFlow(true)

    private enum class Wakeup {
        ExplicitRetry,

        /** Plain foreground. Probe the session; do not replace it. */
        ApplicationActive,

        /** Foreground after a long absence. Replace the session outright. */
        ApplicationActiveReconnect,
    }

    private companion object {
        /** Exponential ladder capped at 16s, matching `RETRY_DELAYS_MS`. */
        val RETRY_DELAYS = listOf(500, 1_000, 2_000, 4_000, 8_000, 16_000)
            .map { it.milliseconds }
        val STABILITY_RESET = 30.seconds

        fun backoffFor(attempt: Int) =
            RETRY_DELAYS[(attempt - 1).coerceIn(0, RETRY_DELAYS.lastIndex)]
    }
}

/** Sentinel for a session that closed without an error. */
private val SessionEnded = RuntimeException("The session ended.")

/**
 * The supervisor deliberately dropped the session. Transient by construction:
 * it always wants a replacement, never a blocked state.
 */
private class ConnectionReleasedException(message: String) : RuntimeException(message)

/**
 * Distinguishes "the input is wrong" from "the network misbehaved".
 *
 * Only the former blocks; everything else retries forever. Getting this split
 * wrong is costly in both directions: a blocked transient failure never
 * recovers, and a retried authorization failure hammers the server.
 */
private fun Throwable.isBlocking(): Boolean = when (this) {
    is EnvironmentAuthException ->
        // 4xx means the credential or address is wrong. 408 and 429 are the
        // exceptions: those are timing, not authorization.
        status.value in 400..499 && status.value != 408 && status.value != 429

    is RpcCallException -> errorTag == "EnvironmentAuthorizationError"
    else -> false
}

private fun Throwable.describe(): String = message ?: this::class.simpleName ?: "Unknown error"
