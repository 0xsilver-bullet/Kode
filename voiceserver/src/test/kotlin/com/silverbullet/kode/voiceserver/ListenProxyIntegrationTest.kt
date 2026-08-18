package com.silverbullet.kode.voiceserver

import com.silverbullet.kode.voice.contract.VoiceClientMessage
import com.silverbullet.kode.voice.contract.VoiceCompleted
import com.silverbullet.kode.voice.contract.VoiceJson
import com.silverbullet.kode.voice.contract.VoiceProtocol
import com.silverbullet.kode.voice.contract.VoiceReady
import com.silverbullet.kode.voice.contract.VoiceRefineResponse
import com.silverbullet.kode.voice.contract.VoiceServerMessage
import com.silverbullet.kode.voice.contract.VoiceStart
import com.silverbullet.kode.voice.contract.VoiceStop
import com.silverbullet.kode.voice.contract.VoiceTranscript
import com.silverbullet.kode.voiceserver.auth.ClientTokenStore
import com.silverbullet.kode.voiceserver.auth.PairingCodeRegistry
import com.silverbullet.kode.voiceserver.deepgram.DeepgramLiveClient
import com.silverbullet.kode.voiceserver.glossary.ProjectGlossaryService
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * End-to-end proxy test: real Netty voice server, real WebSocket client, and a scripted
 * fake Deepgram. Verifies the ordered flush on stop — the completed transcript must
 * include the segment Deepgram only produces after `Finalize`/`CloseStream`.
 */
class ListenProxyIntegrationTest {

    private val servers = mutableListOf<EmbeddedServer<*, *>>()
    private val clients = mutableListOf<HttpClient>()

    @AfterTest
    fun tearDown() {
        clients.forEach { it.close() }
        servers.forEach { it.stop(100, 500) }
    }

    private fun startFakeDeepgram(): Int {
        val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            install(WebSockets)
            routing {
                webSocket("/v1/listen") {
                    var audioFrames = 0
                    for (frame in incoming) {
                        when (frame) {
                            is Frame.Binary -> {
                                audioFrames++
                                if (audioFrames == 1) {
                                    send(Frame.Text(results("hello", isFinal = false)))
                                    send(Frame.Text(results("hello world", isFinal = true, speechFinal = true)))
                                }
                            }
                            is Frame.Text -> {
                                val text = frame.readText()
                                if (text.contains("Finalize")) {
                                    send(Frame.Text(results("tail words", isFinal = true, fromFinalize = true)))
                                }
                                if (text.contains("CloseStream")) {
                                    send(Frame.Text("""{"type":"Metadata","request_id":"fake"}"""))
                                    return@webSocket
                                }
                            }
                            else -> Unit
                        }
                    }
                }
            }
        }.start(wait = false)
        servers += server
        return runBlocking { server.engine.resolvedConnectors().first().port }
    }

    private fun results(
        transcript: String,
        isFinal: Boolean,
        speechFinal: Boolean = false,
        fromFinalize: Boolean = false,
    ): String =
        """
        {"type":"Results","is_final":$isFinal,"speech_final":$speechFinal,"from_finalize":$fromFinalize,
         "channel":{"alternatives":[{"transcript":"$transcript","confidence":0.9}]}}
        """.trimIndent()

    @Test
    fun `streams transcripts and flushes the tail on stop`() {
        val deepgramPort = startFakeDeepgram()
        val dataDir = Files.createTempDirectory("kode-voice-proxy-test")
        val config = VoiceServerConfig.load(
            env = mapOf(
                "KODE_VOICE_DATA_DIR" to dataDir.toString(),
                "DEEPGRAM_API_KEY" to "test-key",
                "KODE_VOICE_DEEPGRAM_URL" to "ws://127.0.0.1:$deepgramPort",
            ),
        )
        val tokenStore = ClientTokenStore(dataDir.resolve("clients.json"))
        val token = tokenStore.issueToken("test", "jvm")
        val upstreamClient = HttpClient(CIO) { install(ClientWebSockets) }.also { clients += it }
        val services = VoiceServices(
            config = config,
            serverId = "proxy-test",
            tokenStore = tokenStore,
            pairingCodes = PairingCodeRegistry(),
            adminSecret = "secret",
            glossary = ProjectGlossaryService(config.allowedRoots),
            deepgram = DeepgramLiveClient(upstreamClient, config),
            refiner = { VoiceRefineResponse(it.transcript, changed = false) },
            advertisedBaseUrl = "http://127.0.0.1",
        )
        val voiceServer = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            voiceServerModule(services)
        }.start(wait = false)
        servers += voiceServer
        val voicePort = runBlocking { voiceServer.engine.resolvedConnectors().first().port }

        val received = mutableListOf<VoiceServerMessage>()
        val client = HttpClient(CIO) { install(ClientWebSockets) }.also { clients += it }

        runBlocking {
            withTimeout(15_000) {
                client.webSocket(
                    urlString = "ws://127.0.0.1:$voicePort${VoiceProtocol.LISTEN_PATH}",
                    request = { bearerAuth(token) },
                ) {
                    send(Frame.Text(VoiceJson.encodeToString(VoiceClientMessage.serializer(), VoiceStart())))
                    // First message back must be ready.
                    val ready = decode(incoming.receive())
                    assertIs<VoiceReady>(ready)

                    send(Frame.Binary(fin = true, data = ByteArray(640)))

                    var stopped = false
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        val message = decode(frame)
                        received += message
                        // Once the final segment lands, tell the server we're done talking.
                        if (!stopped && message is VoiceTranscript && message.isFinal) {
                            send(Frame.Text(VoiceJson.encodeToString(VoiceClientMessage.serializer(), VoiceStop)))
                            stopped = true
                        }
                        if (message is VoiceCompleted) break
                    }
                }
            }
        }

        val transcripts = received.filterIsInstance<VoiceTranscript>()
        assertTrue(transcripts.any { !it.isFinal && it.text == "hello" }, "interim missing: $received")
        assertTrue(transcripts.any { it.isFinal && it.text == "hello world" }, "final missing: $received")
        val completed = received.filterIsInstance<VoiceCompleted>().single()
        assertEquals("hello world tail words", completed.transcript)
    }

    @Test
    fun `listen socket rejects unauthenticated upgrades`() {
        val dataDir = Files.createTempDirectory("kode-voice-authz-test")
        val config = VoiceServerConfig.load(
            env = mapOf("KODE_VOICE_DATA_DIR" to dataDir.toString(), "DEEPGRAM_API_KEY" to "k"),
        )
        val services = VoiceServices(
            config = config,
            serverId = "authz-test",
            tokenStore = ClientTokenStore(dataDir.resolve("clients.json")),
            pairingCodes = PairingCodeRegistry(),
            adminSecret = "secret",
            glossary = ProjectGlossaryService(config.allowedRoots),
            deepgram = DeepgramLiveClient(HttpClient(CIO) { install(ClientWebSockets) }.also { clients += it }, config),
            refiner = { VoiceRefineResponse(it.transcript, changed = false) },
            advertisedBaseUrl = "http://127.0.0.1",
        )
        val voiceServer = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            voiceServerModule(services)
        }.start(wait = false)
        servers += voiceServer
        val voicePort = runBlocking { voiceServer.engine.resolvedConnectors().first().port }

        val client = HttpClient(CIO) { install(ClientWebSockets) }.also { clients += it }
        val failed = runCatching {
            runBlocking {
                withTimeout(10_000) {
                    client.webSocket("ws://127.0.0.1:$voicePort${VoiceProtocol.LISTEN_PATH}") {
                        incoming.receive()
                    }
                }
            }
        }.isFailure
        assertTrue(failed, "unauthenticated websocket upgrade should not succeed")
    }

    private fun decode(frame: Frame): VoiceServerMessage {
        check(frame is Frame.Text)
        return VoiceJson.decodeFromString(VoiceServerMessage.serializer(), frame.readText())
    }
}
