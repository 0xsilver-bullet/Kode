package com.silverbullet.kode.voice.contract

/**
 * Pairing links look like `http(s)://host:port/pair#code=ABCD2345`. The one-time code
 * travels in the URL *fragment*, mirroring t3's pairing links: fragments never appear in
 * request lines, proxy logs, or Referer headers.
 */
object VoicePairingLink {

    const val PAIR_PAGE_PATH: String = "/pair"
    private const val CODE_FRAGMENT_KEY = "code="

    data class Parsed(
        /** Normalized base URL with a trailing slash, e.g. `https://host:8484/`. */
        val baseUrl: String,
        val code: String,
    )

    fun build(baseUrl: String, code: String): String {
        val normalized = normalizeBaseUrl(baseUrl)
        return normalized.dropLast(1) + PAIR_PAGE_PATH + "#" + CODE_FRAGMENT_KEY + code
    }

    fun parse(url: String): Parsed? {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return null
        val fragmentIndex = trimmed.indexOf('#')
        if (fragmentIndex < 0) return null
        val code = trimmed.substring(fragmentIndex + 1)
            .split('&')
            .firstOrNull { it.startsWith(CODE_FRAGMENT_KEY) }
            ?.removePrefix(CODE_FRAGMENT_KEY)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val withoutFragment = trimmed.substring(0, fragmentIndex)
        val schemeEnd = withoutFragment.indexOf("://") + 3
        val pathIndex = withoutFragment.indexOf('/', startIndex = schemeEnd)
        val origin = if (pathIndex < 0) withoutFragment else withoutFragment.substring(0, pathIndex)
        if (origin.length <= schemeEnd) return null
        return Parsed(baseUrl = "$origin/", code = code)
    }

    fun normalizeBaseUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        require(trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            "Voice server URL must start with http:// or https://"
        }
        return "$trimmed/"
    }
}
