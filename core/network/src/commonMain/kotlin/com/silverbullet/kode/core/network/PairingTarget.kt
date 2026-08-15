package com.silverbullet.kode.core.network

import io.ktor.http.Url
import io.ktor.http.parseQueryString

/**
 * A pairing link resolved into everything needed to reach one environment.
 *
 * Port of `resolveRemotePairingTarget` in `packages/shared/src/remote.ts`.
 */
data class PairingTarget(
    /** The one-time bootstrap credential exchanged at `/oauth/token`. */
    val credential: String,
    val httpBaseUrl: String,
    val wsBaseUrl: String,
)

sealed class PairingLinkException(message: String) : Exception(message) {
    class Malformed(value: String) : PairingLinkException("`$value` is not a valid URL.")
    class UnsupportedProtocol(protocol: String) :
        PairingLinkException("Endpoint must use HTTP or HTTPS. Received `$protocol`.")

    class MissingToken(host: String) :
        PairingLinkException("The pairing link for `$host` has no token.")

    class MissingHost : PairingLinkException("A host is required.")
    class MissingCode(host: String) :
        PairingLinkException("A pairing code is required for `$host`.")
}

object PairingLinkResolver {

    private const val TOKEN_PARAM = "token"
    private const val HOSTED_HOST_PARAM = "host"

    /**
     * Resolves a scanned or pasted pairing URL.
     *
     * `t3 pair` puts the token in the URL fragment so it never reaches a server
     * in a request line; hosted links (`https://app.t3.codes/pair?host=...`)
     * instead carry the real backend in a `host` query parameter. Both shapes
     * are handled, matching the TypeScript resolver.
     */
    fun fromPairingUrl(pairingUrl: String): PairingTarget {
        val raw = pairingUrl.trim()
        if (raw.isEmpty()) throw PairingLinkException.MissingHost()

        val url = runCatching { Url(raw) }
            .getOrElse { throw PairingLinkException.Malformed(raw) }
        requireHttpProtocol(url.protocol.name)

        val credential = readToken(url) ?: throw PairingLinkException.MissingToken(url.host)

        // Hosted pairing: the link points at the web app, the real backend is in
        // the `host` parameter.
        val hostedBackend = url.parameters[HOSTED_HOST_PARAM]?.trim()?.takeIf { it.isNotEmpty() }
        val backend = hostedBackend?.let(::normalizeBaseUrl) ?: normalizeBaseUrl(raw)

        return PairingTarget(
            credential = credential,
            httpBaseUrl = backend,
            wsBaseUrl = deriveWsBaseUrl(backend),
        )
    }

    /** Manual entry: a host (or bare IP) plus a pairing code. */
    fun fromHostAndCode(host: String, pairingCode: String): PairingTarget {
        val trimmedHost = host.trim()
        if (trimmedHost.isEmpty()) throw PairingLinkException.MissingHost()
        val normalized = normalizeBaseUrl(trimmedHost)

        val code = pairingCode.trim()
        if (code.isEmpty()) throw PairingLinkException.MissingCode(Url(normalized).host)

        return PairingTarget(
            credential = code,
            httpBaseUrl = normalized,
            wsBaseUrl = deriveWsBaseUrl(normalized),
        )
    }

    /**
     * Normalises any endpoint spelling to an origin-only `http(s)://host:port/`.
     *
     * A bare host such as `192.168.1.9:3773` is assumed to be HTTP, matching the
     * documented behaviour of T3 Code's own Add Environment form.
     */
    fun normalizeBaseUrl(rawValue: String): String {
        val value = rawValue.trim()
        val withScheme = if (value.contains("://")) value else "http://$value"
        val url = runCatching { Url(withScheme) }
            .getOrElse { throw PairingLinkException.Malformed(rawValue) }

        val scheme = when (url.protocol.name) {
            "http", "ws" -> "http"
            "https", "wss" -> "https"
            else -> throw PairingLinkException.UnsupportedProtocol(url.protocol.name)
        }

        val defaultPort = if (scheme == "https") 443 else 80
        val authority = if (url.port == defaultPort || url.port == url.protocol.defaultPort) {
            url.host
        } else {
            "${url.host}:${url.port}"
        }
        return "$scheme://$authority/"
    }

    fun deriveWsBaseUrl(httpBaseUrl: String): String {
        val normalized = normalizeBaseUrl(httpBaseUrl)
        return when {
            normalized.startsWith("https://") -> normalized.replaceFirst("https://", "wss://")
            else -> normalized.replaceFirst("http://", "ws://")
        }
    }

    /** Fragment first, then query — the order the TypeScript resolver uses. */
    private fun readToken(url: Url): String? {
        val fragmentToken = parseQueryString(url.fragment)[TOKEN_PARAM]?.trim()
        if (!fragmentToken.isNullOrEmpty()) return fragmentToken
        return url.parameters[TOKEN_PARAM]?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun requireHttpProtocol(name: String) {
        if (name != "http" && name != "https") {
            throw PairingLinkException.UnsupportedProtocol(name)
        }
    }
}
