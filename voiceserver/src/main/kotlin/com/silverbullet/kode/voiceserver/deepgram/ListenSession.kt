package com.silverbullet.kode.voiceserver.deepgram

import com.silverbullet.kode.voice.contract.VoiceAbort
import com.silverbullet.kode.voice.contract.VoiceClientMessage
import com.silverbullet.kode.voice.contract.VoiceCompleted
import com.silverbullet.kode.voice.contract.VoiceError
import com.silverbullet.kode.voice.contract.VoiceJson
import com.silverbullet.kode.voice.contract.VoiceReady
import com.silverbullet.kode.voice.contract.VoiceServerMessage
import com.silverbullet.kode.voice.contract.VoiceSpeechStarted
import com.silverbullet.kode.voice.contract.VoiceStart
import com.silverbullet.kode.voice.contract.VoiceStop
import com.silverbullet.kode.voice.contract.VoiceTranscript
import com.silverbullet.kode.voice.contract.VoiceUtteranceEnd
import com.silverbullet.kode.voiceserver.glossary.ProjectGlossaryService
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * One live dictation: pumps binary audio from the phone up to Deepgram and translated
 * transcript events back down, owning the three timing obligations the raw API imposes:
 *
 *  1. Deepgram drops the socket after 10s without audio — send `KeepAlive` while the mic
 *     is open but quiet (docs recommend every 3–5s).
 *  2. On stop, `Finalize` + `CloseStream` and *wait* for the flushed results before
 *     reporting [VoiceCompleted], so no tail audio is lost.
 *  3. Interim results replace, finals append — the accumulation happens here so the
 *     client's [VoiceCompleted] transcript is authoritative.
 */
class ListenSession(
    private val client: WebSocketSession,
    private val deepgram: DeepgramLiveClient,
    private val glossary: ProjectGlossaryService,
) {
    private companion object {
        const val START_TIMEOUT_MS = 10_000L
        const val KEEP_ALIVE_INTERVAL_MS = 4_000L
        const val FLUSH_TIMEOUT_MS = 7_000L
    }

    suspend fun run() {
        val start = awaitStart() ?: return

        val projectGlossary = glossary.glossaryFor(start.projectDir)
        val upstream = try {
            deepgram.open(start, projectGlossary?.keyterms ?: emptyList())
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            sendToClient(
                VoiceError(VoiceError.CODE_UPSTREAM, cause.message ?: "Could not reach the transcription service"),
            )
            client.close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "upstream unavailable"))
            return
        }

        sendToClient(VoiceReady(keytermCount = projectGlossary?.keyterms?.size ?: 0))

        val finalSegments = ArrayList<String>()
        val lastAudioAtMillis = AtomicLong(System.currentTimeMillis())
        val stopping = AtomicBoolean(false)
        val upstreamDone = CompletableDeferred<Unit>()

        try {
            coroutineScope {
                val downstream = launch {
                    try {
                        for (frame in upstream.incoming) {
                            if (frame !is Frame.Text) continue
                            when (val message = DeepgramMessage.parse(frame.readText())) {
                                is DeepgramMessage.Results -> {
                                    if (message.isFinal && message.transcript.isNotBlank()) {
                                        finalSegments += message.transcript.trim()
                                    }
                                    if (message.transcript.isNotBlank() || !message.isFinal) {
                                        sendToClient(
                                            VoiceTranscript(
                                                text = message.transcript,
                                                isFinal = message.isFinal,
                                                speechFinal = message.speechFinal,
                                                confidence = message.confidence,
                                            ),
                                        )
                                    }
                                }
                                DeepgramMessage.UtteranceEnd -> sendToClient(VoiceUtteranceEnd)
                                DeepgramMessage.SpeechStarted -> sendToClient(VoiceSpeechStarted)
                                DeepgramMessage.Metadata, is DeepgramMessage.Unknown -> Unit
                            }
                        }
                    } finally {
                        upstreamDone.complete(Unit)
                    }
                }

                val keepAlive = launch {
                    while (isActive && !stopping.get()) {
                        delay(KEEP_ALIVE_INTERVAL_MS)
                        val quietFor = System.currentTimeMillis() - lastAudioAtMillis.get()
                        if (quietFor >= KEEP_ALIVE_INTERVAL_MS && !stopping.get()) {
                            upstream.send(Frame.Text(DeepgramMessage.Control.KEEP_ALIVE))
                        }
                    }
                }

                client@ for (frame in client.incoming) {
                    when (frame) {
                        is Frame.Binary -> {
                            lastAudioAtMillis.set(System.currentTimeMillis())
                            upstream.send(Frame.Binary(fin = true, data = frame.data))
                        }
                        is Frame.Text -> when (decodeClientMessage(frame.readText())) {
                            is VoiceStop -> {
                                stopping.set(true)
                                upstream.send(Frame.Text(DeepgramMessage.Control.FINALIZE))
                                upstream.send(Frame.Text(DeepgramMessage.Control.CLOSE_STREAM))
                                break@client
                            }
                            is VoiceAbort -> {
                                stopping.set(true)
                                break@client
                            }
                            else -> Unit
                        }
                        else -> Unit
                    }
                }

                keepAlive.cancel()
                if (stopping.get()) {
                    // Give Deepgram a bounded window to flush the tail, then report.
                    withTimeoutOrNull(FLUSH_TIMEOUT_MS) { upstreamDone.await() }
                }
                downstream.cancel()
            }

            sendToClient(VoiceCompleted(transcript = finalSegments.joinToString(" ").trim()))
            client.close(CloseReason(CloseReason.Codes.NORMAL, "completed"))
        } finally {
            runCatching { upstream.close(CloseReason(CloseReason.Codes.NORMAL, "done")) }
        }
    }

    private suspend fun awaitStart(): VoiceStart? {
        val start = withTimeoutOrNull(START_TIMEOUT_MS) {
            for (frame in client.incoming) {
                if (frame !is Frame.Text) continue
                return@withTimeoutOrNull decodeClientMessage(frame.readText()) as? VoiceStart
            }
            null
        }
        if (start == null) {
            sendToClient(VoiceError(VoiceError.CODE_BAD_REQUEST, "Expected a start message"))
            client.close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "missing start"))
        }
        return start
    }

    private fun decodeClientMessage(text: String): VoiceClientMessage? =
        runCatching { VoiceJson.decodeFromString(VoiceClientMessage.serializer(), text) }.getOrNull()

    private suspend fun sendToClient(message: VoiceServerMessage) {
        client.send(Frame.Text(VoiceJson.encodeToString(VoiceServerMessage.serializer(), message)))
    }
}
