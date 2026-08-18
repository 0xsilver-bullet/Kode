package com.silverbullet.kode.voice.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire contract for the Kode voice server. Shared verbatim between the KMP client and the
 * JVM server so the two can never drift.
 *
 * Transport shape on `WS /v1/listen`:
 *  - text frames are JSON, one [VoiceClientMessage] / [VoiceServerMessage] per frame,
 *    discriminated by `"type"`;
 *  - binary frames are raw audio in the format declared by [VoiceStart] (defaults:
 *    16 kHz mono little-endian PCM16), client → server only.
 */
object VoiceProtocol {
    const val LISTEN_PATH: String = "/v1/listen"
    const val REFINE_PATH: String = "/v1/refine"
    const val PAIR_PATH: String = "/v1/pair"
    const val WELL_KNOWN_PATH: String = "/.well-known/kode-voice"
    const val SERVICE_MARKER: String = "kode-voice"
    const val PROTOCOL_VERSION: Int = 1

    /** Capability advertised by servers that can refine transcripts through opencode. */
    const val CAPABILITY_REFINEMENT: String = "refinement"
}

/** Json instance both sides use for every voice payload. */
val VoiceJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "type"
}

// ---------------------------------------------------------------------------
// WebSocket messages
// ---------------------------------------------------------------------------

@Serializable
sealed interface VoiceClientMessage

/**
 * First text frame of a session. The server answers with [VoiceReady] once its upstream
 * transcription socket is open; audio sent before that is buffered.
 *
 * [projectDir] is the absolute path of the project the prompt concerns *on the server's
 * machine* — the server uses it to pick keyterms and refinement context, and ignores it
 * when the path is outside its configured roots.
 */
@Serializable
@SerialName("start")
data class VoiceStart(
    val projectDir: String? = null,
    val sampleRate: Int = 16_000,
    val channels: Int = 1,
    val encoding: String = "linear16",
    val language: String = "en",
) : VoiceClientMessage

/**
 * The user is done talking: flush any buffered audio, finalize the transcript, and reply
 * with [VoiceCompleted]. The server owns the ordered flush (Deepgram `Finalize` +
 * `CloseStream`) so no tail audio is lost.
 */
@Serializable
@SerialName("stop")
data object VoiceStop : VoiceClientMessage

/** Tear the session down without waiting for a final transcript. */
@Serializable
@SerialName("abort")
data object VoiceAbort : VoiceClientMessage

@Serializable
sealed interface VoiceServerMessage

/** Upstream transcription socket is open; audio is now flowing end to end. */
@Serializable
@SerialName("ready")
data class VoiceReady(
    val keytermCount: Int = 0,
) : VoiceServerMessage

/**
 * A live transcript update.
 *
 * Deepgram semantics, preserved on purpose: while [isFinal] is false the [text] *replaces*
 * the current in-flight segment; when [isFinal] is true the segment is settled and must be
 * *appended* to the accumulated transcript. [speechFinal] additionally marks a detected
 * end of utterance.
 */
@Serializable
@SerialName("transcript")
data class VoiceTranscript(
    val text: String,
    val isFinal: Boolean,
    val speechFinal: Boolean = false,
    val confidence: Double? = null,
) : VoiceServerMessage

/** Voice activity detected — drives the "hearing you" indicator. */
@Serializable
@SerialName("speech-started")
data object VoiceSpeechStarted : VoiceServerMessage

/** Silence gap after the last finalized words ([VoiceTranscript.isFinal]). */
@Serializable
@SerialName("utterance-end")
data object VoiceUtteranceEnd : VoiceServerMessage

/**
 * Terminal reply to [VoiceStop]: the authoritative full transcript, assembled server-side
 * from every finalized segment including the flushed tail.
 */
@Serializable
@SerialName("completed")
data class VoiceCompleted(
    val transcript: String,
) : VoiceServerMessage

@Serializable
@SerialName("error")
data class VoiceError(
    val code: String,
    val message: String,
    val recoverable: Boolean = false,
) : VoiceServerMessage {
    companion object {
        const val CODE_UPSTREAM: String = "upstream"
        const val CODE_BAD_REQUEST: String = "bad-request"
        const val CODE_INTERNAL: String = "internal"
    }
}
