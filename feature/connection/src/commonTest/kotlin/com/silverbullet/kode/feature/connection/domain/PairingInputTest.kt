package com.silverbullet.kode.feature.connection.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Mirrors `apps/mobile/src/features/connection/pairing.test.ts`: the same
 * payload shapes must round-trip through build/parse/extract.
 */
class PairingInputTest {

    // -- buildPairingUrl ----------------------------------------------------

    @Test
    fun `blank host builds nothing`() {
        assertEquals("", PairingInput.buildPairingUrl("  ", "abc"))
    }

    @Test
    fun `blank code returns the host verbatim so pasted URLs pass through`() {
        assertEquals(
            "http://192.168.1.9:3773/#token=abc",
            PairingInput.buildPairingUrl("http://192.168.1.9:3773/#token=abc", ""),
        )
    }

    @Test
    fun `schemeless ip literal defaults to http`() {
        val url = PairingInput.buildPairingUrl("192.168.1.100:8080", "abc-123")
        assertTrue(url.startsWith("http://192.168.1.100:8080"), url)
        assertTrue(url.endsWith("#token=abc-123"), url)
    }

    @Test
    fun `schemeless hostname defaults to https`() {
        val url = PairingInput.buildPairingUrl("my-macbook.local:8080", "abc")
        assertTrue(url.startsWith("https://my-macbook.local:8080"), url)
    }

    @Test
    fun `an explicit scheme is preserved`() {
        val url = PairingInput.buildPairingUrl("http://example.com", "abc")
        assertTrue(url.startsWith("http://example.com"), url)
    }

    // -- parsePairingUrl ----------------------------------------------------

    @Test
    fun `direct pairing url splits into origin and fragment token`() {
        val fields = PairingInput.parsePairingUrl("http://192.168.1.9:3773/#token=one-time")
        assertEquals("http://192.168.1.9:3773", fields.host)
        assertEquals("one-time", fields.code)
    }

    @Test
    fun `query token is accepted when the fragment has none`() {
        val fields = PairingInput.parsePairingUrl("http://192.168.1.9:3773/?token=q-token")
        assertEquals("http://192.168.1.9:3773", fields.host)
        assertEquals("q-token", fields.code)
    }

    @Test
    fun `fragment token outranks query token`() {
        val fields =
            PairingInput.parsePairingUrl("http://h:1/?token=from-query#token=from-fragment")
        assertEquals("from-fragment", fields.code)
    }

    @Test
    fun `hosted pairing link surfaces the backend host`() {
        val fields = PairingInput.parsePairingUrl(
            "https://app.t3.codes/pair?host=https%3A%2F%2Fbackend.example%2F#token=tkn",
        )
        assertEquals("https://backend.example", fields.host)
        assertEquals("tkn", fields.code)
    }

    @Test
    fun `path query and fragment are stripped from a direct link`() {
        val fields = PairingInput.parsePairingUrl("https://example.com/pair?x=1#token=t")
        assertEquals("https://example.com", fields.host)
        assertEquals("t", fields.code)
    }

    @Test
    fun `default ports are not repeated`() {
        val fields = PairingInput.parsePairingUrl("https://example.com/#token=t")
        assertEquals("https://example.com", fields.host)
    }

    @Test
    fun `non-url text stays in the host field with no code`() {
        val fields = PairingInput.parsePairingUrl("not a url")
        assertEquals("not a url", fields.host)
        assertEquals("", fields.code)
    }

    @Test
    fun `round trip build then parse preserves both fields`() {
        val built = PairingInput.buildPairingUrl("192.168.1.100:8080", "abc-123-xyz")
        val fields = PairingInput.parsePairingUrl(built)
        assertEquals("http://192.168.1.100:8080", fields.host)
        assertEquals("abc-123-xyz", fields.code)
    }

    // -- extractPairingUrlFromQrPayload --------------------------------------

    @Test
    fun `deep link wrapper unwraps the pairing url`() {
        val payload =
            "t3code://pair?pairingUrl=http%3A%2F%2F192.168.1.9%3A3773%2F%23token%3Dabc"
        assertEquals(
            "http://192.168.1.9:3773/#token=abc",
            PairingInput.extractPairingUrlFromQrPayload(payload),
        )
    }

    @Test
    fun `direct urls pass through the extractor untouched`() {
        assertEquals(
            "http://192.168.1.9:3773/#token=abc",
            PairingInput.extractPairingUrlFromQrPayload(" http://192.168.1.9:3773/#token=abc "),
        )
    }

    @Test
    fun `non-url payloads are returned as-is for normal validation to reject`() {
        assertEquals("gibberish", PairingInput.extractPairingUrlFromQrPayload("gibberish"))
    }

    @Test
    fun `empty payload throws`() {
        assertFailsWith<EmptyQrPayloadException> {
            PairingInput.extractPairingUrlFromQrPayload("   ")
        }
    }

    // -- isIpLiteral ---------------------------------------------------------

    @Test
    fun `ip detection`() {
        assertTrue(PairingInput.isIpLiteral("192.168.1.100:8080"))
        assertTrue(PairingInput.isIpLiteral("10.0.0.1"))
        assertTrue(PairingInput.isIpLiteral("[::1]:8080"))
        assertTrue(PairingInput.isIpLiteral("fe80::1"))
        assertEquals(false, PairingInput.isIpLiteral("my-macbook.local"))
        assertEquals(false, PairingInput.isIpLiteral("example.com:8080"))
        assertEquals(false, PairingInput.isIpLiteral("300.1.2.3.4"))
    }
}
