package com.silverbullet.kode.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.annotator.AnnotatorSettings
import com.mikepenz.markdown.annotator.DefaultAnnotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.compose.LocalMarkdownAnnotator
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.LocalMarkdownInlineContent
import com.mikepenz.markdown.compose.LocalMarkdownTypography
import com.mikepenz.markdown.compose.LocalReferenceLinkHandler
import com.mikepenz.markdown.compose.elements.MarkdownDivider
import com.mikepenz.markdown.compose.elements.material.MarkdownBasicText
import com.mikepenz.markdown.utils.codeSpanStyle
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.flavours.gfm.GFMElementTypes.HEADER
import org.intellij.markdown.flavours.gfm.GFMElementTypes.ROW
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.CELL

/**
 * A GFM table whose cells are actually readable.
 *
 * The library's `MarkdownTable` renders every cell with `maxLines = 1` and
 * `TextOverflow.Ellipsis` inside a `weight(1f)` column, and only turns on
 * horizontal scrolling when `columnCount * 160.dp` exceeds the viewport. Both
 * halves of that are content-blind: a two-column table on a phone never
 * scrolls (320dp fits), each cell gets half the width no matter what it holds,
 * and anything longer is cut off with an ellipsis with no way to reach it —
 * exactly the "Backend unit | 1614 passed (73 fi…" case.
 *
 * This renderer sizes columns from their content instead:
 *
 *  - each column is as wide as its widest cell, capped at [MaxColumnFraction]
 *    of the viewport so one verbose column cannot squeeze the rest away;
 *  - cells wrap instead of ellipsing, so a capped column stays fully readable;
 *  - if the columns still do not fit, the table scrolls horizontally at its
 *    natural width — the swipe gesture that was missing;
 *  - if they fit with room to spare, the slack is shared out in proportion to
 *    content so the table still fills the message width.
 *
 * Performance notes, because this runs inside a lazy list:
 *
 *  - Cell `AnnotatedString`s are built **once** per (node, content, style) and
 *    remembered. The library rebuilt them on every recomposition of the cell.
 *  - Column widths are measured once per (cells, viewport width) with a
 *    remembered [TextMeasurer], never per frame.
 *  - Because the widths are known during composition, every cell gets a fixed
 *    width constraint. There is no `IntrinsicSize`, no `weight`, and no
 *    subcomposition beyond the single [BoxWithConstraints] the library already
 *    paid for — so a re-layout (scrolling the table, or the row re-entering the
 *    viewport) is one text layout per cell, with no measurement negotiation.
 */
@Composable
fun KodeMarkdownTable(
    content: String,
    node: ASTNode,
    style: TextStyle,
) {
    val dimens = LocalMarkdownDimens.current
    val colors = LocalMarkdownColors.current
    val settings = rememberTableAnnotatorSettings()

    val table = remember(node, content, style, settings) {
        buildMarkdownTable(node = node, content = content, style = style, settings = settings)
    }
    if (table == null) return

    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val inlineContent = LocalMarkdownInlineContent.current.inlineContent

    BoxWithConstraints(
        modifier = Modifier
            .background(colors.tableBackground, RoundedCornerShape(dimens.tableCornerSize))
            .semantics {
                collectionInfo = CollectionInfo(
                    rowCount = table.rows.size + 1,
                    columnCount = table.columns,
                )
            },
    ) {
        // `maxWidth` only changes when the message column itself is resized, so
        // this keying costs nothing during scrolling.
        val viewportWidth = maxWidth
        val layout = remember(table, viewportWidth, density, measurer) {
            measureTableLayout(
                table = table,
                style = style,
                viewportWidth = viewportWidth,
                cellPadding = dimens.tableCellPadding,
                density = density,
                measurer = measurer,
            )
        }

        Column(
            modifier = if (layout.scrollable) {
                // A fixed width (rather than the parent's unbounded scroll
                // constraint) is what lets the header divider span the whole
                // table instead of collapsing to zero.
                Modifier.horizontalScroll(rememberScrollState()).width(layout.totalWidth)
            } else {
                Modifier.fillMaxWidth()
            },
        ) {
            TableRow(
                cells = table.header,
                widths = layout.columnWidths,
                rowIndex = 0,
                isHeader = true,
                cellPadding = dimens.tableCellPadding,
                style = style,
                inlineContent = inlineContent,
            )
            MarkdownDivider()
            table.rows.forEachIndexed { index, cells ->
                TableRow(
                    cells = cells,
                    widths = layout.columnWidths,
                    rowIndex = index + 1,
                    isHeader = false,
                    cellPadding = dimens.tableCellPadding,
                    style = style,
                    inlineContent = inlineContent,
                )
            }
        }
    }
}

@Composable
private fun TableRow(
    cells: List<AnnotatedString>,
    widths: List<Dp>,
    rowIndex: Int,
    isHeader: Boolean,
    cellPadding: Dp,
    style: TextStyle,
    inlineContent: Map<String, InlineTextContent>,
) {
    Row(verticalAlignment = Alignment.Top) {
        widths.forEachIndexed { columnIndex, width ->
            val cell = cells.getOrNull(columnIndex)
            if (cell == null) {
                Box(Modifier.width(width))
                return@forEachIndexed
            }
            Box(
                modifier = Modifier
                    .width(width)
                    .padding(cellPadding)
                    .semantics {
                        if (isHeader) heading()
                        collectionItemInfo = CollectionItemInfo(
                            rowIndex = rowIndex,
                            rowSpan = 1,
                            columnIndex = columnIndex,
                            columnSpan = 1,
                        )
                    },
            ) {
                MarkdownBasicText(
                    text = cell,
                    style = style,
                    inlineContent = inlineContent,
                )
            }
        }
    }
}

/**
 * The cell text of one table, already annotated.
 *
 * Held as `AnnotatedString`s rather than AST nodes so both measurement and
 * rendering read the same, already-built text.
 */
@Immutable
internal class MarkdownTableContent(
    val columns: Int,
    val header: List<AnnotatedString>,
    val rows: List<List<AnnotatedString>>,
)

@Immutable
private class MarkdownTableLayout(
    val columnWidths: List<Dp>,
    val totalWidth: Dp,
    val scrollable: Boolean,
)

/** Fraction of the viewport a single column may occupy before it starts wrapping. */
private const val MaxColumnFraction = 0.7f

/** Floor for [MaxColumnFraction], so very narrow viewports still wrap sanely. */
private val MinColumnCap = 144.dp

/** No column is ever narrower than this, so short cells stay tappable columns. */
private val MinColumnWidth = 56.dp

internal fun buildMarkdownTable(
    node: ASTNode,
    content: String,
    style: TextStyle,
    settings: AnnotatorSettings,
): MarkdownTableContent? {
    val headerNode = node.findChildOfType(HEADER) ?: return null
    val headerStyle = style.copy(fontWeight = FontWeight.Bold)
    val header = headerNode.cells(content, headerStyle, settings)
    if (header.isEmpty()) return null

    val rows = ArrayList<List<AnnotatedString>>()
    for (child in node.children) {
        if (child.type != ROW) continue
        // Trailing cells beyond the header count are dropped, as GFM requires,
        // which also keeps every row aligned to the measured column widths.
        rows += child.cells(content, style, settings).take(header.size)
    }

    return MarkdownTableContent(columns = header.size, header = header, rows = rows)
}

private fun ASTNode.cells(
    content: String,
    style: TextStyle,
    settings: AnnotatorSettings,
): List<AnnotatedString> = children.mapNotNull { child ->
    if (child.type != CELL) {
        null
    } else {
        content.buildMarkdownAnnotatedString(
            textNode = child,
            style = style,
            annotatorSettings = settings,
        )
    }
}

/**
 * Resolves column widths from cell content.
 *
 * Each cell is measured once, unwrapped, to get its natural single-line width;
 * a column takes the widest of those, clamped to [MinColumnWidth] and the
 * per-column cap. Everything is integer pixels until the final conversion, so
 * the widths add up exactly to either the viewport or the table width and no
 * rounding gap opens between the last column and the table edge.
 */
private fun measureTableLayout(
    table: MarkdownTableContent,
    style: TextStyle,
    viewportWidth: Dp,
    cellPadding: Dp,
    density: Density,
    measurer: TextMeasurer,
): MarkdownTableLayout {
    val paddingPx = with(density) { cellPadding.roundToPx() } * 2
    // An unbounded parent (nothing in this app, but a table is a public
    // composable) has no viewport to fit to: keep the natural widths.
    val viewportPx = if (viewportWidth.value.isFinite()) {
        with(density) { viewportWidth.roundToPx() }
    } else {
        Int.MAX_VALUE
    }
    val minWidthPx = with(density) { MinColumnWidth.roundToPx() }
        .coerceAtMost(if (table.columns > 0) viewportPx / table.columns else viewportPx)
    val capPx = (viewportPx * MaxColumnFraction).toInt()
        .coerceAtLeast(with(density) { MinColumnCap.roundToPx() }.coerceAtMost(viewportPx))

    fun naturalWidth(cell: AnnotatedString): Int =
        if (cell.isEmpty()) {
            0
        } else {
            measurer.measure(
                text = cell,
                style = style,
                softWrap = false,
                constraints = Constraints(),
            ).size.width
        }

    val natural = IntArray(table.columns) { column ->
        var widest = naturalWidth(table.header[column])
        for (row in table.rows) {
            val cell = row.getOrNull(column) ?: continue
            val width = naturalWidth(cell)
            if (width > widest) widest = width
        }
        widest + paddingPx
    }

    if (viewportPx == Int.MAX_VALUE) {
        val total = natural.sum()
        return MarkdownTableLayout(
            columnWidths = natural.map { with(density) { it.toDp() } },
            totalWidth = with(density) { total.toDp() },
            scrollable = false,
        )
    }

    val widths = resolveColumnWidths(
        natural = natural,
        viewportPx = viewportPx,
        minWidthPx = minWidthPx,
        capPx = capPx,
    )

    val total = widths.sum()
    return MarkdownTableLayout(
        columnWidths = widths.map { with(density) { it.toDp() } },
        totalWidth = with(density) { total.toDp() },
        scrollable = total > viewportPx,
    )
}

/**
 * Turns natural (content) column widths into final ones.
 *
 * Pure integer arithmetic, split out from measurement so the policy can be
 * tested without a font stack:
 *  - clamp every column to `[minWidthPx, capPx]`;
 *  - if the clamped table is narrower than the viewport, share the slack in
 *    proportion to content and give the rounding remainder to the last column,
 *    so the widths sum to exactly the viewport;
 *  - otherwise leave it wider than the viewport, which is the caller's signal
 *    to scroll horizontally.
 */
internal fun resolveColumnWidths(
    natural: IntArray,
    viewportPx: Int,
    minWidthPx: Int,
    capPx: Int,
): IntArray {
    val widths = IntArray(natural.size) { natural[it].coerceIn(minWidthPx, capPx) }
    val total = widths.sum()
    if (widths.isEmpty() || total >= viewportPx) return widths

    val slack = viewportPx - total
    val naturalTotal = natural.sum().coerceAtLeast(1)
    var handedOut = 0
    for (column in 0 until widths.size - 1) {
        val share = (slack.toLong() * natural[column] / naturalTotal).toInt()
        widths[column] += share
        handedOut += share
    }
    widths[widths.size - 1] += slack - handedOut
    return widths
}

/**
 * A stable [AnnotatorSettings] for cell text.
 *
 * The library's `annotatorSettings()` allocates a fresh instance — including a
 * fresh link listener — on every call, which would invalidate the remembered
 * cell text on every recomposition. Keying on the theme-scoped inputs instead
 * means the identity only changes when the theme does.
 */
@Composable
private fun rememberTableAnnotatorSettings(): AnnotatorSettings {
    val typography = LocalMarkdownTypography.current
    val codeSpanStyle = typography.codeSpanStyle
    val annotator = LocalMarkdownAnnotator.current
    val referenceLinkHandler = LocalReferenceLinkHandler.current
    val uriHandler = LocalUriHandler.current

    return remember(typography, codeSpanStyle, annotator, referenceLinkHandler, uriHandler) {
        DefaultAnnotatorSettings(
            linkTextSpanStyle = typography.textLink,
            codeSpanStyle = codeSpanStyle,
            annotator = annotator,
            referenceLinkHandler = referenceLinkHandler,
            linkInteractionListener = LinkInteractionListener { link ->
                val url = (link as? LinkAnnotation.Url)?.url ?: return@LinkInteractionListener
                val target = referenceLinkHandler.find(url).takeIf { it.isNotEmpty() } ?: url
                runCatching { uriHandler.openUri(target) }
            },
        )
    }
}
