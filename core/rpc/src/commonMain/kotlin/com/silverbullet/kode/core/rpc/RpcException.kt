package com.silverbullet.kode.core.rpc

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

sealed class RpcException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** The frame did not match the Effect RPC encoding. */
class RpcProtocolException(
    message: String,
    cause: Throwable? = null,
) : RpcException(message, cause)

/**
 * The socket closed or failed. Callers must not retry on the same connection —
 * the supervisor owns reconnection, mirroring `isTransportFailure` in
 * `packages/client-runtime/src/rpc/client.ts`.
 */
class RpcTransportException(
    message: String,
    cause: Throwable? = null,
) : RpcException(message, cause)

/**
 * A typed, declared failure from the RPC contract — a domain error, not a
 * transport problem. A healthy socket must never be torn down for one of these.
 */
class RpcCallException(
    val tag: String,
    val error: JsonElement,
) : RpcException("`$tag` failed: ${error.describe()}") {

    /** The `_tag` of the error payload, e.g. `EnvironmentAuthorizationError`. */
    val errorTag: String? = (error as? JsonObject)
        ?.get(RpcFrameCodec.DISCRIMINATOR)
        ?.let { (it as? JsonPrimitive)?.content }
}

/** An undeclared server-side throwable. */
class RpcDefectException(
    val defect: JsonElement?,
) : RpcException("Server defect: ${defect.describe()}")

private fun JsonElement?.describe(): String = this?.toString() ?: "<none>"
