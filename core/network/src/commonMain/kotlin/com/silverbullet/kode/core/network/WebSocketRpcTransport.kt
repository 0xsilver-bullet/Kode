package com.silverbullet.kode.core.network

import com.silverbullet.kode.core.rpc.RpcConnection
import com.silverbullet.kode.core.rpc.RpcFrameChannel
import com.silverbullet.kode.core.rpc.RpcTransportException
import com.silverbullet.kode.core.rpc.serveRpc
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * One attempt at an RPC session over some transport.
 *
 * The interface exists so the connection supervisor can be exercised against
 * an in-memory transport; the app always uses [WebSocketRpcTransport].
 */
interface RpcTransport {
    /**
     * Connects to [socketUrl] and invokes [block] with a live connection.
     *
     * The socket is closed when [block] returns. This performs exactly one
     * attempt and never retries — retry and backoff belong to the connection
     * supervisor, matching `RpcSessionFactory` on the TypeScript side.
     */
    suspend fun <T> connect(
        socketUrl: String,
        block: suspend CoroutineScope.(RpcConnection) -> T,
    ): T
}

/**
 * Opens the environment's `/ws` socket and runs an RPC session over it.
 *
 * T3 Code serves the RPC group with `RpcSerialization.layerJson`, so each
 * WebSocket text frame is exactly one JSON message and no additional framing is
 * needed. Binary frames are not part of this protocol and are ignored.
 */
class WebSocketRpcTransport(
    private val httpClient: HttpClient,
) : RpcTransport {
    override suspend fun <T> connect(
        socketUrl: String,
        block: suspend CoroutineScope.(RpcConnection) -> T,
    ): T = try {
        var result: Result<T>? = null
        httpClient.webSocket(socketUrl) {
            result = runCatching {
                serveRpc(SessionFrameChannel(this)) { connection -> block(connection) }
            }
        }
        result?.getOrThrow()
            ?: throw RpcTransportException("The WebSocket closed before the session started.")
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: RpcTransportException) {
        throw failure
    } catch (failure: Throwable) {
        throw RpcTransportException("Could not open the WebSocket at $socketUrl.", failure)
    }

    private class SessionFrameChannel(
        private val session: DefaultClientWebSocketSession,
    ) : RpcFrameChannel {

        override val incoming: Flow<String> = flow {
            for (frame in session.incoming) {
                if (frame is Frame.Text) emit(frame.readText())
            }
        }

        override suspend fun send(frame: String) {
            session.send(Frame.Text(frame))
        }
    }
}
