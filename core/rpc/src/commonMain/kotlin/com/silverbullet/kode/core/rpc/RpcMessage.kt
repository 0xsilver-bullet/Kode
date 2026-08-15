package com.silverbullet.kode.core.rpc

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Client -> server frames of the Effect RPC protocol.
 *
 * Reference: `effect/src/unstable/rpc/RpcMessage.ts` (`FromClientEncoded`).
 * T3 Code serves this group with `RpcSerialization.layerJson`, which means one
 * complete JSON object per WebSocket frame — there is no newline framing to
 * implement, unlike the ndjson serializer.
 */
@Serializable
sealed interface RpcClientMessage

@Serializable
@SerialName("Request")
data class RpcRequest(
    val id: Long,
    val tag: String,
    val payload: JsonElement,
    /** Encoded as an array of `[name, value]` pairs, not an object. */
    val headers: List<List<String>> = emptyList(),
) : RpcClientMessage

/**
 * Acknowledges one streamed [RpcServerMessage.Chunk]. The socket protocol sets
 * `supportsAck: true`, so the server applies backpressure and will stop sending
 * chunks until the ack arrives.
 */
@Serializable
@SerialName("Ack")
data class RpcAck(val requestId: Long) : RpcClientMessage

@Serializable
@SerialName("Interrupt")
data class RpcInterrupt(
    val requestId: Long,
    val interruptors: List<Int> = emptyList(),
) : RpcClientMessage

@Serializable
@SerialName("Ping")
data object RpcPing : RpcClientMessage

@Serializable
@SerialName("Eof")
data object RpcEof : RpcClientMessage

/**
 * Server -> client frames.
 *
 * These are decoded by hand in [RpcFrameCodec] rather than through a sealed
 * serializer: an unrecognised `_tag` from a newer server must be ignored, not
 * treated as a fatal decode error that tears down the socket.
 */
sealed interface RpcServerMessage {
    data class Chunk(val requestId: Long, val values: List<JsonElement>) : RpcServerMessage

    data class Exit(val requestId: Long, val exit: RpcExit) : RpcServerMessage

    /** A server-side defect that invalidates every in-flight request. */
    data class Defect(val defect: JsonElement?) : RpcServerMessage

    data object Pong : RpcServerMessage

    data object ClientEnd : RpcServerMessage

    /** A frame this build does not understand. Ignored by the reader loop. */
    data class Unknown(val tag: String) : RpcServerMessage
}

/** The `exit` payload of an [RpcServerMessage.Exit] frame. */
sealed interface RpcExit {
    /** `value` is absent for methods whose success type is `void`. */
    data class Success(val value: JsonElement?) : RpcExit

    data class Failure(val causes: List<RpcCause>) : RpcExit
}

sealed interface RpcCause {
    /** A typed, declared error from the RPC contract. */
    data class Fail(val error: JsonElement) : RpcCause

    /** An undeclared throwable on the server. */
    data class Die(val defect: JsonElement?) : RpcCause

    data class Interrupt(val fiberId: Int?) : RpcCause
}
