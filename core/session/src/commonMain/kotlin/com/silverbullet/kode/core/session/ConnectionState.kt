package com.silverbullet.kode.core.session

import com.silverbullet.kode.core.model.ExecutionEnvironmentDescriptor

/**
 * The connection lifecycle, derived from supervisor state only.
 *
 * `docs/internals/connection-runtime.md` is explicit that connection health is
 * never inferred from cached data or from the existence of a transport object.
 * An environment is [Connected] only after the socket opens *and* the initial
 * config RPC succeeds, which is what proves the server is responsive.
 */
sealed interface ConnectionState {

    /** First attempt for this environment. */
    data object Connecting : ConnectionState

    /**
     * The device has no usable network.
     *
     * Distinct from [Reconnecting] because nothing is scheduled: there is no
     * timer and no retry attempt being consumed. The UI must not imply a
     * countdown that is not running.
     */
    data object Offline : ConnectionState

    /**
     * A previous attempt failed transiently and another is scheduled.
     * Transient failures retry forever; the backoff is capped, never abandoned.
     */
    data class Reconnecting(
        val attempt: Int,
        val retryInMillis: Long,
        val detail: String,
    ) : ConnectionState

    data class Connected(
        val environment: ExecutionEnvironmentDescriptor,
        val workingDirectory: String,
    ) : ConnectionState

    /**
     * Authentication or configuration failed. Unlike a transient failure this
     * does *not* schedule a retry: it stays blocked until an external change
     * (re-pairing, an explicit retry) alters the relevant input.
     */
    data class Blocked(val reason: String) : ConnectionState
}
