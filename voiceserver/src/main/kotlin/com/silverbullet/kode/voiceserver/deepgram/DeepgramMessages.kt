package com.silverbullet.kode.voiceserver.deepgram

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The slice of Deepgram's live-streaming messages this server consumes.
 * Reference: developers.deepgram.com/reference/speech-to-text-api/listen-streaming
 */
sealed interface DeepgramMessage {

    data class Results(
        val transcript: String,
        val isFinal: Boolean,
        val speechFinal: Boolean,
        val fromFinalize: Boolean,
        val confidence: Double?,
    ) : DeepgramMessage

    data object UtteranceEnd : DeepgramMessage
    data object SpeechStarted : DeepgramMessage
    data object Metadata : DeepgramMessage
    data class Unknown(val type: String?) : DeepgramMessage

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        @Serializable
        private data class ResultsDto(
            val is_final: Boolean = false,
            val speech_final: Boolean = false,
            val from_finalize: Boolean = false,
            val channel: ChannelDto = ChannelDto(),
        )

        @Serializable
        private data class ChannelDto(val alternatives: List<AlternativeDto> = emptyList())

        @Serializable
        private data class AlternativeDto(val transcript: String = "", val confidence: Double? = null)

        fun parse(text: String): DeepgramMessage {
            val element = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
                ?: return Unknown(null)
            return when (val type = element["type"]?.jsonPrimitive?.content) {
                "Results" -> {
                    val dto = json.decodeFromJsonElement(ResultsDto.serializer(), element)
                    val alternative = dto.channel.alternatives.firstOrNull()
                    Results(
                        transcript = alternative?.transcript.orEmpty(),
                        isFinal = dto.is_final,
                        speechFinal = dto.speech_final,
                        fromFinalize = dto.from_finalize,
                        confidence = alternative?.confidence,
                    )
                }
                "UtteranceEnd" -> UtteranceEnd
                "SpeechStarted" -> SpeechStarted
                "Metadata" -> Metadata
                else -> Unknown(type)
            }
        }
    }

    object Control {
        const val KEEP_ALIVE: String = """{"type":"KeepAlive"}"""
        const val FINALIZE: String = """{"type":"Finalize"}"""
        const val CLOSE_STREAM: String = """{"type":"CloseStream"}"""
    }
}
