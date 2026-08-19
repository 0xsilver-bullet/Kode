package com.silverbullet.kode.feature.threads.ui.review

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.silverbullet.kode.core.designsystem.KodeTheme
import com.silverbullet.kode.feature.threads.domain.review.DiffLineKind
import com.silverbullet.kode.feature.threads.domain.review.ReviewRow
import com.silverbullet.kode.feature.threads.ui.ToolDetailStyle
import kotlin.math.max
import kotlin.math.min

/**
 * The diff body, rendered as a single draw-phase canvas — the Compose port of
 * t3code's `T3ReviewDiffView` architecture, replacing the previous
 * LazyColumn-in-horizontalScroll implementation whose per-row composables made
 * every fling a composition/layout storm.
 *
 * Why this is fast:
 * - **Virtualization is offset math, not composition.** Row heights are fixed
 *   per row kind, so a prefix-sum `IntArray` (t3code's `rowOffsets`) plus one
 *   binary search finds the first visible row; drawing walks rows until the
 *   viewport bottom. Nothing exists per row — no nodes, no modifiers, no
 *   measure passes.
 * - **Scrolling never recomposes.** The two scroll offsets are `FloatState`s
 *   written by `Modifier.scrollable` (which also supplies fling) and read
 *   *only inside the draw lambda* — a state read in the draw phase invalidates
 *   draw alone, so a scroll frame is one `drawBehind` execution.
 * - **Text layout is measured once and cached.** A bounded insertion-ordered
 *   cache keyed by string holds `TextLayoutResult`s (line text, gutter
 *   numbers, hunk headers); a cache hit is a single map lookup with zero
 *   allocation. Colors are applied at `drawText` time, so theme flips never
 *   invalidate layouts.
 * - **Monospace makes word-diff highlights arithmetic.** One glyph advance is
 *   measured up front; highlight rects are `start × advance` — no
 *   AnnotatedString spans, no per-bind string building.
 *
 * Behavioral notes: file headers (and the notice rows) are pinned to the
 * viewport while code pans horizontally under a clip that starts after the
 * pinned gutters, and the current file's header sticks to the viewport top —
 * both straight from the t3code reference.
 */
@Composable
internal fun DiffCanvas(
    rows: List<ReviewRow>,
    collapsedFileIds: Set<String>,
    viewedFileIds: Set<String>,
    maxLineLength: Int,
    onToggleFile: (String) -> Unit,
    onToggleViewed: (String) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Identity of the content being shown (the section id). Changing it resets
     * both scroll offsets; without this, switching from a long section to a
     * short one lands clamped at the new section's end instead of its top.
     * Fold/viewed toggles change [rows] but not this key, so they keep the
     * reading position.
     */
    resetKey: Any? = null,
) {
    // The measurer's own cache is disabled: every measurement funnels through
    // the explicit caches below, so a second cache would only add key hashing.
    val textMeasurer = rememberTextMeasurer(cacheSize = 0)
    val density = LocalDensity.current
    val typography = MaterialTheme.typography

    // All dp→px conversion happens here, once per density change.
    val metrics = remember(textMeasurer, density, typography) {
        DiffMetrics(textMeasurer, density, typography)
    }
    val layout = remember(rows, metrics) { DiffLayout(rows, metrics) }

    val colors = KodeTheme.colors
    val scheme = MaterialTheme.colorScheme
    val palette = remember(colors, scheme) {
        DiffPalette(
            onSurface = scheme.onSurface,
            muted = colors.muted,
            success = colors.success,
            danger = colors.danger,
            primary = scheme.primary,
            headerBackground = scheme.surfaceContainerHigh,
            codeBackground = colors.codeBackground,
            addedBackground = colors.diffAddedBackground,
            removedBackground = colors.diffRemovedBackground,
            addedHighlight = colors.diffAddedHighlight,
            removedHighlight = colors.diffRemovedHighlight,
        )
    }

    // Pan surface width, capped so a minified single-line file cannot demand
    // an absurd surface — the same reason t3code fixes its canvas width.
    val sizingLength = min(maxLineLength, MAX_PAN_CHARS)
    val contentWidth = metrics.gutterBlock + metrics.panSlack +
        metrics.charWidth * (sizingLength + 2)

    // Rebuilt only when inputs actually change (rare: section switch, fold or
    // viewed toggles, theme flip) — never on scroll.
    val painter = remember(layout, palette, collapsedFileIds, viewedFileIds, contentWidth) {
        DiffPainter(rows, layout, metrics, palette, collapsedFileIds, viewedFileIds, contentWidth)
    }

    // Mutable geometry shared with the scroll and tap lambdas. Plain vars
    // (not snapshot state) except the offsets — only the draw phase observes
    // those. Keyed on the section identity so a section switch starts at the
    // top with fresh offsets; `rememberScrollableState` wraps its lambda in
    // `rememberUpdatedState`, so the (unkeyed) scroll states clamp against the
    // replacement runtime on the very next event.
    val runtime = remember(resetKey) { DiffCanvasRuntime() }
    runtime.rows = rows
    runtime.layout = layout
    runtime.headerRowHeight = metrics.headerRowHeight
    runtime.bottomPadding = metrics.bottomPadding
    runtime.viewedZone = metrics.viewedZone
    runtime.contentWidth = contentWidth
    runtime.onToggleFile = onToggleFile
    runtime.onToggleViewed = onToggleViewed

    // scrollable's delta convention: positive = finger moves down/right, which
    // reveals earlier content, so the offset moves opposite the delta. The
    // consumed amount is returned so flings stop cleanly at the clamps.
    val verticalState = rememberScrollableState { delta ->
        val old = runtime.scrollY.floatValue
        val new = (old - delta).coerceIn(0f, runtime.maxScrollY())
        runtime.scrollY.floatValue = new
        old - new
    }
    val horizontalState = rememberScrollableState { delta ->
        val old = runtime.scrollX.floatValue
        val new = (old - delta).coerceIn(0f, runtime.maxScrollX())
        runtime.scrollX.floatValue = new
        old - new
    }

    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged {
                runtime.viewportWidth = it.width
                runtime.viewportHeight = it.height
            }
            .scrollable(verticalState, Orientation.Vertical)
            .scrollable(horizontalState, Orientation.Horizontal)
            .pointerInput(runtime) { detectTapGestures(onTap = runtime::handleTap) }
            .drawBehind {
                // The only place the scroll offsets are read: scrolling
                // re-executes this lambda and nothing else.
                with(painter) {
                    drawDiff(runtime.scrollX.floatValue, runtime.scrollY.floatValue)
                }
            },
    )
}

// --------------------------------------------------------------------- sizing

private const val MAX_PAN_CHARS = 512
private const val CODE_CACHE_CAPACITY = 600
private const val UI_CACHE_CAPACITY = 128

private val GutterWidth = 40.dp
private val GutterEndPadding = 6.dp
private val MarkerWidth = 14.dp
private val HeaderHeight = 48.dp
private val HeaderHorizontalPadding = 10.dp
/** Trailing header zone whose taps toggle viewed instead of collapse. */
private val ViewedTapZone = 44.dp
private val BottomContentPadding = 32.dp

/**
 * Pixel geometry plus the text-layout caches. Built once per density/theme
 * typography change; the caches live here because a layout is only valid for
 * the density it was measured at.
 */
private class DiffMetrics(
    measurer: TextMeasurer,
    density: Density,
    typography: Typography,
) {
    val codeCache = TextLayoutCache(measurer, ToolDetailStyle, CODE_CACHE_CAPACITY)
    val noticeCache = TextLayoutCache(measurer, typography.bodySmall, UI_CACHE_CAPACITY)
    val countCache = TextLayoutCache(measurer, typography.labelMedium, UI_CACHE_CAPACITY)
    val headerPathCache = EllipsizedTextLayoutCache(measurer, typography.bodySmall, UI_CACHE_CAPACITY)
    val headerSubtitleCache = EllipsizedTextLayoutCache(measurer, typography.labelSmall, UI_CACHE_CAPACITY)

    val gutterWidth = with(density) { GutterWidth.toPx() }
    val gutter1Right = with(density) { gutterWidth - GutterEndPadding.toPx() }
    val gutter2Right = with(density) { gutterWidth * 2 - GutterEndPadding.toPx() }
    val markerX = gutterWidth * 2

    /** Left edge of the panning code area; also the pan clip's left edge. */
    val gutterBlock = with(density) { gutterWidth * 2 + MarkerWidth.toPx() }
    val noticeIndent = gutterWidth * 2
    val panSlack = with(density) { 24.dp.toPx() }
    val bottomPadding = with(density) { BottomContentPadding.roundToPx() }

    val headerRowHeight = with(density) { HeaderHeight.roundToPx() }
    val headerPadding = with(density) { HeaderHorizontalPadding.toPx() }
    val chevronCenterX = with(density) { headerPadding + 8.dp.toPx() }
    val chevronHalf = with(density) { 4.5.dp.toPx() }
    val headerTextStart = with(density) { headerPadding + 24.dp.toPx() }
    val eyeWidth = with(density) { 18.dp.toPx() }
    val eyeArc = with(density) { 12.dp.toPx() }
    val eyePupilRadius = with(density) { 2.dp.toPx() }
    val countGap = with(density) { 8.dp.toPx() }
    val countGapSmall = with(density) { 6.dp.toPx() }
    val viewedZone = with(density) { ViewedTapZone.toPx() }
    val iconStroke = Stroke(
        width = with(density) { 1.5.dp.toPx() },
        cap = StrokeCap.Round,
        join = StrokeJoin.Round,
    )

    /** Reused by the icon draws — draw runs on one thread, so this is safe. */
    val scratchPath = Path()

    // The monospace style carries an explicit lineHeight, so one sample
    // measurement fixes both the glyph advance and every line row height.
    private val sample = codeCache.get("0000000000")

    /** One monospace glyph advance — the basis of all horizontal code math. */
    val charWidth = sample.size.width / 10f
    val lineRowHeight = sample.size.height
    val hunkRowHeight = lineRowHeight + with(density) { 6.dp.roundToPx() }
    val noticeRowHeight = noticeCache.get("0").size.height + with(density) { 16.dp.roundToPx() }

    fun rowHeight(row: ReviewRow): Int = when (row) {
        is ReviewRow.FileHeader -> headerRowHeight
        is ReviewRow.Hunk -> hunkRowHeight
        is ReviewRow.Line -> lineRowHeight
        is ReviewRow.Notice -> noticeRowHeight
    }
}

/**
 * Prefix-sum row offsets plus nearest-file-header indexes — t3code's
 * `rowOffsets`/`rebuildOffsets` scheme. Rebuilt only when the row list
 * changes; every per-frame question ("which row is at y?", "which header is
 * sticky?") is then O(log n) or O(1).
 */
private class DiffLayout(rows: List<ReviewRow>, metrics: DiffMetrics) {
    val offsets = IntArray(rows.size + 1)

    /** Nearest FileHeader index at or before i; -1 when none. */
    val prevHeader = IntArray(rows.size)

    /** First FileHeader index after i; -1 when none. */
    val nextHeader = IntArray(rows.size)

    private val lastIndex = rows.size - 1

    val totalHeight: Int get() = offsets[offsets.size - 1]

    init {
        var prev = -1
        for (i in rows.indices) {
            offsets[i + 1] = offsets[i] + metrics.rowHeight(rows[i])
            if (rows[i] is ReviewRow.FileHeader) prev = i
            prevHeader[i] = prev
        }
        var next = -1
        for (i in rows.indices.reversed()) {
            nextHeader[i] = next
            if (rows[i] is ReviewRow.FileHeader) next = i
        }
    }

    /** Index of the row containing content-y, clamped into range. */
    fun rowIndexAt(y: Int): Int {
        if (lastIndex < 0) return 0
        var low = 0
        var high = lastIndex
        while (low <= high) {
            val middle = (low + high) ushr 1
            when {
                y < offsets[middle] -> high = middle - 1
                y >= offsets[middle + 1] -> low = middle + 1
                else -> return middle
            }
        }
        return low.coerceIn(0, lastIndex)
    }
}

// --------------------------------------------------------------------- caches

/**
 * Bounded insertion-ordered cache of single-line `TextLayoutResult`s.
 *
 * Deliberately FIFO rather than true LRU: a hit is then one map lookup with
 * zero allocation (re-linking an access-ordered map allocates per hit, and
 * Kotlin common has no access-ordered LinkedHashMap anyway). With ~50 visible
 * rows against hundreds of slots, evicting a still-visible entry is rare and
 * costs one re-measure. Keys are the strings themselves, so repeated content
 * (gutter numbers, common lines) share one layout.
 */
private class TextLayoutCache(
    private val measurer: TextMeasurer,
    private val style: TextStyle,
    private val capacity: Int,
) {
    private val map = LinkedHashMap<String, TextLayoutResult>()

    fun get(text: String): TextLayoutResult {
        map[text]?.let { return it }
        val layout = measurer.measure(
            text = AnnotatedString(text),
            style = style,
            softWrap = false,
            maxLines = 1,
        )
        if (map.size >= capacity) {
            val iterator = map.keys.iterator()
            iterator.next()
            iterator.remove()
        }
        map[text] = layout
        return layout
    }
}

/** Like [TextLayoutCache] but width-constrained with ellipsis, for headers. */
private class EllipsizedTextLayoutCache(
    private val measurer: TextMeasurer,
    private val style: TextStyle,
    private val capacity: Int,
) {
    private val map = LinkedHashMap<String, TextLayoutResult>()

    fun get(text: String, maxWidthPx: Int): TextLayoutResult {
        // Keyed by width too: headers re-ellipsize when the viewport changes.
        // Key building allocates, but only headers pass through here (a
        // handful per frame at most).
        val key = "$maxWidthPx:$text"
        map[key]?.let { return it }
        val layout = measurer.measure(
            text = AnnotatedString(text),
            style = style,
            overflow = TextOverflow.Ellipsis,
            softWrap = false,
            maxLines = 1,
            constraints = Constraints(maxWidth = maxWidthPx),
        )
        if (map.size >= capacity) {
            val iterator = map.keys.iterator()
            iterator.next()
            iterator.remove()
        }
        map[key] = layout
        return layout
    }
}

// -------------------------------------------------------------------- palette

private class DiffPalette(
    val onSurface: Color,
    val muted: Color,
    val success: Color,
    val danger: Color,
    val primary: Color,
    val headerBackground: Color,
    val codeBackground: Color,
    val addedBackground: Color,
    val removedBackground: Color,
    val addedHighlight: Color,
    val removedHighlight: Color,
)

// -------------------------------------------------------------------- runtime

/**
 * Mutable geometry the scroll-clamp and tap lambdas read at event time.
 * Fields are reassigned during composition; only the scroll offsets are
 * snapshot state, because only the draw phase must observe them.
 */
private class DiffCanvasRuntime {
    val scrollX = mutableFloatStateOf(0f)
    val scrollY = mutableFloatStateOf(0f)
    var viewportWidth = 0
    var viewportHeight = 0
    var rows: List<ReviewRow> = emptyList()
    var layout: DiffLayout? = null
    var headerRowHeight = 0
    var bottomPadding = 0
    var viewedZone = 0f
    var contentWidth = 0f
    var onToggleFile: (String) -> Unit = {}
    var onToggleViewed: (String) -> Unit = {}

    fun maxScrollY(): Float {
        val layout = layout ?: return 0f
        return max(0, layout.totalHeight + bottomPadding - viewportHeight).toFloat()
    }

    fun maxScrollX(): Float = max(0f, contentWidth - viewportWidth)

    /**
     * Hit-test in content space: the sticky header shadows whatever sits
     * under it (t3code checks it first for the same reason), then a binary
     * search maps y to a row. Only file headers react to taps.
     */
    fun handleTap(position: Offset) {
        val layout = layout ?: return
        val rows = rows
        if (rows.isEmpty()) return
        val y = min(scrollY.floatValue, maxScrollY()).toInt()

        val first = layout.rowIndexAt(y)
        val stickyIndex = layout.prevHeader[first]
        if (stickyIndex >= 0) {
            val next = layout.nextHeader[stickyIndex]
            val top = if (next >= 0) {
                min(0, layout.offsets[next] - y - headerRowHeight)
            } else {
                0
            }
            if (position.y >= max(0, top) && position.y < top + headerRowHeight) {
                dispatchHeaderTap(rows[stickyIndex] as ReviewRow.FileHeader, position.x)
                return
            }
        }

        val contentY = position.y.toInt() + y
        if (contentY >= layout.totalHeight) return
        val row = rows[layout.rowIndexAt(contentY)]
        if (row is ReviewRow.FileHeader) dispatchHeaderTap(row, position.x)
    }

    private fun dispatchHeaderTap(row: ReviewRow.FileHeader, x: Float) {
        if (x >= viewportWidth - viewedZone) {
            onToggleViewed(row.fileId)
        } else {
            onToggleFile(row.fileId)
        }
    }
}

// -------------------------------------------------------------------- painter

/**
 * Everything the draw lambda needs, captured once per (rare) recomposition.
 * All methods run in the draw phase; none allocate on the steady-state path
 * beyond `Offset`/`Size` value classes and one clip closure per frame.
 */
private class DiffPainter(
    private val rows: List<ReviewRow>,
    private val layout: DiffLayout,
    private val metrics: DiffMetrics,
    private val palette: DiffPalette,
    private val collapsedFileIds: Set<String>,
    private val viewedFileIds: Set<String>,
    private val contentWidth: Float,
) {
    fun DrawScope.drawDiff(scrollXRaw: Float, scrollYRaw: Float) {
        if (rows.isEmpty()) return
        // Clamp at read time: a collapse can shrink content while the stored
        // offset still points past the new end. No state writes in draw.
        val maxY = (layout.totalHeight + metrics.bottomPadding - size.height).coerceAtLeast(0f)
        val y = min(scrollYRaw, maxY).toInt()
        val x = min(scrollXRaw, max(0f, contentWidth - size.width))

        val first = layout.rowIndexAt(y)
        val viewportBottom = size.height

        // Pass A — viewport-pinned chrome: backgrounds, gutters, markers,
        // notices, and in-flow file headers. Nothing here pans horizontally.
        var index = first
        while (index < rows.size) {
            val top = (layout.offsets[index] - y).toFloat()
            if (top >= viewportBottom) break
            val bottom = (layout.offsets[index + 1] - y).toFloat()
            when (val row = rows[index]) {
                is ReviewRow.FileHeader -> drawFileHeader(row, top)
                is ReviewRow.Hunk -> drawRect(
                    color = palette.codeBackground,
                    topLeft = Offset(0f, top),
                    size = Size(size.width, bottom - top),
                )

                is ReviewRow.Line -> drawLineChrome(row, top, bottom)
                is ReviewRow.Notice -> drawNotice(row, top, bottom)
            }
            index++
        }
        val lastExclusive = index

        // Pass B — panning code, in one clip that starts after the gutters so
        // scrolled text never runs over the line numbers.
        clipRect(left = metrics.gutterBlock, top = 0f, right = size.width, bottom = viewportBottom) {
            val codeX = metrics.gutterBlock - x
            var j = first
            while (j < lastExclusive) {
                val top = (layout.offsets[j] - y).toFloat()
                val bottom = (layout.offsets[j + 1] - y).toFloat()
                when (val row = rows[j]) {
                    is ReviewRow.Line -> drawLineCode(row, codeX, top, bottom)
                    is ReviewRow.Hunk -> {
                        val header = metrics.codeCache.get(row.header)
                        drawText(
                            textLayoutResult = header,
                            color = palette.muted,
                            topLeft = Offset(codeX, top + (bottom - top - header.size.height) / 2f),
                        )
                    }

                    else -> Unit
                }
                j++
            }
        }

        // Pass C — the sticky header, drawn last so it covers scrolled code.
        // Skipped when the header is already at its natural position.
        val stickyIndex = layout.prevHeader[first]
        if (stickyIndex >= 0) {
            val next = layout.nextHeader[stickyIndex]
            val top = if (next >= 0) {
                min(0, layout.offsets[next] - y - metrics.headerRowHeight)
            } else {
                0
            }
            if (layout.offsets[stickyIndex] - y != top) {
                drawFileHeader(rows[stickyIndex] as ReviewRow.FileHeader, top.toFloat())
            }
        }
    }

    private fun DrawScope.drawLineChrome(row: ReviewRow.Line, top: Float, bottom: Float) {
        val background = when (row.kind) {
            DiffLineKind.Add -> palette.addedBackground
            DiffLineKind.Delete -> palette.removedBackground
            DiffLineKind.Context -> null
        }
        if (background != null) {
            // Viewport-wide, never content-wide: the old implementation drew
            // a huge fixed-width background per row.
            drawRect(background, Offset(0f, top), Size(size.width, bottom - top))
        }

        if (row.oldLineText.isNotEmpty()) {
            val number = metrics.codeCache.get(row.oldLineText)
            drawText(
                textLayoutResult = number,
                color = palette.muted,
                topLeft = Offset(metrics.gutter1Right - number.size.width, top),
            )
        }
        if (row.newLineText.isNotEmpty()) {
            val number = metrics.codeCache.get(row.newLineText)
            drawText(
                textLayoutResult = number,
                color = palette.muted,
                topLeft = Offset(metrics.gutter2Right - number.size.width, top),
            )
        }

        when (row.kind) {
            DiffLineKind.Add -> drawText(
                textLayoutResult = metrics.codeCache.get("+"),
                color = palette.success,
                topLeft = Offset(metrics.markerX, top),
            )

            DiffLineKind.Delete -> drawText(
                textLayoutResult = metrics.codeCache.get("−"),
                color = palette.danger,
                topLeft = Offset(metrics.markerX, top),
            )

            DiffLineKind.Context -> Unit
        }
    }

    private fun DrawScope.drawLineCode(row: ReviewRow.Line, codeX: Float, top: Float, bottom: Float) {
        val highlights = row.highlights
        if (highlights.isNotEmpty() && row.kind != DiffLineKind.Context) {
            val color = if (row.kind == DiffLineKind.Add) {
                palette.addedHighlight
            } else {
                palette.removedHighlight
            }
            // Monospace: rect x/width are pure glyph-advance arithmetic, so
            // no spans and no re-layout. (Tabs would skew this; word-diff
            // ranges on tab-bearing lines are the accepted rounding error.)
            for (k in highlights.indices) {
                val range = highlights[k]
                drawRect(
                    color = color,
                    topLeft = Offset(codeX + range.first * metrics.charWidth, top),
                    size = Size((range.last + 1 - range.first) * metrics.charWidth, bottom - top),
                )
            }
        }
        if (row.text.isNotEmpty()) {
            drawText(
                textLayoutResult = metrics.codeCache.get(row.text),
                color = palette.onSurface,
                topLeft = Offset(codeX, top),
            )
        }
    }

    private fun DrawScope.drawNotice(row: ReviewRow.Notice, top: Float, bottom: Float) {
        val text = metrics.noticeCache.get(row.text)
        drawText(
            textLayoutResult = text,
            color = palette.muted,
            topLeft = Offset(metrics.noticeIndent, top + (bottom - top - text.size.height) / 2f),
        )
    }

    private fun DrawScope.drawFileHeader(row: ReviewRow.FileHeader, top: Float) {
        val height = metrics.headerRowHeight.toFloat()
        val centerY = top + height / 2f
        drawRect(palette.headerBackground, Offset(0f, top), Size(size.width, height))

        drawChevron(centerY, collapsed = row.fileId in collapsedFileIds)

        val eyeLeft = size.width - metrics.headerPadding - metrics.eyeWidth
        drawEye(eyeLeft, centerY, viewed = row.fileId in viewedFileIds)

        val minus = metrics.countCache.get(row.deletionsText)
        val plus = metrics.countCache.get(row.additionsText)
        val minusX = eyeLeft - metrics.countGap - minus.size.width
        val plusX = minusX - metrics.countGapSmall - plus.size.width
        drawText(plus, color = palette.success, topLeft = Offset(plusX, centerY - plus.size.height / 2f))
        drawText(minus, color = palette.danger, topLeft = Offset(minusX, centerY - minus.size.height / 2f))

        val available = (plusX - metrics.headerTextStart - metrics.countGap).toInt()
        if (available <= 0) return
        val path = metrics.headerPathCache.get(row.path, available)
        val subtitle = row.subtitle?.let { metrics.headerSubtitleCache.get(it, available) }
        if (subtitle == null) {
            drawText(
                textLayoutResult = path,
                color = palette.onSurface,
                topLeft = Offset(metrics.headerTextStart, centerY - path.size.height / 2f),
            )
        } else {
            val textTop = centerY - (path.size.height + subtitle.size.height) / 2f
            drawText(
                textLayoutResult = path,
                color = palette.onSurface,
                topLeft = Offset(metrics.headerTextStart, textTop),
            )
            drawText(
                textLayoutResult = subtitle,
                color = palette.muted,
                topLeft = Offset(metrics.headerTextStart, textTop + path.size.height),
            )
        }
    }

    /** Disclosure chevron: down when expanded, right when collapsed. */
    private fun DrawScope.drawChevron(centerY: Float, collapsed: Boolean) {
        val cx = metrics.chevronCenterX
        val half = metrics.chevronHalf
        val path = metrics.scratchPath
        path.reset()
        if (collapsed) {
            path.moveTo(cx - half / 2f, centerY - half)
            path.lineTo(cx + half / 2f, centerY)
            path.lineTo(cx - half / 2f, centerY + half)
        } else {
            path.moveTo(cx - half, centerY - half / 2f)
            path.lineTo(cx, centerY + half / 2f)
            path.lineTo(cx + half, centerY - half / 2f)
        }
        drawPath(path, palette.muted, style = metrics.iconStroke)
    }

    /** Eye affordance: almond outline plus pupil; primary tint when viewed. */
    private fun DrawScope.drawEye(left: Float, centerY: Float, viewed: Boolean) {
        val tint = if (viewed) palette.primary else palette.muted
        val right = left + metrics.eyeWidth
        val centerX = (left + right) / 2f
        val path = metrics.scratchPath
        path.reset()
        path.moveTo(left, centerY)
        path.quadraticTo(centerX, centerY - metrics.eyeArc, right, centerY)
        path.quadraticTo(centerX, centerY + metrics.eyeArc, left, centerY)
        path.close()
        drawPath(path, tint, style = metrics.iconStroke)
        drawCircle(tint, metrics.eyePupilRadius, Offset(centerX, centerY))
    }
}
