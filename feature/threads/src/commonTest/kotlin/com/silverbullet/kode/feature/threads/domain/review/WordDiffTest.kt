package com.silverbullet.kode.feature.threads.domain.review

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WordDiffTest {

    @Test
    fun highlightsTheChangedWordOnly() {
        val result = computeWordDiff(
            "val count = oldValue + 1",
            "val count = newValue + 1",
        )
        val oldRange = result!!.oldRanges.single()
        val newRange = result.newRanges.single()
        assertEquals("oldValue", "val count = oldValue + 1".substring(oldRange.first, oldRange.last + 1))
        assertEquals("newValue", "val count = newValue + 1".substring(newRange.first, newRange.last + 1))
    }

    @Test
    fun dropsHighlightsWhenMostOfTheLineChanged() {
        // Entirely different lines: coverage exceeds the 45 % cap.
        assertNull(computeWordDiff("alpha beta gamma delta", "one two three four"))
    }

    @Test
    fun skipsVeryLongLines() {
        val long = "a ".repeat(600)
        assertNull(computeWordDiff(long, long + "b"))
    }

    @Test
    fun identicalLinesProduceNothing() {
        assertNull(computeWordDiff("same line", "same line"))
    }

    @Test
    fun rowBuilderPairsDeleteAndAddRuns() {
        val diff = """
            diff --git a/a.kt b/a.kt
            --- a/a.kt
            +++ b/a.kt
            @@ -1,3 +1,3 @@
             context
            -val port = 8080 // service port and some more words here
            +val port = 9090 // service port and some more words here
        """.trimIndent()

        val data = buildReviewRows(parseUnifiedDiff(diff))
        val lines = data.blocks.single().body.filterIsInstance<ReviewRow.Line>()
        val delete = lines.first { it.kind == DiffLineKind.Delete }
        val add = lines.first { it.kind == DiffLineKind.Add }
        assertTrue(delete.highlights.isNotEmpty())
        assertTrue(add.highlights.isNotEmpty())
        assertEquals(
            "8080",
            delete.text.substring(
                delete.highlights.single().first,
                delete.highlights.single().last + 1,
            ),
        )
        assertEquals(
            "9090",
            add.text.substring(add.highlights.single().first, add.highlights.single().last + 1),
        )
    }

    @Test
    fun binaryFileGetsNoticeRow() {
        val diff = """
            diff --git a/logo.png b/logo.png
            Binary files a/logo.png and b/logo.png differ
        """.trimIndent()
        val data = buildReviewRows(parseUnifiedDiff(diff))
        val notice = data.blocks.single().body.single() as ReviewRow.Notice
        assertEquals("Unsupported format. Diff contents are not available.", notice.text)
    }
}
