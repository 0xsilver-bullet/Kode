package com.silverbullet.kode.core.designsystem

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import com.mikepenz.markdown.annotator.DefaultAnnotatorSettings
import com.mikepenz.markdown.model.markdownAnnotator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

class KodeMarkdownTableTest {

    // --- column width policy ------------------------------------------------

    @Test
    fun narrowTableFillsTheViewportExactly() {
        val widths = resolveColumnWidths(
            natural = intArrayOf(100, 200, 100),
            viewportPx = 1000,
            minWidthPx = 50,
            capPx = 700,
        )
        assertEquals(1000, widths.sum())
        // Slack is shared in proportion to content, so the widest column stays widest.
        assertTrue(widths[1] > widths[0] && widths[1] > widths[2], widths.toList().toString())
    }

    @Test
    fun oneVerboseColumnIsCappedInsteadOfSqueezingTheOthers() {
        val widths = resolveColumnWidths(
            natural = intArrayOf(150, 4000),
            viewportPx = 1000,
            minWidthPx = 50,
            capPx = 700,
        )
        // The cap keeps the short column at (near) its content width instead of
        // the library's flat 50/50 split, and the table still fits the viewport,
        // so the long cell wraps rather than being ellipsed away.
        assertEquals(1000, widths.sum())
        assertTrue(widths[0] in 150..200, widths.toList().toString())
        assertTrue(widths[1] >= 700, widths.toList().toString())
    }

    @Test
    fun twoVerboseColumnsOverflowAndScroll() {
        val widths = resolveColumnWidths(
            natural = intArrayOf(4000, 4000),
            viewportPx = 1000,
            minWidthPx = 50,
            capPx = 700,
        )
        assertEquals(listOf(700, 700), widths.toList())
        assertTrue(widths.sum() > 1000)
    }

    @Test
    fun manyColumnsStayAtTheirNaturalWidthAndOverflow() {
        val natural = IntArray(6) { 300 }
        val widths = resolveColumnWidths(
            natural = natural,
            viewportPx = 1000,
            minWidthPx = 50,
            capPx = 700,
        )
        assertEquals(natural.toList(), widths.toList())
    }

    @Test
    fun emptyColumnsStillGetTheMinimumWidth() {
        val widths = resolveColumnWidths(
            natural = intArrayOf(0, 0),
            viewportPx = 1000,
            minWidthPx = 50,
            capPx = 700,
        )
        assertEquals(1000, widths.sum())
        assertTrue(widths.all { it >= 50 })
    }

    @Test
    fun noColumnsIsNotAnError() {
        assertEquals(
            0,
            resolveColumnWidths(intArrayOf(), viewportPx = 1000, minWidthPx = 50, capPx = 700).size,
        )
    }

    // --- cell extraction ----------------------------------------------------

    @Test
    fun headerAndRowCellsAreExtractedInOrder() {
        val table = buildTable(
            """
            | Suite | Result |
            | --- | --- |
            | Backend unit | 1614 passed (73 files) |
            | Client tests | 361 passed |
            """.trimIndent(),
        )!!

        assertEquals(2, table.columns)
        assertEquals(listOf("Suite", "Result"), table.header.map { it.text.trim() })
        assertEquals(
            listOf(
                listOf("Backend unit", "1614 passed (73 files)"),
                listOf("Client tests", "361 passed"),
            ),
            table.rows.map { row -> row.map { it.text.trim() } },
        )
    }

    @Test
    fun overlongRowsAreTruncatedToTheHeaderWidth() {
        val table = buildTable(
            """
            | A | B |
            | --- | --- |
            | 1 | 2 | 3 |
            """.trimIndent(),
        )!!

        assertEquals(2, table.columns)
        assertEquals(listOf(listOf("1", "2")), table.rows.map { row -> row.map { it.text.trim() } })
    }

    @Test
    fun aTableWithoutAHeaderIsSkipped() {
        val node = parse("Just a paragraph.")
        assertNull(
            buildMarkdownTable(node = node, content = "Just a paragraph.", style = Style, settings = Settings),
        )
    }

    private fun buildTable(markdown: String): MarkdownTableContent? {
        val table = parse(markdown).findChildOfType(GFMElementTypes.TABLE) ?: return null
        return buildMarkdownTable(node = table, content = markdown, style = Style, settings = Settings)
    }

    private fun parse(markdown: String): ASTNode =
        MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(markdown)

    private companion object {
        val Style = TextStyle.Default
        val Settings = DefaultAnnotatorSettings(
            linkTextSpanStyle = TextLinkStyles(style = SpanStyle()),
            codeSpanStyle = SpanStyle(),
            annotator = markdownAnnotator(),
        )
    }
}
