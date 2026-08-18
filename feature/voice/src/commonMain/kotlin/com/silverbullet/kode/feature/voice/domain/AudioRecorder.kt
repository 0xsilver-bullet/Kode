package com.silverbullet.kode.feature.voice.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * One chunk of microphone audio in the session's wire format (16 kHz mono PCM16 by
 * default), plus a normalized loudness for the reactive indicator.
 *
 * A plain class on purpose: chunks are fire-and-forget stream elements, and a data class
 * over a ByteArray invites broken equality.
 */
class AudioChunk(
    val bytes: ByteArray,
    /** RMS loudness normalized to 0..1. */
    val amplitude: Float,
)

/**
 * Platform microphone capture. Follows the project's capability pattern (see
 * `QrCodeScanner`): interface in commonMain, unavailable default in DI, real
 * implementation bound by the platform module.
 *
 * Implementations must expect the collector to cancel at any moment and release the
 * microphone in response; recording ends by cancellation, not by an explicit stop call.
 */
interface AudioRecorder {
    val isAvailable: Boolean

    /** Emits chunks until cancelled. The mic is held only while collected. */
    fun record(): Flow<AudioChunk>
}

class UnavailableAudioRecorder : AudioRecorder {
    override val isAvailable: Boolean = false

    override fun record(): Flow<AudioChunk> = emptyFlow()
}

/** Platform microphone permission. */
interface MicPermission {
    /** Returns true when recording is permitted, prompting the user if needed. */
    suspend fun ensure(): Boolean
}

class DeniedMicPermission : MicPermission {
    override suspend fun ensure(): Boolean = false
}
