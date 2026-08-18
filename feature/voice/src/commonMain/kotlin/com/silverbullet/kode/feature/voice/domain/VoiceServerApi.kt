package com.silverbullet.kode.feature.voice.domain

import com.silverbullet.kode.core.datastore.VoiceBindingRecord
import com.silverbullet.kode.voice.contract.VoiceClientMessage
import com.silverbullet.kode.voice.contract.VoiceJson
import com.silverbullet.kode.voice.contract.VoicePairRequest
import com.silverbullet.kode.voice.contract.VoicePairResponse
import com.silverbullet.kode.voice.contract.VoiceProtocol
import com.silverbullet.kode.voice.contract.VoiceRefineRequest
import com.silverbullet.kode.voice.contract.VoiceRefineResponse
import com.silverbullet.kode.voice.contract.VoiceServerDescriptor
import com.silverbullet.kode.voice.contract.VoiceServerMessage
import com.silverbullet.kode.voice.contract.VoiceStart
import com.silverbullet.kode.voice.contract.VoiceStop
import com.silverbullet.kode.voice.contract.VoiceAbort
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A live dictation socket. [events] is cold and single-collector: collecting it drives
 * the read loop, and cancelling the collector closes nothing by itself — end the session
 * with [stop] (graceful, transcript flushed) or [abort].
 */
interface VoiceLiveSession {
    val events: Flow<VoiceServerMessage>

    suspend fun sendAudio(bytes: ByteArray)

    /** Graceful end: the server flushes the tail and replies with the full transcript. */
    suspend fun stop()

    suspend fun abort()

    suspend fun close()
}

internal class KtorVoiceLiveSession(
    private val session: DefaultClientWebSocketSession,
) : VoiceLiveSession {
    override val events: Flow<VoiceServerMessage> = flow {
        for (frame in session.incoming) {
            if (frame !is Frame.Text) continue
            val message = runCatching {
                VoiceJson.decodeFromString(VoiceServerMessage.serializer(), frame.readText())
            }.getOrNull() ?: continue
            emit(message)
        }
    }

    override suspend fun sendAudio(bytes: ByteArray) {
        session.send(Frame.Binary(fin = true, data = bytes))
    }

    override suspend fun stop() {
        sendControl(VoiceStop)
    }

    override suspend fun abort() {
        sendControl(VoiceAbort)
    }

    override suspend fun close() {
        runCatching { session.close() }
    }

    private suspend fun sendControl(message: VoiceClientMessage) {
        session.send(Frame.Text(VoiceJson.encodeToString(VoiceClientMessage.serializer(), message)))
    }
}

/**
 * Typed client for the Kode voice server. Bodies are encoded with the shared
 * [VoiceJson] explicitly rather than relying on the app client's ContentNegotiation,
 * which is configured for the t3 contract.
 */
interface VoiceServerApi {
    /** Identify-before-pairing, same ladder as environment pairing. */
    suspend fun fetchDescriptor(baseUrl: String): VoiceServerDescriptor

    suspend fun pair(baseUrl: String, code: String, clientLabel: String, clientOs: String): VoicePairResponse

    suspend fun refine(binding: VoiceBindingRecord, request: VoiceRefineRequest): VoiceRefineResponse

    suspend fun openSession(binding: VoiceBindingRecord, start: VoiceStart): VoiceLiveSession
}

class KtorVoiceServerApi(
    private val httpClient: HttpClient,
) : VoiceServerApi {

    override suspend fun fetchDescriptor(baseUrl: String): VoiceServerDescriptor {
        val response = httpClient.get(normalize(baseUrl) + VoiceProtocol.WELL_KNOWN_PATH.removePrefix("/"))
        if (!response.status.isSuccess()) throw VoiceServerException("Server answered ${response.status.value}")
        val descriptor = decode(VoiceServerDescriptor.serializer(), response.bodyAsText())
        if (descriptor.service != VoiceProtocol.SERVICE_MARKER) {
            throw VoiceServerException("That address is not a Kode voice server.")
        }
        return descriptor
    }

    override suspend fun pair(
        baseUrl: String,
        code: String,
        clientLabel: String,
        clientOs: String,
    ): VoicePairResponse {
        val response = httpClient.post(normalize(baseUrl) + VoiceProtocol.PAIR_PATH.removePrefix("/")) {
            contentType(ContentType.Application.Json)
            setBody(
                VoiceJson.encodeToString(
                    VoicePairRequest.serializer(),
                    VoicePairRequest(code = code, clientLabel = clientLabel, clientOs = clientOs),
                ),
            )
        }
        if (!response.status.isSuccess()) {
            throw VoiceServerException(
                if (response.status.value == 401) {
                    "Pairing code was rejected — codes are single-use and expire after 15 minutes."
                } else {
                    "Pairing failed (${response.status.value})."
                },
            )
        }
        return decode(VoicePairResponse.serializer(), response.bodyAsText())
    }

    override suspend fun refine(
        binding: VoiceBindingRecord,
        request: VoiceRefineRequest,
    ): VoiceRefineResponse {
        val response = httpClient.post(binding.serverUrl + VoiceProtocol.REFINE_PATH.removePrefix("/")) {
            bearerAuth(binding.accessToken)
            contentType(ContentType.Application.Json)
            setBody(VoiceJson.encodeToString(VoiceRefineRequest.serializer(), request))
            // Refinement legitimately outlives the app client's default 15s request
            // timeout: the server's first prompt against a project pays opencode's
            // cold instance load on top of the model round trip.
            timeout { requestTimeoutMillis = REFINE_TIMEOUT_MS }
        }
        if (!response.status.isSuccess()) {
            throw VoiceServerException("Refinement failed (${response.status.value}).")
        }
        return decode(VoiceRefineResponse.serializer(), response.bodyAsText())
    }

    override suspend fun openSession(binding: VoiceBindingRecord, start: VoiceStart): VoiceLiveSession {
        val wsUrl = binding.serverUrl
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://") +
            VoiceProtocol.LISTEN_PATH.removePrefix("/")
        val session = httpClient.webSocketSession(wsUrl) {
            headers { append(HttpHeaders.Authorization, "Bearer ${binding.accessToken}") }
        }
        session.send(Frame.Text(VoiceJson.encodeToString(VoiceClientMessage.serializer(), start)))
        return KtorVoiceLiveSession(session)
    }

    private fun <T> decode(serializer: kotlinx.serialization.KSerializer<T>, body: String): T =
        runCatching { VoiceJson.decodeFromString(serializer, body) }
            .getOrElse { throw VoiceServerException("Unexpected reply from the voice server.") }

    private fun normalize(baseUrl: String): String = baseUrl.trimEnd('/') + "/"

    private companion object {
        /** Server-side refinement is capped at 60s; leave headroom for transport. */
        const val REFINE_TIMEOUT_MS = 90_000L
    }
}

class VoiceServerException(message: String) : Exception(message)
