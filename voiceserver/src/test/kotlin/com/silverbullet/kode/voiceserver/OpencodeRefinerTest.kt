package com.silverbullet.kode.voiceserver

import com.silverbullet.kode.voice.contract.VoiceRefineRequest
import com.silverbullet.kode.voice.contract.VoiceThreadMessage
import com.silverbullet.kode.voiceserver.glossary.ProjectGlossaryService
import com.silverbullet.kode.voiceserver.refine.OpencodeManager
import com.silverbullet.kode.voiceserver.refine.OpencodeRefiner
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpencodeRefinerTest {

    private class FakeOpencode(
        var replyText: String,
        var errorOnPrompt: Boolean = false,
    ) {
        val requests = mutableListOf<Pair<HttpMethod, String>>()
        var promptBody: String? = null

        val engine = MockEngine { request ->
            requests += request.method to request.url.encodedPath
            when {
                request.method == HttpMethod.Get && request.url.encodedPath == "/global/health" ->
                    respond("""{"healthy":true}""", headers = jsonHeaders())
                request.method == HttpMethod.Post && request.url.encodedPath == "/session" ->
                    respond("""{"id":"ses_test123"}""", headers = jsonHeaders())
                request.method == HttpMethod.Post && request.url.encodedPath == "/session/ses_test123/message" -> {
                    promptBody = (request.body as? TextContent)?.text
                    if (errorOnPrompt) {
                        respondError(HttpStatusCode.InternalServerError)
                    } else {
                        respond(
                            """{"info":{"error":null},"parts":[{"type":"step-start"},{"type":"text","text":${'"'}$replyText${'"'}}]}""",
                            headers = jsonHeaders(),
                        )
                    }
                }
                request.method == HttpMethod.Delete && request.url.encodedPath == "/session/ses_test123" ->
                    respond("true", headers = jsonHeaders())
                else -> respondError(HttpStatusCode.NotFound)
            }
        }

        private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, "application/json")
    }

    private fun refiner(fake: FakeOpencode): OpencodeRefiner {
        val dataDir = Files.createTempDirectory("kode-voice-refine-test")
        val config = VoiceServerConfig.load(
            env = mapOf(
                "KODE_VOICE_DATA_DIR" to dataDir.toString(),
                "KODE_VOICE_OPENCODE_URL" to "http://opencode.test",
                "KODE_VOICE_OPENCODE_PASSWORD" to "hunter2",
                "KODE_VOICE_REFINE_MODEL" to "anthropic/claude-haiku-4-5",
            ),
        )
        val client = HttpClient(fake.engine)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return OpencodeRefiner(
            config = config,
            manager = OpencodeManager(config, client, scope),
            httpClient = client,
            glossary = ProjectGlossaryService(config.allowedRoots),
        )
    }

    @Test
    fun `refines through a session and deletes it afterwards`() = runBlocking {
        val fake = FakeOpencode(replyText = "use Ktor 3.5 for the client")
        val response = refiner(fake).refine(
            VoiceRefineRequest(
                transcript = "use k tor three five for the client",
                threadMessages = listOf(VoiceThreadMessage(VoiceThreadMessage.ROLE_USER, "earlier message")),
            ),
        )

        assertEquals("use Ktor 3.5 for the client", response.refinedText)
        assertTrue(response.changed)
        // Session lifecycle: create → prompt → delete.
        assertEquals(HttpMethod.Post to "/session", fake.requests[0])
        assertEquals(HttpMethod.Post to "/session/ses_test123/message", fake.requests[1])
        assertEquals(HttpMethod.Delete to "/session/ses_test123", fake.requests.last())
        // The prompt pinned the configured model and disabled tools.
        val body = requireNotNull(fake.promptBody)
        assertTrue(body.contains("\"providerID\":\"anthropic\""))
        assertTrue(body.contains("\"modelID\":\"claude-haiku-4-5\""))
        assertTrue(body.contains("\"tools\":{\"*\":false}"))
        assertTrue(body.contains("earlier message"))
    }

    @Test
    fun `a rewrite that balloons in length is discarded`() = runBlocking {
        val fake = FakeOpencode(replyText = "Certainly! Here is a much better and considerably longer prompt that I wrote for you instead of yours, with lots of extra ideas you never said")
        val response = refiner(fake).refine(VoiceRefineRequest(transcript = "short prompt"))
        assertEquals("short prompt", response.refinedText)
        assertFalse(response.changed)
    }

    @Test
    fun `an upstream failure falls back to the raw transcript`() = runBlocking {
        val fake = FakeOpencode(replyText = "unused", errorOnPrompt = true)
        val response = refiner(fake).refine(VoiceRefineRequest(transcript = "keep me intact"))
        assertEquals("keep me intact", response.refinedText)
        assertFalse(response.changed)
        // Even on failure the session is cleaned up.
        assertTrue(fake.requests.contains(HttpMethod.Delete to "/session/ses_test123"))
    }

    @Test
    fun `empty transcripts skip opencode entirely`() = runBlocking {
        val fake = FakeOpencode(replyText = "unused")
        val response = refiner(fake).refine(VoiceRefineRequest(transcript = "   "))
        assertEquals("", response.refinedText)
        assertTrue(fake.requests.isEmpty())
    }
}
