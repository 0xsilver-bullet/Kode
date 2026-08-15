package com.silverbullet.kode.feature.threads.domain

/**
 * Derives a thread title from the first prompt, mirroring
 * `deriveThreadTitleFromPrompt` in T3 Code's mobile client: whitespace is
 * collapsed and the result capped at 72 characters. It seeds the server's
 * title generation (`titleSeed`) and doubles as the created thread's title
 * until the server regenerates a better one.
 */
fun deriveThreadTitleFromPrompt(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) {
        return "New thread"
    }

    val compact = trimmed.replace(Regex("\\s+"), " ")
    return if (compact.length <= 72) compact else "${compact.take(69).trimEnd()}..."
}
