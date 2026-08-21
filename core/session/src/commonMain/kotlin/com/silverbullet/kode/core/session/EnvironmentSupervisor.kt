package com.silverbullet.kode.core.session

import com.silverbullet.kode.core.common.AlwaysOnlineNetworkMonitor
import com.silverbullet.kode.core.common.AppActivation
import com.silverbullet.kode.core.common.AppLifecycleMonitor
import com.silverbullet.kode.core.common.NetworkMonitor
import com.silverbullet.kode.core.common.NoOpAppLifecycleMonitor
import com.silverbullet.kode.core.datastore.EnvironmentRecord
import com.silverbullet.kode.core.network.EnvironmentAuthApi
import com.silverbullet.kode.core.network.EnvironmentAuthException
import com.silverbullet.kode.core.network.RpcTransport
import com.silverbullet.kode.core.network.T3EnvironmentClient
import com.silverbullet.kode.core.model.ServerConfig
import com.silverbullet.kode.core.rpc.RpcConnection
import com.silverbullet.kode.core.rpc.RpcCallException
import com.silverbullet.kode.core.rpc.RpcTransportException
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
 *  - an explicit retry, or a return to the foreground, interrupts backoff
 *    immediately and resets the ladder;
 *  - a session the supervisor drops on purpose — a resume after suspension,
 *    an explicit retry, a failed wake probe — reconnects at once with the
 *    ladder reset, because the user is actively looking at the app.
 */
class EnvironmentSupervisor(
    private val record: EnvironmentRecord,
    private val authApi: EnvironmentAuthApi,
    private val transport: RpcTransport,
    private val networkMonitor: NetworkMonitor = AlwaysOnlineNetworkMonitor(),
    private val appLifecycleMonitor: AppLifecycleMonitor = NoOpAppLifecycleMonitor(),
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Connecting)
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

    private val _serverConfig = MutableStateFlow<ServerConfig?>(null)

    /**
     * The live server's configuration, including its provider instances.
     *
     * Retained across reconnects: the provider catalogue does not change when a
     * socket drops, and clearing it would blank every model picker during a
     * momentary reconnect.
     */
    val serverConfig: StateFlow<ServerConfig?> = _serverConfig.asStateFlow()

    /** Conflated so a burst of taps collapses into a single wakeup. */
    private val wakeups = Channel<Wakeup>(Channel.CONFLATED)

    /** Set when a session reaches [ConnectionState.Connected]. */
    private var connectedAt: TimeMark? = null

    /**
     * Runs this environment's connection until the calling coroutine is
     * cancelled. Cancellation is the only exit: the caller — the fleet — cancels
     * it when the environment is removed or its record changes, which is what
     * used to be `collectLatest` over the single stored record.
     */
    suspend fun run(): Nothing = coroutineScope {
        launch {
            networkMonitor.isOnline.collect { online.value = it }
        }
        launch {
            appLifecycleMonitor.activations.collect { activation ->
                wakeups.trySend(
                    when (activation) {
                        AppActivation.Resumed -> Wakeup.ApplicationActive
                        AppActivation.ResumedAfterSuspension -> Wakeup.ApplicationActiveReconnect
                    },
                )
            }
        }
        superviseEnvironment(record)
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

    private suspend fun superviseEnvironment(record: EnvironmentRecord): Nothing {
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
                // Wakeups that piled up while offline were aimed at a session
                // that no longer exists; consuming them keeps a stale resume
                // from immediately superseding the attempt about to start.
                drainWakeups()
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

            if (failure is ConnectionAttemptSupersededException) {
                // The supervisor dropped this session or attempt itself in
                // response to the user — a resume after suspension, an explicit
                // retry, a failed wake probe. They are looking at the app right
                // now, so reconnect immediately with the ladder reset instead
                // of sleeping a backoff rung against our own decision. This is
                // `resetRetryLadder` + `continue` in `supervisor.ts`.
                attempt = 0
                continue
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

            // Any wakeup interrupts the backoff and resets the ladder: an
            // explicit retry and a return to the foreground both mean "the
            // user is watching, try now" — `supervisor.ts` calls
            // `resetRetryLadder` for every application-active signal here.
            val wakeup = withTimeoutOrNull(delay) { wakeups.receive() }
            if (wakeup != null) attempt = 0
        }
    }

    /** Empties the conflated wakeup channel (it holds at most one element). */
    private fun drainWakeups() {
        while (wakeups.tryReceive().isSuccess) {
            // Consumed deliberately.
        }
    }

    /**
     * Opens one session and holds it until the socket ends.
     *
     * `server.getConfig` gates the transition to [ConnectionState.Connected]:
     * an open socket alone does not prove the server is responsive.
     */
    private suspend fun runSession(record: EnvironmentRecord): Unit = coroutineScope {
        // While establishing there is no lease monitor to receive wakeups, but
        // the conflated channel keeps the latest one. Left unconsumed, a resume
        // that lands here would wait out the whole establishment and then be
        // read by the fresh lease as an order to tear down the session it just
        // asked for. So wakeups are consumed for the establishment's duration:
        // a resume after suspension aborts the attempt — it may be stalled on a
        // transport that died while the app was suspended — and anything else
        // is redundant while an attempt is already running. This is
        // `waitForEstablishmentInterrupt` in `supervisor.ts`.
        val establishmentInterruptor = launch {
            while (true) {
                if (wakeups.receive() == Wakeup.ApplicationActiveReconnect) {
                    throw ConnectionAttemptSupersededException(
                        "Restarting the connection attempt after returning to the foreground.",
                    )
                }
            }
        }

        val ticket = authApi.issueWebSocketTicket(
            httpBaseUrl = record.httpBaseUrl,
            accessToken = record.accessToken,
        )
        val socketUrl = authApi.socketUrl(record.wsBaseUrl, ticket.ticket)

        transport.connect(socketUrl) { connection ->
            val client = T3EnvironmentClient(connection)
            // `withTimeoutOrNull` rather than `withTimeout`: the latter throws
            // a CancellationException subtype, which the supervisor loop would
            // read as its own cancellation and stop supervising entirely. The
            // bound matches CONNECTION_ESTABLISHMENT_TIMEOUT in `supervisor.ts`
            // and covers the one establishment step nothing else bounds — a
            // server that answers pings but never answers the first call.
            val config = withTimeoutOrNull(ESTABLISHMENT_TIMEOUT) { client.getConfig() }
                ?: throw RpcTransportException(
                    "The server did not respond during connection setup.",
                )

            // Establishment is over: wakeups from here on belong to the lease.
            establishmentInterruptor.cancel()

            _state.value = ConnectionState.Connected(
                environment = config.environment,
                workingDirectory = config.cwd,
            )
            connectedAt = timeSource.markNow()
            _serverConfig.value = config

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
                            // the lease if it actually fails. Bounded by the
                            // mobile probe timeout from `supervisor.ts`: a
                            // probe against a silently dead socket would
                            // otherwise wait for the ping watchdog while the
                            // user stares at stale data.
                            val healthy = try {
                                withTimeoutOrNull(PROBE_TIMEOUT) {
                                    client.probe(config)
                                } != null
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (_: Throwable) {
                                false
                            }
                            if (!healthy) {
                                throw ConnectionAttemptSupersededException(
                                    "The session stopped responding after returning to the foreground.",
                                )
                            }
                        }

                        Wakeup.ApplicationActiveReconnect, Wakeup.ExplicitRetry ->
                            throw ConnectionAttemptSupersededException("Replacing the session.")
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
        /**
         * Exponential ladder capped at 16s. Deliberately starts lower than
         * `RETRY_DELAYS_MS` (3s, 4s, 8s, 16s): a phone regains its footing in
         * bursts, and the first rung being sub-second is what makes a flaky
         * reconnect feel instant. The cap and the retry-forever policy match.
         */
        val RETRY_DELAYS = listOf(500, 1_000, 2_000, 4_000, 8_000, 16_000)
            .map { it.milliseconds }
        val STABILITY_RESET = 30.seconds

        /** `CONNECTION_ESTABLISHMENT_TIMEOUT` in `supervisor.ts`. */
        val ESTABLISHMENT_TIMEOUT = 15.seconds

        /** `MOBILE_CONNECTION_PROBE_TIMEOUT` in `supervisor.ts`. */
        val PROBE_TIMEOUT = 3.seconds

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
 * The supervisor dropped a session or attempt because the user demanded a
 * fresh one — a resume after suspension, an explicit retry, a failed wake
 * probe. Unlike [ConnectionReleasedException] it skips the backoff timer
 * entirely and resets the ladder: the user is actively waiting.
 */
private class ConnectionAttemptSupersededException(message: String) : RuntimeException(message)

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
