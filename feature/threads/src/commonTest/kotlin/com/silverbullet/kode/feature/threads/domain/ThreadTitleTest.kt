package com.silverbullet.kode.feature.threads.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pins the port of T3 Code mobile's `deriveThreadTitleFromPrompt`. */
class ThreadTitleTest {

    @Test
    fun `falls back for a blank prompt`() {
        assertEquals("New thread", deriveThreadTitleFromPrompt("   "))
    }

    @Test
    fun `collapses whitespace and trims`() {
        assertEquals(
            "Fix the login bug",
            deriveThreadTitleFromPrompt("  Fix the\n\n  login\tbug "),
        )
    }

    @Test
    fun `keeps a short prompt as-is`() {
        val prompt = "a".repeat(72)
        assertEquals(prompt, deriveThreadTitleFromPrompt(prompt))
    }

    @Test
    fun `truncates a long prompt to 72 characters with an ellipsis`() {
        val title = deriveThreadTitleFromPrompt("word ".repeat(40))
        assertTrue(title.length <= 72, "title was ${title.length} chars: $title")
        assertTrue(title.endsWith("..."))
        // The cut point is trimmed so the ellipsis never follows a space.
        assertTrue(!title.endsWith(" ..."))
    }
}
