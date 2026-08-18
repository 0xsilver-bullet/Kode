package com.silverbullet.kode.feature.voice.domain

import com.silverbullet.kode.voice.contract.VoiceTranscript
import kotlin.test.Test
import kotlin.test.assertEquals

class TranscriptTest {

    private fun interim(text: String) = VoiceTranscript(text = text, isFinal = false)
    private fun final(text: String) = VoiceTranscript(text = text, isFinal = true)

    @Test
    fun interimReplacesWhileFinalsAppend() {
        var transcript = Transcript()
        transcript = transcript.apply(interim("add a"))
        transcript = transcript.apply(interim("add a mic"))
        assertEquals("add a mic", transcript.display)

        transcript = transcript.apply(final("add a mic button"))
        assertEquals("add a mic button", transcript.settled)
        assertEquals("", transcript.interim)

        transcript = transcript.apply(interim("to the"))
        assertEquals("add a mic button to the", transcript.display)

        transcript = transcript.apply(final("to the composer"))
        assertEquals("add a mic button to the composer", transcript.display)
    }

    @Test
    fun emptyFinalOnlyClearsTheInterim() {
        var transcript = Transcript(settled = "hello", interim = "wor")
        transcript = transcript.apply(final(""))
        assertEquals("hello", transcript.settled)
        assertEquals("", transcript.interim)
    }
}
