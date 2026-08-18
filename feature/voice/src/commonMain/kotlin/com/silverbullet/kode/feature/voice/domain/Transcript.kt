package com.silverbullet.kode.feature.voice.domain

import com.silverbullet.kode.voice.contract.VoiceTranscript

/**
 * The live transcript, folded from Deepgram-shaped events: a final segment appends and
 * clears the in-flight interim; an interim replaces the previous interim. The dialog
 * renders [settled] normally and [interim] muted, so words firm up in place while the
 * user keeps talking.
 */
data class Transcript(
    val settled: String = "",
    val interim: String = "",
) {
    val display: String
        get() = when {
            settled.isEmpty() -> interim
            interim.isEmpty() -> settled
            else -> "$settled $interim"
        }

    val isEmpty: Boolean get() = settled.isEmpty() && interim.isEmpty()

    fun apply(event: VoiceTranscript): Transcript =
        if (event.isFinal) {
            val text = event.text.trim()
            if (text.isEmpty()) copy(interim = "") else Transcript(
                settled = if (settled.isEmpty()) text else "$settled $text",
                interim = "",
            )
        } else {
            copy(interim = event.text.trim())
        }
}
