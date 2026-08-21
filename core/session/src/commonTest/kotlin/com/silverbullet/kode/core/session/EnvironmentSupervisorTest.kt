package com.silverbullet.kode.core.session

import com.silverbullet.kode.core.datastore.EnvironmentRecord
import com.silverbullet.kode.core.model.EnvironmentId
import com.silverbullet.kode.core.network.ContractJson
import com.silverbullet.kode.core.network.EnvironmentAuthApi
import com.silverbullet.kode.core.network.RpcTransport
import com.silverbullet.kode.core.rpc.RpcConnection
import com.silverbullet.kode.core.rpc.RpcFrameChannel
import com.silverbullet.kode.core.rpc.RpcTransportException
import com.silverbullet.kode.core.rpc.serveRpc
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Drives [EnvironmentSupervisor] against an in-memory RPC server, covering the
 * resume-from-background behaviors ported from `supervisor.ts`.
 *
 * All waiting is done by suspending on state or on server signals: `runTest`
 * auto-advances virtual time whenever the test body suspends, so the
 * supervisor's timers (backoff, probe timeout, establishment timeout, ping
 * watchdog) fire in order without manual clock control. Wakeups that must land
 * in a precise window are sent synchronously from inside the fake server.
 */
class EnvironmentSupervisorTest {

    @Test
    fun `a resume during establishment restarts the attempt instead of killing the fresh session`() =
        runTest {
            val server = FakeServer()
            val supervisor = supervisor(server)
            // Fires while the first `getConfig` is in flight — the exact window
            // where a wakeup used to sit buffered until the lease started and
            // then tear down the session it had asked for.
            server.onGetConfigOnce = { supervisor.onApplicationActive(afterSuspension = true) }
            backgroundScope.launch { supervisor.run() }

            supervisor.state.first { it is ConnectionState.Connected }

            // The first attempt was aborted and replaced immediately.
            assertEquals(2, server.connectCount)

            // The session must now be stable: no leftover wakeup replaces it.
            assertNull(
                withTimeoutOrNull(30_000) {
                    supervisor.state.first { it !is ConnectionState.Connected }
                },
                "The fresh session was replaced after connecting.",
            )
            assertEquals(2, server.connectCount)
        }

    @Test
    fun `a plain foreground during establishment is swallowed`() = runTest {
        val server = FakeServer()
        val supervisor = supervisor(server)
        server.onGetConfigOnce = { supervisor.onApplicationActive(afterSuspension = false) }
        backgroundScope.launch { supervisor.run() }

        supervisor.state.first { it is ConnectionState.Connected }

        // One attempt was enough, and nothing probes or replaces it afterwards.
        assertEquals(1, server.connectCount)
        assertNull(
            withTimeoutOrNull(30_000) {
                supervisor.state.first { it !is ConnectionState.Connected }
            },
            "A swallowed foreground wakeup must not disturb the session.",
        )
        assertEquals(1, server.connectCount)
    }

    @Test
    fun `a foreground wakeup during backoff retries immediately and resets the ladder`() = runTest {
        val server = FakeServer(failConnect = true)
        val supervisor = supervisor(server)
        val attemptsAfterWakeup = Channel<Int>(Channel.UNLIMITED)
        var woken = false
        backgroundScope.launch { supervisor.run() }
        backgroundScope.launch {
            supervisor.state.collect { state ->
                if (state !is ConnectionState.Reconnecting) return@collect
                if (!woken && state.attempt == 3) {
                    // Lands inside attempt 3's two-second backoff window.
                    woken = true
                    supervisor.onApplicationActive(afterSuspension = false)
                } else if (woken) {
                    attemptsAfterWakeup.trySend(state.attempt)
                }
            }
        }

        // The wakeup cuts the backoff short and resets the ladder, so the next
        // failure reports attempt 1 — without the reset it would report 4.
        assertEquals(1, attemptsAfterWakeup.receive())
    }

    @Test
    fun `a probe that hangs replaces the session within the probe timeout`() = runTest {
        val server = FakeServer()
        val supervisor = supervisor(server)
        backgroundScope.launch { supervisor.run() }
        supervisor.state.first { it is ConnectionState.Connected }

        server.respondToProbes = false
        val probeStartedAt = testScheduler.currentTime
        supervisor.onApplicationActive(afterSuspension = false)

        // Pings keep being answered, so the watchdog stays quiet: only the 3s
        // probe timeout can decide this session is dead. The replacement then
        // connects with no backoff.
        server.awaitConnectCount(2)
        val elapsed = testScheduler.currentTime - probeStartedAt
        assertTrue(
            elapsed in 3_000..7_999,
            "The hung probe should be abandoned by the 3s probe timeout, " +
                "not the 10s ping watchdog. Took ${elapsed}ms.",
        )
        server.respondToProbes = true
        supervisor.state.first { it is ConnectionState.Connected }
    }

    @Test
    fun `an unanswered first call fails the attempt at the establishment timeout`() = runTest {
        // The server answers pings but never `getConfig` — without the
        // establishment timeout nothing would ever fail this attempt.
        val server = FakeServer(gateConfig = true)
        val supervisor = supervisor(server)
        backgroundScope.launch { supervisor.run() }

        val state = supervisor.state.first { it is ConnectionState.Reconnecting }
        assertTrue(testScheduler.currentTime >= 15_000)
        assertEquals(1, (state as ConnectionState.Reconnecting).attempt)
    }

    // ------------------------------------------------------------- harness

    private fun supervisor(server: FakeServer) = EnvironmentSupervisor(
        record = EnvironmentRecord(
            environmentId = EnvironmentId("env-1"),
            label = "Env",
            httpBaseUrl = "http://env.test",
            wsBaseUrl = "ws://env.test",
            accessToken = "token",
        ),
        authApi = fakeAuthApi(),
        transport = server.transport,
    )

    private fun fakeAuthApi(): EnvironmentAuthApi {
        val engine = MockEngine {
            respond(
                content = """{"ticket":"ticket-1"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return EnvironmentAuthApi(
            httpClient = HttpClient(engine) {
                install(ContentNegotiation) { json(ContractJson) }
            },
        )
    }
}

/**
 * An in-memory environment server: hands each connection attempt a frame
 * channel speaking just enough of the Effect RPC protocol for the supervisor.
 */
private class FakeServer(
    var gateConfig: Boolean = false,
    var failConnect: Boolean = false,
    var respondToProbes: Boolean = true,
) {
    var connectCount = 0
        private set

    /** One-shot hook invoked when a `server.getConfig` request arrives. */
    var onGetConfigOnce: (() -> Unit)? = null

    private val connects = Channel<Int>(Channel.UNLIMITED)

    suspend fun awaitConnectCount(count: Int) {
        while (connectCount < count) {
            connects.receive()
        }
    }

    val transport: RpcTransport = object : RpcTransport {
        override suspend fun <T> connect(
            socketUrl: String,
            block: suspend CoroutineScope.(RpcConnection) -> T,
        ): T {
            connectCount++
            connects.trySend(connectCount)
            if (failConnect) throw RpcTransportException("Synthetic connect failure.")
            return serveRpc(FakeFrameChannel(this@FakeServer)) { connection -> block(connection) }
        }
    }
}

private class FakeFrameChannel(private val server: FakeServer) : RpcFrameChannel {

    private val frames = Channel<String>(Channel.UNLIMITED)

    override val incoming: Flow<String> = frames.receiveAsFlow()

    override suspend fun send(frame: String) {
        val obj = Json.parseToJsonElement(frame).jsonObject
        when (obj["_tag"]?.jsonPrimitive?.content) {
            "Ping" -> frames.send("""{"_tag":"Pong"}""")
            "Request" -> {
                val id = obj["id"]?.jsonPrimitive?.content ?: return
                when (obj["tag"]?.jsonPrimitive?.content) {
                    "server.getConfig" -> {
                        server.onGetConfigOnce?.let { hook ->
                            server.onGetConfigOnce = null
                            hook()
                        }
                        if (!server.gateConfig) frames.send(configExit(id))
                    }

                    "server.probe" ->
                        if (server.respondToProbes) frames.send(voidExit(id))
                }
            }
        }
    }

    private fun configExit(id: String): String =
        """{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Success","value":$CONFIG_JSON}}"""

    private fun voidExit(id: String): String =
        """{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Success"}}"""

    private companion object {
        const val CONFIG_JSON =
            """{"environment":{"environmentId":"env-1","label":"Env","platform":{"os":"linux","arch":"arm64"},"serverVersion":"1.0.0","capabilities":{"connectionProbe":true}},"cwd":"/work"}"""
    }
}
