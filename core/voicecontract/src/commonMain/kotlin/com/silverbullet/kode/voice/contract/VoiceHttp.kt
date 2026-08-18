package com.silverbullet.kode.voice.contract

import kotlinx.serialization.Serializable

/**
 * Served unauthenticated at [VoiceProtocol.WELL_KNOWN_PATH] so clients can identify a voice
 * server (and refuse to pair with something else) before spending a pairing code — the same
 * identify-first ladder the t3 environment uses.
 */
@Serializable
data class VoiceServerDescriptor(
    val service: String = VoiceProtocol.SERVICE_MARKER,
    val serverId: String,
    val label: String,
    val version: Int = VoiceProtocol.PROTOCOL_VERSION,
    val capabilities: List<String> = emptyList(),
)

/** `POST /v1/pair` — exchanges a one-time pairing code for a long-lived bearer token. */
@Serializable
data class VoicePairRequest(
    val code: String,
    val clientLabel: String,
    val clientOs: String,
)

@Serializable
data class VoicePairResponse(
    val accessToken: String,
    val serverId: String,
    val label: String,
)

/** One entry of recent thread history, oldest first. */
@Serializable
data class VoiceThreadMessage(
    val role: String,
    val text: String,
) {
    companion object {
        const val ROLE_USER: String = "user"
        const val ROLE_ASSISTANT: String = "assistant"
    }
}

/** `POST /v1/refine` — bearer-authenticated one-shot transcript refinement. */
@Serializable
data class VoiceRefineRequest(
    val transcript: String,
    val projectDir: String? = null,
    val threadMessages: List<VoiceThreadMessage> = emptyList(),
)

@Serializable
data class VoiceRefineResponse(
    val refinedText: String,
    /** True when the refiner actually changed something; false means passthrough. */
    val changed: Boolean,
)

@Serializable
data class VoiceErrorResponse(
    val error: String,
    val detail: String? = null,
)
