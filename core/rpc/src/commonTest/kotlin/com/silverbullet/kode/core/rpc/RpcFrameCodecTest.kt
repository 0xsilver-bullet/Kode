package com.silverbullet.kode.core.rpc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The frames here are written to match `effect/src/unstable/rpc/RpcMessage.ts`
 * exactly. If Effect changes its encoding, these fail rather than the app
 * silently losing messages at runtime.
 */
class RpcFrameCodecTest {

    private val codec = RpcFrameCodec()

    @Test
    fun `encodes a request with the _tag discriminator`() {
        val encoded = codec.encode(
            RpcRequest(
                id = 0,
                tag = "server.getConfig",
                payload = buildJsonObject { },
            ),
        )

        val obj = Json.parseToJsonElement(encoded).jsonObject
        assertEquals("Request", obj["_tag"]?.jsonPrimitive?.content)
        assertEquals(0, obj["id"]?.jsonPrimitive?.content?.toInt())
        assertEquals("server.getConfig", obj["tag"]?.jsonPrimitive?.content)
        // Headers are an array of pairs on the wire, never an object.
        assertIs<kotlinx.serialization.json.JsonArray>(obj["headers"])
    }

    @Test
    fun `encodes ack and ping frames`() {
        assertEquals(
            "Ack",
            Json.parseToJsonElement(codec.encode(RpcAck(requestId = 7)))
                .jsonObject["_tag"]?.jsonPrimitive?.content,
        )
        assertEquals(
            "Ping",
            Json.parseToJsonElement(codec.encode(RpcPing))
                .jsonObject["_tag"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `decodes a chunk`() {
        val message = codec.decode(
            """{"_tag":"Chunk","requestId":3,"values":[{"kind":"snapshot"},{"kind":"delta"}]}""",
        )

        val chunk = assertIs<RpcServerMessage.Chunk>(message)
        assertEquals(3, chunk.requestId)
        assertEquals(2, chunk.values.size)
        assertEquals("snapshot", chunk.values[0].jsonObject["kind"]?.jsonPrimitive?.content)
    }

    @Test
    fun `decodes a success exit`() {
        val message = codec.decode(
            """{"_tag":"Exit","requestId":1,"exit":{"_tag":"Success","value":{"cwd":"/repo"}}}""",
        )

        val exit = assertIs<RpcServerMessage.Exit>(message)
        val success = assertIs<RpcExit.Success>(exit.exit)
        assertEquals("/repo", success.value?.jsonObject?.get("cwd")?.jsonPrimitive?.content)
    }

    @Test
    fun `decodes a void success exit as a null value`() {
        val message = codec.decode("""{"_tag":"Exit","requestId":1,"exit":{"_tag":"Success"}}""")
        val exit = assertIs<RpcServerMessage.Exit>(message)
        assertNull(assertIs<RpcExit.Success>(exit.exit).value)
    }

    @Test
    fun `decodes a typed failure exit`() {
        val message = codec.decode(
            """
            {"_tag":"Exit","requestId":2,"exit":{"_tag":"Failure","cause":[
              {"_tag":"Fail","error":{"_tag":"EnvironmentAuthorizationError","message":"nope"}}
            ]}}
            """.trimIndent(),
        )

        val exit = assertIs<RpcServerMessage.Exit>(message)
        val failure = assertIs<RpcExit.Failure>(exit.exit)
        val fail = assertIs<RpcCause.Fail>(failure.causes.single())
        assertEquals(
            "EnvironmentAuthorizationError",
            fail.error.jsonObject["_tag"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `decodes a die cause as a defect`() {
        val message = codec.decode(
            """{"_tag":"Exit","requestId":2,"exit":{"_tag":"Failure","cause":[
               {"_tag":"Die","defect":"boom"}]}}""",
        )
        val exit = assertIs<RpcServerMessage.Exit>(message)
        val failure = assertIs<RpcExit.Failure>(exit.exit)
        assertEquals(JsonPrimitive("boom"), assertIs<RpcCause.Die>(failure.causes.single()).defect)
    }

    @Test
    fun `accepts a string request id`() {
        // `RequestId` is `string | number` in the contract.
        val message = codec.decode("""{"_tag":"Exit","requestId":"42","exit":{"_tag":"Success"}}""")
        assertEquals(42, assertIs<RpcServerMessage.Exit>(message).requestId)
    }

    @Test
    fun `decodes pong`() {
        assertEquals(RpcServerMessage.Pong, codec.decode("""{"_tag":"Pong"}"""))
    }

    @Test
    fun `reports an unknown frame instead of throwing`() {
        // A newer server must not be able to kill our socket with a frame kind
        // this build has never heard of.
        val message = codec.decode("""{"_tag":"SomethingNew","requestId":1}""")
        assertEquals("SomethingNew", assertIs<RpcServerMessage.Unknown>(message).tag)
    }

    @Test
    fun `rejects a frame with no discriminator`() {
        assertFailsWith<RpcProtocolException> { codec.decode("""{"requestId":1}""") }
    }

    @Test
    fun `rejects malformed json`() {
        assertFailsWith<RpcProtocolException> { codec.decode("not json") }
    }

    @Test
    fun `ignores unknown fields on known frames`() {
        val message = codec.decode(
            """{"_tag":"Chunk","requestId":1,"values":[{}],"futureField":true}""",
        )
        assertIs<RpcServerMessage.Chunk>(message)
    }
}
