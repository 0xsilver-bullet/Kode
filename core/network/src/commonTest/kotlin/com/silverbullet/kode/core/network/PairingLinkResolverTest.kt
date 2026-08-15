package com.silverbullet.kode.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Behaviour ported from `resolveRemotePairingTarget` and
 * `normalizeHttpBaseUrl` in `packages/shared/src/remote.ts` and
 * `advertisedEndpoint.ts`.
 */
class PairingLinkResolverTest {

    @Test
    fun `reads the token from the url fragment`() {
        // `t3 pair` puts the token in the fragment so it never reaches a server
        // in a request line.
        val target = PairingLinkResolver.fromPairingUrl(
            "http://100.101.102.103:3773/#token=abc123",
        )

        assertEquals("abc123", target.credential)
        assertEquals("http://100.101.102.103:3773/", target.httpBaseUrl)
        assertEquals("ws://100.101.102.103:3773/", target.wsBaseUrl)
    }

    @Test
    fun `falls back to the token query parameter`() {
        val target = PairingLinkResolver.fromPairingUrl("https://desk.tail.ts.net/?token=xyz")

        assertEquals("xyz", target.credential)
        assertEquals("https://desk.tail.ts.net/", target.httpBaseUrl)
        assertEquals("wss://desk.tail.ts.net/", target.wsBaseUrl)
    }

    @Test
    fun `prefers the fragment token over the query token`() {
        val target = PairingLinkResolver.fromPairingUrl(
            "http://host:3773/?token=fromQuery#token=fromFragment",
        )
        assertEquals("fromFragment", target.credential)
    }

    @Test
    fun `resolves a hosted pairing link to the real backend`() {
        // https://app.t3.codes/pair?host=... points at the web app; the backend
        // to actually connect to is in the `host` parameter.
        val target = PairingLinkResolver.fromPairingUrl(
            "https://app.t3.codes/pair?host=https%3A%2F%2Fdesk.tail.ts.net%2F#token=tok",
        )

        assertEquals("tok", target.credential)
        assertEquals("https://desk.tail.ts.net/", target.httpBaseUrl)
        assertEquals("wss://desk.tail.ts.net/", target.wsBaseUrl)
    }

    @Test
    fun `rejects a pairing url with no token`() {
        assertFailsWith<PairingLinkException.MissingToken> {
            PairingLinkResolver.fromPairingUrl("http://100.101.102.103:3773/")
        }
    }

    @Test
    fun `rejects a non-http protocol`() {
        assertFailsWith<PairingLinkException.UnsupportedProtocol> {
            PairingLinkResolver.fromPairingUrl("ftp://host/#token=abc")
        }
    }

    @Test
    fun `manual entry assumes http for a bare address`() {
        // Documented behaviour of T3 Code's Add Environment form: a numeric IP
        // without a scheme uses HTTP.
        val target = PairingLinkResolver.fromHostAndCode("192.168.1.9:3773", "code-1")

        assertEquals("code-1", target.credential)
        assertEquals("http://192.168.1.9:3773/", target.httpBaseUrl)
    }

    @Test
    fun `manual entry honours an explicit https scheme`() {
        val target = PairingLinkResolver.fromHostAndCode("https://desk.tail.ts.net", "code-2")

        assertEquals("https://desk.tail.ts.net/", target.httpBaseUrl)
        assertEquals("wss://desk.tail.ts.net/", target.wsBaseUrl)
    }

    @Test
    fun `manual entry requires a code`() {
        assertFailsWith<PairingLinkException.MissingCode> {
            PairingLinkResolver.fromHostAndCode("192.168.1.9:3773", "  ")
        }
    }

    @Test
    fun `normalising strips paths and queries and fragments`() {
        assertEquals(
            "http://host:3773/",
            PairingLinkResolver.normalizeBaseUrl("http://host:3773/some/path?a=1#frag"),
        )
    }

    @Test
    fun `normalising rewrites websocket schemes to http`() {
        assertEquals("http://host:3773/", PairingLinkResolver.normalizeBaseUrl("ws://host:3773"))
        assertEquals("https://host/", PairingLinkResolver.normalizeBaseUrl("wss://host"))
    }

    @Test
    fun `normalising drops the default port`() {
        assertEquals("https://host/", PairingLinkResolver.normalizeBaseUrl("https://host:443"))
        assertEquals("http://host/", PairingLinkResolver.normalizeBaseUrl("http://host:80"))
    }
}
