package com.silverbullet.kode.voiceserver.deepgram

import com.silverbullet.kode.voice.contract.VoiceStart
import com.silverbullet.kode.voiceserver.VoiceServerConfig
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.http.URLBuilder
import io.ktor.http.takeFrom
import kotlinx.coroutines.withTimeout

/**
 * Opens the live socket to Deepgram, tuned for dictating technical prompts:
 * Nova-3 with project [keyterms], interim results for the live display, moderate
 * endpointing (people pause mid-thought while composing), and numerals so "port eighty
 * eighty" comes back as 8080. `smart_format` implies punctuation.
 */
class DeepgramLiveClient(
    private val httpClient: HttpClient,
    private val config: VoiceServerConfig,
) {
    suspend fun open(start: VoiceStart, keyterms: List<String>): DefaultClientWebSocketSession {
        val apiKey = requireNotNull(config.deepgramApiKey) { "DEEPGRAM_API_KEY is not configured" }
        val url = URLBuilder().takeFrom(config.deepgramBaseUrl).apply {
            pathSegments = listOf("v1", "listen")
            parameters.append("model", config.deepgramModel)
            parameters.append("language", start.language)
            parameters.append("encoding", start.encoding)
            parameters.append("sample_rate", start.sampleRate.toString())
            parameters.append("channels", start.channels.toString())
            parameters.append("interim_results", "true")
            parameters.append("smart_format", "true")
            parameters.append("numerals", "true")
            parameters.append("filler_words", "false")
            parameters.append("vad_events", "true")
            parameters.append("endpointing", config.deepgramEndpointingMs.toString())
            parameters.append("utterance_end_ms", config.deepgramUtteranceEndMs.toString())
            keyterms.forEach { parameters.append("keyterm", it) }
        }.buildString()

        // The shared client has no engine-level timeout; bound the handshake so a
        // black-holed connection surfaces as an upstream error instead of hanging.
        return withTimeout(15_000) {
            httpClient.webSocketSession(url) {
                headers.append("Authorization", "Token $apiKey")
            }
        }
    }
}
