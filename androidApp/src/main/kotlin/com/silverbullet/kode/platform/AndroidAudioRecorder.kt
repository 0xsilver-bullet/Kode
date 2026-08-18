package com.silverbullet.kode.platform

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.silverbullet.kode.feature.voice.domain.AudioChunk
import com.silverbullet.kode.feature.voice.domain.AudioRecorder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Microphone capture as the voice contract's wire format: 16 kHz mono PCM16
 * little-endian, in ~40 ms chunks (640 samples / 1280 bytes), each carrying an RMS
 * loudness for the reactive indicator.
 *
 * `VOICE_RECOGNITION` selects the unprocessed near-field tuning (no aggressive AGC or
 * noise suppression that hurts ASR). The mic is held exactly as long as the flow is
 * collected; cancellation releases it in `finally`.
 */
class AndroidAudioRecorder(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AudioRecorder {

    override val isAvailable: Boolean = true

    // Permission is checked by MicPermission before the recorder is started; a failed
    // AudioRecord init (state != INITIALIZED) still guards the no-permission race.
    @SuppressLint("MissingPermission")
    override fun record(): Flow<AudioChunk> = flow {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            CHANNEL,
            ENCODING,
            maxOf(minBuffer, CHUNK_BYTES * 4),
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw IllegalStateException("Microphone is unavailable (recorder failed to initialize).")
        }
        try {
            recorder.startRecording()
            val buffer = ByteArray(CHUNK_BYTES)
            while (currentCoroutineContext().isActive) {
                var offset = 0
                while (offset < buffer.size) {
                    val read = recorder.read(buffer, offset, buffer.size - offset)
                    if (read <= 0) throw IllegalStateException("Microphone read failed ($read).")
                    offset += read
                }
                emit(AudioChunk(bytes = buffer.copyOf(), amplitude = rms(buffer)))
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
    }.flowOn(ioDispatcher)

    /** Root-mean-square of the PCM16 samples, normalized to 0..1 with a speech-friendly curve. */
    private fun rms(buffer: ByteArray): Float {
        var sum = 0.0
        var i = 0
        while (i + 1 < buffer.size) {
            val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
            sum += sample * sample.toDouble()
            i += 2
        }
        val rms = sqrt(sum / (buffer.size / 2))
        // Normal speech peaks well below full scale; scale so it visibly moves the rings.
        return min(1.0, rms / 9000.0).toFloat()
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /** 40 ms of 16 kHz mono PCM16. */
        const val CHUNK_BYTES = 1280
    }
}
