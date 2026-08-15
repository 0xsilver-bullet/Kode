package com.silverbullet.kode.core.rpc

import kotlinx.coroutines.flow.Flow

/**
 * A bidirectional stream of already-framed text messages.
 *
 * This is the only seam between the RPC protocol and the network. Keeping it
 * here means `:core:rpc` has no Ktor dependency and the whole protocol can be
 * tested against an in-memory channel.
 */
interface RpcFrameChannel {
    /**
     * Inbound frames. Completes normally when the peer closes cleanly and fails
     * with the underlying cause when the socket breaks.
     */
    val incoming: Flow<String>

    suspend fun send(frame: String)
}
