package com.silverbullet.kode.voice.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceProtocolTest {

    @Test
    fun clientMessagesRoundTripThroughTheTypeDiscriminator() {
        val start: VoiceClientMessage = VoiceStart(projectDir = "/work/kode", language = "en")
        val encoded = VoiceJson.encodeToString(VoiceClientMessage.serializer(), start)
        assertTrue(encoded.contains("\"type\":\"start\""), encoded)

        val decoded = VoiceJson.decodeFromString(VoiceClientMessage.serializer(), encoded)
        assertEquals(start, decoded)

        val stop = VoiceJson.decodeFromString(VoiceClientMessage.serializer(), """{"type":"stop"}""")
        assertIs<VoiceStop>(stop)
    }

    @Test
    fun serverMessagesRoundTrip() {
        val transcript: VoiceServerMessage = VoiceTranscript(
            text = "add a mic button to the composer",
            isFinal = true,
            speechFinal = true,
            confidence = 0.97,
        )
        val encoded = VoiceJson.encodeToString(VoiceServerMessage.serializer(), transcript)
        val decoded = VoiceJson.decodeFromString(VoiceServerMessage.serializer(), encoded)
        assertEquals(transcript, decoded)
    }

    @Test
    fun unknownFieldsAreIgnoredSoOlderClientsSurviveNewerServers() {
        val decoded = VoiceJson.decodeFromString(
            VoiceServerMessage.serializer(),
            """{"type":"completed","transcript":"hello","futureField":42}""",
        )
        assertEquals(VoiceCompleted("hello"), decoded)
    }

    @Test
    fun pairingLinkRoundTrips() {
        val link = VoicePairingLink.build("https://machine.tail1234.ts.net:8484", "ABCD2345EFGH")
        assertEquals("https://machine.tail1234.ts.net:8484/pair#code=ABCD2345EFGH", link)

        val parsed = VoicePairingLink.parse(link)
        assertEquals("https://machine.tail1234.ts.net:8484/", parsed?.baseUrl)
        assertEquals("ABCD2345EFGH", parsed?.code)
    }

    @Test
    fun pairingLinkParsingRejectsGarbage() {
        assertNull(VoicePairingLink.parse("not a url"))
        assertNull(VoicePairingLink.parse("https://host/pair"))
        assertNull(VoicePairingLink.parse("https://host/pair#code="))
        assertNull(VoicePairingLink.parse("ftp://host/pair#code=AAAA"))
    }

    @Test
    fun refineRequestOmitsNothingItNeeds() {
        val request = VoiceRefineRequest(
            transcript = "use ktor three five",
            projectDir = "/work/kode",
            threadMessages = listOf(VoiceThreadMessage(VoiceThreadMessage.ROLE_USER, "hi")),
        )
        val decoded = VoiceJson.decodeFromString(
            VoiceRefineRequest.serializer(),
            VoiceJson.encodeToString(VoiceRefineRequest.serializer(), request),
        )
        assertEquals(request, decoded)
    }
}
