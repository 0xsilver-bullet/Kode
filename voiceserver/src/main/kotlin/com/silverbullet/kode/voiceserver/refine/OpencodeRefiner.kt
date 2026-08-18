package com.silverbullet.kode.voiceserver.refine

import com.silverbullet.kode.voice.contract.VoiceRefineRequest
import com.silverbullet.kode.voice.contract.VoiceRefineResponse
import com.silverbullet.kode.voice.contract.VoiceThreadMessage
import com.silverbullet.kode.voiceserver.VoiceServerConfig
import com.silverbullet.kode.voiceserver.glossary.ProjectGlossaryService
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * One-shot transcript refinement through opencode's session API
 * (`POST /session` → `POST /session/{id}/message` → `DELETE /session/{id}`).
 *
 * The refiner is a guardrail-first design: the model is told it may only fix
 * misrecognized words, and anything that comes back empty, fenced, or wildly different in
 * length is discarded in favor of the raw transcript. A voice prompt the user read and
 * accepted must never be silently replaced by a model's rewrite.
 */
class OpencodeRefiner(
    private val config: VoiceServerConfig,
    private val manager: OpencodeManager,
    private val httpClient: HttpClient,
    private val glossary: ProjectGlossaryService,
) : TranscriptRefiner {
    private val log = LoggerFactory.getLogger(OpencodeRefiner::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private val scratchDir: Path by lazy {
        config.dataDir.resolve("refine-scratch").also(Files::createDirectories)
    }

    override suspend fun refine(request: VoiceRefineRequest): VoiceRefineResponse {
        val transcript = request.transcript.trim()
        if (transcript.isEmpty()) return VoiceRefineResponse(refinedText = transcript, changed = false)

        val refined = withTimeout(config.refineTimeoutMs) { runRefine(request, transcript) }
        val accepted = sanitize(refined, transcript)
        return VoiceRefineResponse(refinedText = accepted, changed = accepted != transcript)
    }

    private suspend fun runRefine(request: VoiceRefineRequest, transcript: String): String? {
        val endpoint = manager.acquire()
        val directory = resolveDirectory(request.projectDir)
        val projectGlossary = glossary.glossaryFor(request.projectDir)

        val sessionId = createSession(endpoint, directory) ?: return null
        return try {
            prompt(endpoint, directory, sessionId, buildPrompt(transcript, projectGlossary?.contextSummary, request.threadMessages))
        } finally {
            withContext(NonCancellable) {
                runCatching {
                    httpClient.delete("${endpoint.baseUrl}/session/$sessionId") {
                        endpoint.basicAuthHeader?.let { header(HttpHeaders.Authorization, it) }
                        parameter("directory", directory)
                    }
                }.onFailure { log.warn("Failed to delete refine session {}", sessionId, it) }
            }
        }
    }

    private suspend fun createSession(endpoint: OpencodeManager.Endpoint, directory: String): String? {
        val response = httpClient.post("${endpoint.baseUrl}/session") {
            endpoint.basicAuthHeader?.let { header(HttpHeaders.Authorization, it) }
            parameter("directory", directory)
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("title", "kode-voice refine")
                    put(
                        "permission",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("permission", "*")
                                    put("pattern", "*")
                                    put("action", "deny")
                                },
                            )
                        },
                    )
                }.toString(),
            )
        }
        if (!response.status.isSuccess()) {
            log.warn("opencode session create failed: {} {}", response.status, response.bodyAsText().take(300))
            return null
        }
        val body = json.parseToJsonElement(response.body<String>()).jsonObject
        return body["id"]?.jsonPrimitive?.content
    }

    private suspend fun prompt(
        endpoint: OpencodeManager.Endpoint,
        directory: String,
        sessionId: String,
        promptText: String,
    ): String? {
        val (providerId, modelId) = splitModelSlug(config.refineModel)
        val response = httpClient.post("${endpoint.baseUrl}/session/$sessionId/message") {
            endpoint.basicAuthHeader?.let { header(HttpHeaders.Authorization, it) }
            parameter("directory", directory)
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    putJsonObject("model") {
                        put("providerID", providerId)
                        put("modelID", modelId)
                    }
                    put("system", SYSTEM_PROMPT)
                    putJsonObject("tools") { put("*", false) }
                    put(
                        "parts",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", promptText)
                                },
                            )
                        },
                    )
                }.toString(),
            )
        }
        if (!response.status.isSuccess()) {
            log.warn(
                "opencode prompt failed: {} {} — model is '{}'; verify it appears in `opencode models` " +
                    "and its provider is authenticated (`opencode auth list`), or set KODE_VOICE_REFINE_MODEL",
                response.status,
                response.bodyAsText().take(300),
                config.refineModel,
            )
            return null
        }
        val body = json.parseToJsonElement(response.body<String>()).jsonObject
        val error = body["info"]?.jsonObject?.get("error")
        if (error != null && error != JsonNull) {
            log.warn("opencode returned a model error: {}", error.toString().take(300))
            return null
        }
        val parts = body["parts"] as? JsonArray ?: return null
        val text = parts
            .mapNotNull { it as? JsonObject }
            .filter { it["type"]?.jsonPrimitive?.content == "text" }
            .joinToString("") { it["text"]?.jsonPrimitive?.content.orEmpty() }
            .trim()
        return text.ifEmpty { null }
    }

    private fun buildPrompt(
        transcript: String,
        projectContext: String?,
        threadMessages: List<VoiceThreadMessage>,
    ): String = buildString {
        appendLine("Correct the following voice-dictated coding prompt.")
        if (projectContext != null) {
            appendLine()
            appendLine("Project context: $projectContext")
        }
        if (threadMessages.isNotEmpty()) {
            appendLine()
            appendLine("Recent conversation in this thread (oldest first):")
            threadMessages.takeLast(MAX_THREAD_MESSAGES).forEach { message ->
                appendLine("- [${message.role}] ${message.text.take(MAX_THREAD_MESSAGE_CHARS)}")
            }
        }
        appendLine()
        appendLine("Transcript:")
        append(transcript)
    }

    private fun resolveDirectory(projectDir: String?): String {
        if (!projectDir.isNullOrBlank()) {
            val path = runCatching { Path.of(projectDir).toRealPath() }.getOrNull()
            if (path != null && Files.isDirectory(path) &&
                config.allowedRoots.any { root -> path.startsWith(runCatching { root.toRealPath() }.getOrDefault(root)) }
            ) {
                return path.toString()
            }
        }
        return scratchDir.toString()
    }

    /**
     * Refusals, fences, and rewrites all fall back to the raw transcript: refinement may
     * only ever polish, never surprise.
     */
    private fun sanitize(refined: String?, transcript: String): String {
        if (refined.isNullOrBlank()) return transcript
        var candidate = refined.trim()
        if (candidate.startsWith("```")) {
            candidate = candidate.removePrefix("```").substringAfter('\n', "").substringBeforeLast("```").trim()
        }
        candidate = candidate.removeSurrounding("\"")
        if (candidate.isEmpty()) return transcript
        val ratio = candidate.length.toDouble() / transcript.length.toDouble()
        if (ratio < 0.5 || ratio > 2.0) {
            log.info("Discarding refinement with length ratio {}", ratio)
            return transcript
        }
        return candidate
    }

    private fun splitModelSlug(slug: String): Pair<String, String> {
        val index = slug.indexOf('/')
        require(index > 0 && index < slug.length - 1) {
            "KODE_VOICE_REFINE_MODEL must look like provider/model, got: $slug"
        }
        // Split on the FIRST slash only: model ids may themselves contain slashes.
        return slug.substring(0, index) to slug.substring(index + 1)
    }

    private companion object {
        const val MAX_THREAD_MESSAGES = 6
        const val MAX_THREAD_MESSAGE_CHARS = 500

        val SYSTEM_PROMPT = """
            You are a transcription corrector for spoken coding prompts, not an assistant.
            The user dictated a prompt for a coding agent; speech recognition may have
            misheard technical terms.

            Rules, in priority order:
            1. Fix ONLY misrecognized words: technical terms, identifiers, product names,
               numbers, and obvious homophones (e.g. "cot lin" -> "Kotlin", "get hub" -> "GitHub").
               Prefer terms from the provided project context when they plausibly match the sound.
            2. NEVER rewrite, rephrase, expand, shorten, or "improve" the prompt. Keep the
               user's wording, order, and tone exactly. When unsure, leave the text unchanged.
            3. Do not answer, execute, or comment on the prompt's content.
            4. Output ONLY the corrected transcript as plain text - no quotes, no markdown,
               no explanations.
        """.trimIndent()
    }
}
