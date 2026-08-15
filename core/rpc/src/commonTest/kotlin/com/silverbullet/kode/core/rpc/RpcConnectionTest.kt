package com.silverbullet.kode.core.rpc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@OptIn(ExperimentalCoroutinesApi::class)
class RpcConnectionTest {

    @Test
    fun `unary request resolves with the exit value`() = runTest {
        val channel = FakeFrameChannel()

        serveRpc(channel) { connection ->
            launch {
                val request = channel.awaitSent()
                assertEquals("server.getConfig", request["tag"]?.jsonPrimitive?.content)
                val id = request["id"]!!.jsonPrimitive.content
                channel.receive(
                    """{"_tag":"Exit","requestId":$id,"exit":{"_tag":"Success","value":{"cwd":"/repo"}}}""",
                )
            }

            val result = connection.request("server.getConfig")
            assertEquals("/repo", result?.jsonObject?.get("cwd")?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `a typed failure surfaces as a call exception rather than a transport error`() = runTest {
        val channel = FakeFrameChannel()

        serveRpc(channel) { connection ->
            launch {
                val id = channel.awaitSent()["id"]!!.jsonPrimitive.content
                channel.receive(
                    """{"_tag":"Exit","requestId":$id,"exit":{"_tag":"Failure","cause":[
                       {"_tag":"Fail","error":{"_tag":"EnvironmentAuthorizationError"}}]}}""",
                )
            }

            val failure = assertFailsWith<RpcCallException> { connection.request("server.getConfig") }
            assertEquals("EnvironmentAuthorizationError", failure.errorTag)
        }
    }

    @Test
    fun `streaming emits chunk values and acks each chunk`() = runTest {
        val channel = FakeFrameChannel()

        serveRpc(channel) { connection ->
            val collected = mutableListOf<String>()

            launch {
                val id = channel.awaitSent()["id"]!!.jsonPrimitive.content
                channel.receive("""{"_tag":"Chunk","requestId":$id,"values":[{"n":"a"},{"n":"b"}]}""")

                // The server only continues once the client acknowledges.
                val ack = channel.awaitSent()
                assertEquals("Ack", ack["_tag"]?.jsonPrimitive?.content)

                channel.receive("""{"_tag":"Chunk","requestId":$id,"values":[{"n":"c"}]}""")
                channel.awaitSent()
                channel.receive("""{"_tag":"Exit","requestId":$id,"exit":{"_tag":"Success"}}""")
            }

            connection.stream("orchestration.subscribeShell").toList().mapTo(collected) {
                it.jsonObject["n"]!!.jsonPrimitive.content
            }

            assertEquals(listOf("a", "b", "c"), collected)
        }
    }

    @Test
    fun `a stream failure exit propagates to the collector`() = runTest {
        val channel = FakeFrameChannel()

        serveRpc(channel) { connection ->
            launch {
                val id = channel.awaitSent()["id"]!!.jsonPrimitive.content
                channel.receive(
                    """{"_tag":"Exit","requestId":$id,"exit":{"_tag":"Failure","cause":[
                       {"_tag":"Fail","error":{"_tag":"OrchestrationGetSnapshotError"}}]}}""",
                )
            }

            assertFailsWith<RpcCallException> {
                connection.stream("orchestration.subscribeThread").toList()
            }
        }
    }

    @Test
    fun `an in-flight call fails when the transport closes`() = runTest {
        val channel = FakeFrameChannel()

        serveRpc(channel) { connection ->
            launch {
                channel.awaitSent()
                channel.close()
            }

            assertFailsWith<RpcTransportException> { connection.request("server.getConfig") }
        }
    }

    @Test
    fun `awaitClosed reports the cause when the socket ends`() = runTest {
        val channel = FakeFrameChannel()

        serveRpc(channel) { connection ->
            launch { channel.close() }
            assertTrue(connection.awaitClosed() is RpcTransportException)
        }
    }

    /** In-memory stand-in for a WebSocket, one JSON object per frame. */
    private class FakeFrameChannel : RpcFrameChannel {
        private val inbound = Channel<String>(Channel.UNLIMITED)
        private val outbound = Channel<String>(Channel.UNLIMITED)

        override val incoming: Flow<String> = inbound.consumeAsFlow()

        override suspend fun send(frame: String) {
            outbound.send(frame)
        }

        fun receive(frame: String) {
            inbound.trySend(frame.trimIndent().replace("\n", ""))
        }

        fun close() {
            inbound.close()
        }

        /** The next frame the client wrote, parsed. */
        suspend fun awaitSent() = Json.parseToJsonElement(outbound.receive()).jsonObject
    }
}
