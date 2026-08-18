package com.silverbullet.kode.voiceserver

import com.silverbullet.kode.voice.contract.VoicePairRequest
import com.silverbullet.kode.voice.contract.VoicePairResponse
import com.silverbullet.kode.voice.contract.VoiceProtocol
import com.silverbullet.kode.voice.contract.VoiceRefineRequest
import com.silverbullet.kode.voice.contract.VoiceRefineResponse
import com.silverbullet.kode.voice.contract.VoiceServerDescriptor
import com.silverbullet.kode.voiceserver.auth.ClientTokenStore
import com.silverbullet.kode.voiceserver.auth.PairingCodeRegistry
import com.silverbullet.kode.voiceserver.deepgram.DeepgramLiveClient
import com.silverbullet.kode.voiceserver.glossary.ProjectGlossaryService
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoiceServerAppTest {

    private fun testConfig(dataDir: Path): VoiceServerConfig = VoiceServerConfig.load(
        env = mapOf(
            "KODE_VOICE_DATA_DIR" to dataDir.toString(),
            "KODE_VOICE_LABEL" to "test-server",
            "DEEPGRAM_API_KEY" to "test-key",
        ),
    )

    private fun ApplicationTestBuilder.setUpServer(
        refined: (VoiceRefineRequest) -> VoiceRefineResponse = { VoiceRefineResponse(it.transcript, changed = false) },
    ): VoiceServices {
        val dataDir = Files.createTempDirectory("kode-voice-app-test")
        val config = testConfig(dataDir)
        val glossary = ProjectGlossaryService(config.allowedRoots)
        val services = VoiceServices(
            config = config,
            serverId = "server-under-test",
            tokenStore = ClientTokenStore(dataDir.resolve("clients.json")),
            pairingCodes = PairingCodeRegistry(),
            adminSecret = "admin-secret",
            glossary = glossary,
            deepgram = DeepgramLiveClient(HttpClient(), config),
            refiner = { request -> refined(request) },
            advertisedBaseUrl = "http://127.0.0.1:${config.port}",
        )
        application { voiceServerModule(services) }
        return services
    }

    private fun ApplicationTestBuilder.jsonClient(): HttpClient = createClient {
        install(ContentNegotiation) { json() }
    }

    @Test
    fun `descriptor identifies the service unauthenticated`() = testApplication {
        setUpServer()
        val descriptor: VoiceServerDescriptor = jsonClient().get(VoiceProtocol.WELL_KNOWN_PATH).body()
        assertEquals(VoiceProtocol.SERVICE_MARKER, descriptor.service)
        assertEquals("server-under-test", descriptor.serverId)
        assertTrue(descriptor.capabilities.contains(VoiceProtocol.CAPABILITY_REFINEMENT))
    }

    @Test
    fun `pairing exchanges a one-time code for a working bearer token`() = testApplication {
        val services = setUpServer()
        val client = jsonClient()
        val code = services.pairingCodes.mint()

        val paired: VoicePairResponse = client.post(VoiceProtocol.PAIR_PATH) {
            contentType(ContentType.Application.Json)
            setBody(VoicePairRequest(code = code, clientLabel = "Pixel", clientOs = "android"))
        }.body()
        assertTrue(paired.accessToken.startsWith("kv_"))

        // Same code again must fail.
        val replay = client.post(VoiceProtocol.PAIR_PATH) {
            contentType(ContentType.Application.Json)
            setBody(VoicePairRequest(code = code, clientLabel = "Mallory", clientOs = "android"))
        }
        assertEquals(HttpStatusCode.Unauthorized, replay.status)

        // The token authenticates the refine endpoint.
        val refineResponse = client.post(VoiceProtocol.REFINE_PATH) {
            bearerAuth(paired.accessToken)
            contentType(ContentType.Application.Json)
            setBody(VoiceRefineRequest(transcript = "hello world"))
        }
        assertEquals(HttpStatusCode.OK, refineResponse.status)
        assertEquals("hello world", refineResponse.body<VoiceRefineResponse>().refinedText)
    }

    @Test
    fun `refine rejects missing and bogus tokens`() = testApplication {
        setUpServer()
        val client = jsonClient()

        val missing = client.post(VoiceProtocol.REFINE_PATH) {
            contentType(ContentType.Application.Json)
            setBody(VoiceRefineRequest(transcript = "hi"))
        }
        assertEquals(HttpStatusCode.Unauthorized, missing.status)

        val bogus = client.post(VoiceProtocol.REFINE_PATH) {
            bearerAuth("kv_not_real")
            contentType(ContentType.Application.Json)
            setBody(VoiceRefineRequest(transcript = "hi"))
        }
        assertEquals(HttpStatusCode.Unauthorized, bogus.status)
    }

    @Test
    fun `admin pairing links require the runtime secret`() = testApplication {
        setUpServer()
        val client = jsonClient()

        val unauthorized = client.post("/v1/admin/pairing-links")
        assertEquals(HttpStatusCode.Unauthorized, unauthorized.status)

        val authorized = client.post("/v1/admin/pairing-links") {
            headers.append("X-Admin-Secret", "admin-secret")
        }
        assertEquals(HttpStatusCode.OK, authorized.status)
        val link: PairingLinkResponse = authorized.body()
        assertTrue(link.url.contains("#code=" + link.code))
    }
}
