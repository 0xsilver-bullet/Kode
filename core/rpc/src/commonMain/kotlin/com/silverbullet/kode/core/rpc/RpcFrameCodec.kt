package com.silverbullet.kode.core.rpc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Encodes and decodes single Effect RPC frames.
 *
 * Outbound frames use generated serializers; inbound frames are decoded by hand
 * so an unknown `_tag` from a newer server degrades to
 * [RpcServerMessage.Unknown] instead of throwing and tearing down the socket.
 */
class RpcFrameCodec(
    private val json: Json = DefaultJson,
) {
    fun encode(message: RpcClientMessage): String = json.encodeToString(message)

    fun decode(frame: String): RpcServerMessage {
        val obj = runCatching { json.parseToJsonElement(frame) }
            .getOrElse { throw RpcProtocolException("Frame was not valid JSON.", it) }
            .asObject("frame")

        return when (val tag = obj.discriminator()) {
            "Chunk" -> RpcServerMessage.Chunk(
                requestId = obj.requestId(),
                values = obj.requireArray("values"),
            )

            "Exit" -> RpcServerMessage.Exit(
                requestId = obj.requestId(),
                exit = decodeExit(obj["exit"]),
            )

            "Defect" -> RpcServerMessage.Defect(obj["defect"].orNull())
            "Pong" -> RpcServerMessage.Pong
            "ClientEnd" -> RpcServerMessage.ClientEnd
            else -> RpcServerMessage.Unknown(tag)
        }
    }

    private fun decodeExit(element: JsonElement?): RpcExit {
        val obj = element.asObject("exit")
        return when (val tag = obj.discriminator()) {
            "Success" -> RpcExit.Success(obj["value"].orNull())
            "Failure" -> RpcExit.Failure(
                causes = (obj["cause"] as? JsonArray).orEmpty().map(::decodeCause),
            )

            else -> throw RpcProtocolException("Unsupported exit discriminator `$tag`.")
        }
    }

    private fun decodeCause(element: JsonElement): RpcCause {
        val obj = element.asObject("cause entry")
        return when (obj.discriminator()) {
            "Fail" -> RpcCause.Fail(obj["error"].orNull() ?: JsonNull)
            "Interrupt" -> RpcCause.Interrupt(fiberId = obj.intOrNull("fiberId"))
            // "Die" plus any cause kind a newer Effect adds: surfaced as a
            // defect, which is the correct conservative reading.
            else -> RpcCause.Die(obj["defect"].orNull())
        }
    }

    private fun JsonObject.discriminator(): String =
        stringOrNull(DISCRIMINATOR)
            ?: throw RpcProtocolException("Frame is missing a `$DISCRIMINATOR` discriminator.")

    /** `RequestId` is `string | number` on the wire; normalise to a Long. */
    private fun JsonObject.requestId(): Long {
        val raw = stringOrNull("requestId")
            ?: throw RpcProtocolException("Frame is missing `requestId`.")
        return raw.toLongOrNull()
            ?: throw RpcProtocolException("`requestId` was not numeric: $raw")
    }

    private fun JsonObject.requireArray(key: String): List<JsonElement> =
        (this[key] as? JsonArray)?.toList()
            ?: throw RpcProtocolException("Frame is missing array `$key`.")

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun JsonObject.intOrNull(key: String): Int? = stringOrNull(key)?.toIntOrNull()

    private fun JsonElement?.asObject(what: String): JsonObject =
        this as? JsonObject ?: throw RpcProtocolException("Expected $what to be a JSON object.")

    /** Treats an explicit JSON `null` the same as an absent key. */
    private fun JsonElement?.orNull(): JsonElement? = this?.takeIf { it !is JsonNull }

    companion object {
        const val DISCRIMINATOR: String = "_tag"

        /**
         * `_tag` matches Effect's discriminator. `ignoreUnknownKeys` and
         * `explicitNulls = false` keep us forward compatible with servers that
         * add fields we do not model yet.
         */
        val DefaultJson: Json = Json {
            classDiscriminator = DISCRIMINATOR
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }
    }
}
