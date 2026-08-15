package com.silverbullet.kode.core.rpc

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * A live Effect RPC session over one [RpcFrameChannel].
 *
 * Obtain one with [serveRpc], which owns the reader loop and the ping watchdog
 * for the duration of a block. The connection is single-use: once the transport
 * fails, every in-flight and subsequent call fails with
 * [RpcTransportException] and the caller must establish a new one. Reconnection
 * policy deliberately lives above this class, in the connection supervisor.
 */
class RpcConnection internal constructor(
    private val channel: RpcFrameChannel,
    private val codec: RpcFrameCodec,
) {
    private val lock = Mutex()
    private val entries = mutableMapOf<Long, Entry>()
    private var nextRequestId = 0L
    private var closedCause: Throwable? = null
    private val closed = CompletableDeferred<Throwable>()

    /**
     * Suspends until the session ends, returning the cause.
     *
     * This is how a caller holds a connection open without polling: the ping
     * watchdog and the reader loop both surface here.
     */
    suspend fun awaitClosed(): Throwable = closed.await()

    /**
     * Performs a unary call and returns its success payload, or `null` for
     * methods whose success type is `void`.
     *
     * @throws RpcCallException for a declared domain failure.
     * @throws RpcTransportException if the socket dies while in flight.
     */
    suspend fun request(tag: String, payload: JsonElement = EmptyPayload): JsonElement? {
        val result = CompletableDeferred<JsonElement?>()
        val id = register { Entry.Unary(tag, result) }
        try {
            sendFrame(RpcRequest(id = id, tag = tag, payload = payload))
            return result.await()
        } finally {
            unregister(id)
        }
    }

    /**
     * Subscribes to a streaming method. Each emitted element is one value from a
     * `Chunk` frame.
     *
     * The stream ends normally on a `Success` exit and throws on a failure exit.
     * Cancelling the collector sends an `Interrupt` frame so the server can stop
     * producing.
     */
    fun stream(tag: String, payload: JsonElement = EmptyPayload): Flow<JsonElement> = flow {
        // Bounded so an idle collector cannot make the server stream without
        // limit: the reader loop only acks after handing a chunk to this buffer.
        val values = Channel<JsonElement>(capacity = CHUNK_BUFFER)
        val id = register { Entry.Stream(tag, values) }
        var completed = false
        try {
            sendFrame(RpcRequest(id = id, tag = tag, payload = payload))
            for (value in values) {
                emit(value)
            }
            completed = true
        } finally {
            unregister(id)
            if (!completed) {
                // Best effort: the socket may already be gone.
                runCatching { sendFrame(RpcInterrupt(requestId = id)) }
            }
        }
    }

    // ---------------------------------------------------------------- internals

    private suspend fun sendFrame(message: RpcClientMessage) {
        lock.withLock { closedCause }?.let { throw it }
        try {
            channel.send(codec.encode(message))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            throw RpcTransportException("Failed to write an RPC frame.", failure)
        }
    }

    private suspend fun register(create: (Long) -> Entry): Long = lock.withLock {
        closedCause?.let { throw it }
        val id = nextRequestId++
        entries[id] = create(id)
        id
    }

    private suspend fun unregister(id: Long) {
        lock.withLock { entries.remove(id) }
    }

    /** Drives inbound frames until the transport ends. Owned by [serveRpc]. */
    internal suspend fun readLoop(onPong: () -> Unit) {
        channel.incoming.collect { frame ->
            when (val message = codec.decode(frame)) {
                is RpcServerMessage.Chunk -> dispatchChunk(message)
                is RpcServerMessage.Exit -> dispatchExit(message)
                is RpcServerMessage.Defect -> throw RpcDefectException(message.defect)
                RpcServerMessage.Pong -> onPong()
                RpcServerMessage.ClientEnd -> Unit
                is RpcServerMessage.Unknown -> Unit
            }
        }
    }

    private suspend fun dispatchChunk(message: RpcServerMessage.Chunk) {
        val entry = lock.withLock { entries[message.requestId] }
        if (entry !is Entry.Stream) return
        message.values.forEach { entry.values.send(it) }
        // Acked only once the values are buffered, which is what makes the
        // server's backpressure meaningful.
        sendFrame(RpcAck(requestId = message.requestId))
    }

    private suspend fun dispatchExit(message: RpcServerMessage.Exit) {
        val entry = lock.withLock { entries.remove(message.requestId) } ?: return
        when (val exit = message.exit) {
            is RpcExit.Success -> when (entry) {
                is Entry.Unary -> entry.result.complete(exit.value)
                is Entry.Stream -> entry.values.close()
            }

            is RpcExit.Failure -> {
                val failure = exit.toException(entry.tag)
                when (entry) {
                    is Entry.Unary -> entry.result.completeExceptionally(failure)
                    is Entry.Stream -> entry.values.close(failure)
                }
            }
        }
    }

    /** Fails every outstanding call. Called once when the session ends. */
    internal suspend fun closeWith(cause: Throwable) {
        val outstanding = lock.withLock {
            if (closedCause == null) closedCause = cause
            entries.values.toList().also { entries.clear() }
        }
        closed.complete(cause)
        outstanding.forEach { entry ->
            when (entry) {
                is Entry.Unary -> entry.result.completeExceptionally(cause)
                is Entry.Stream -> entry.values.close(cause)
            }
        }
    }

    private sealed interface Entry {
        val tag: String

        data class Unary(
            override val tag: String,
            val result: CompletableDeferred<JsonElement?>,
        ) : Entry

        data class Stream(
            override val tag: String,
            val values: Channel<JsonElement>,
        ) : Entry
    }

    companion object {
        val EmptyPayload: JsonElement = JsonObject(emptyMap())
        private const val CHUNK_BUFFER = 64
    }
}

private fun RpcExit.Failure.toException(tag: String): RpcException {
    // A declared `Fail` is a domain error and takes precedence; anything else is
    // a defect or an interrupt, which are transport-level concerns.
    causes.filterIsInstance<RpcCause.Fail>().firstOrNull()?.let {
        return RpcCallException(tag, it.error)
    }
    causes.filterIsInstance<RpcCause.Die>().firstOrNull()?.let {
        return RpcDefectException(it.defect)
    }
    return RpcTransportException("`$tag` was interrupted by the server.")
}

/**
 * Runs an RPC session over [channel] for the duration of [block].
 *
 * Structured concurrency guarantees the reader loop and ping watchdog are torn
 * down with the block, and that every pending call is failed rather than left
 * hanging when the transport ends.
 *
 * The watchdog mirrors Effect's client: a `Ping` every [pingInterval], and the
 * connection is considered dead if the matching `Pong` has not arrived by the
 * next tick.
 */
suspend fun <T> serveRpc(
    channel: RpcFrameChannel,
    codec: RpcFrameCodec = RpcFrameCodec(),
    pingInterval: Duration = 5.seconds,
    block: suspend CoroutineScope.(RpcConnection) -> T,
): T {
    val connection = RpcConnection(channel, codec)
    var sawPong = true

    return try {
        coroutineScope {
            val reader = launch {
                try {
                    connection.readLoop(onPong = { sawPong = true })
                    connection.closeWith(RpcTransportException("The RPC socket closed."))
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Throwable) {
                    connection.closeWith(
                        failure as? RpcException
                            ?: RpcTransportException("The RPC socket failed.", failure),
                    )
                }
            }

            val pinger = launch {
                while (true) {
                    delay(pingInterval)
                    if (!sawPong) {
                        connection.closeWith(
                            RpcTransportException("The server stopped responding to pings."),
                        )
                        reader.cancel()
                        break
                    }
                    sawPong = false
                    runCatching { channel.send(codec.encode(RpcPing)) }
                }
            }

            try {
                block(connection)
            } finally {
                pinger.cancel()
                reader.cancel()
            }
        }
    } finally {
        connection.closeWith(RpcTransportException("The RPC session ended."))
    }
}
