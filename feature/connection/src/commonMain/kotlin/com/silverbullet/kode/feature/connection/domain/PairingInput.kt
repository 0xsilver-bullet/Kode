package com.silverbullet.kode.feature.connection.domain

import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.encodeURLQueryComponent
import io.ktor.http.parseQueryString

/** The host/code pair the Add Environment form edits. */
data class PairingFields(val host: String, val code: String)

/** The scanned QR payload carried no pairing URL at all. */
class EmptyQrPayloadException :
    IllegalArgumentException("Scanned QR code did not contain a pairing URL.")

/**
 * The Add Environment form's translation between what the user sees (a host
 * and a pairing code) and what the resolver consumes (one pairing URL).
 *
 * Port of `apps/mobile/src/features/connection/pairing.ts`. The form is a thin
 * veneer: pasting a full pairing URL into the host field splits it into the two
 * fields, and submitting rebuilds the URL, so the same resolver validates every
 * entry path — typed, pasted, or scanned.
 */
object PairingInput {

    private const val TOKEN_PARAM = "token"
    private const val HOSTED_HOST_PARAM = "host"
    private const val MOBILE_PAIRING_URL_PARAM = "pairingUrl"
    private const val MOBILE_DEEP_LINK_SCHEME = "t3code"

    /**
     * Rebuilds the pairing URL from the two form fields.
     *
     * A blank code returns the host verbatim so a full pairing URL pasted into
     * the host field passes through untouched. A schemeless IP literal gets
     * `http://` (LAN pairing is plain HTTP), any other schemeless host gets
     * `https://` — matching `buildPairingUrl` in T3 Code mobile.
     */
    fun buildPairingUrl(host: String, code: String): String {
        val trimmedHost = host.trim()
        val trimmedCode = code.trim()
        if (trimmedHost.isEmpty()) return ""
        if (trimmedCode.isEmpty()) return trimmedHost

        val withScheme = if (trimmedHost.contains("://")) {
            trimmedHost
        } else {
            val scheme = if (isIpLiteral(trimmedHost)) "http" else "https"
            "$scheme://$trimmedHost"
        }

        return runCatching {
            val builder = URLBuilder(withScheme)
            builder.fragment = "$TOKEN_PARAM=${trimmedCode.encodeURLQueryComponent()}"
            builder.buildString()
        }.getOrElse { "$trimmedHost#$TOKEN_PARAM=$trimmedCode" }
    }

    /**
     * Splits a pairing URL back into the form's fields.
     *
     * Hosted links (`https://app.t3.codes/pair?host=…#token=…`) surface the real
     * backend as the host; direct links keep their origin and lose path, query
     * and fragment. Anything that does not parse as a URL is left in the host
     * field for the resolver to reject with a precise message on submit.
     */
    fun parsePairingUrl(pairingUrl: String): PairingFields {
        val trimmed = pairingUrl.trim()
        if (trimmed.isEmpty()) return PairingFields(host = "", code = "")

        // Ktor parses schemeless text leniently where `new URL` throws; the
        // explicit scheme check keeps the TypeScript behaviour of leaving
        // non-URLs alone.
        val url = trimmed.takeIf { it.contains("://") }
            ?.let { runCatching { Url(it) }.getOrNull() }
            ?: return PairingFields(host = trimmed, code = "")

        // The fragment token outranks the query token, matching the order the
        // TypeScript parser (and the desktop's link builder) use.
        val fragmentToken = parseQueryString(url.fragment)[TOKEN_PARAM]?.trim().orEmpty()
        val queryToken = url.parameters[TOKEN_PARAM]?.trim().orEmpty()
        val code = fragmentToken.ifEmpty { queryToken }

        val hostedBackend = url.parameters[HOSTED_HOST_PARAM]?.trim().orEmpty()
        if (hostedBackend.isNotEmpty() && code.isNotEmpty()) {
            return PairingFields(host = hostedBackend.removeSuffix("/"), code = code)
        }

        val origin = buildString {
            append(url.protocol.name)
            append("://")
            append(url.host)
            if (url.port != url.protocol.defaultPort) {
                append(':')
                append(url.port)
            }
        }
        return PairingFields(host = origin, code = code)
    }

    /**
     * Unwraps a scanned QR payload into a pairing URL.
     *
     * Desktop QR codes usually encode the pairing URL directly; the
     * `t3code://pair?pairingUrl=…` deep-link wrapper is also accepted. Payloads
     * that are neither are returned as-is so the normal input validation
     * decides, with one exception: an empty payload throws
     * [EmptyQrPayloadException].
     */
    fun extractPairingUrlFromQrPayload(payload: String): String {
        val trimmed = payload.trim()
        if (trimmed.isEmpty()) throw EmptyQrPayloadException()

        val url = runCatching { Url(trimmed) }.getOrNull()
        if (url != null && url.protocol.name == MOBILE_DEEP_LINK_SCHEME) {
            val wrapped = url.parameters[MOBILE_PAIRING_URL_PARAM]?.trim().orEmpty()
            if (wrapped.isNotEmpty()) return wrapped
        }
        return trimmed
    }

    /**
     * Whether [host] is a bare IPv4/IPv6 literal (optionally with a port).
     * Port of `isIpLiteral`: these default to `http://` because LAN pairing
     * has no certificates, while names default to `https://`.
     */
    fun isIpLiteral(host: String): Boolean {
        val withoutPort = host.substringBefore('/')
        if (withoutPort.startsWith('[')) return true // bracketed IPv6

        val bare = withoutPort.substringBefore(':')
        if (withoutPort.count { it == ':' } >= 2) return true // raw IPv6

        val octets = bare.split('.')
        return octets.size == 4 && octets.all { part ->
            part.toIntOrNull()?.let { it in 0..255 } == true
        }
    }
}
