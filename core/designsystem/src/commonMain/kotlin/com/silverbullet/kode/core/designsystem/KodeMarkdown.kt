package com.silverbullet.kode.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.model.DefaultMarkdownColors
import com.mikepenz.markdown.model.DefaultMarkdownTypography
import com.mikepenz.markdown.model.ImageTransformer
import com.mikepenz.markdown.model.MarkdownAnimations
import com.mikepenz.markdown.model.MarkdownAnnotator
import com.mikepenz.markdown.model.MarkdownColors
import com.mikepenz.markdown.model.MarkdownDimens
import com.mikepenz.markdown.model.MarkdownExtendedSpans
import com.mikepenz.markdown.model.MarkdownInlineContent
import com.mikepenz.markdown.model.MarkdownPadding
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.NoOpImageTransformerImpl
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.markdownAnimations
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownExtendedSpans
import com.mikepenz.markdown.model.markdownInlineContent
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.model.rememberStreamingMarkdownState
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Every argument `Markdown()` needs, built once per theme.
 *
 * This bundle is not a tidiness exercise — it is the most important fix in this
 * file. `Markdown()` has ten parameters with default expressions, and a default
 * expression is re-evaluated on **every** recomposition. Several of those
 * defaults (`imageTransformer`, `inlineContent`, `annotator`, `extendedSpans`,
 * `animations`) are plain classes with no `equals`, and the renderer publishes
 * them through `staticCompositionLocalOf`. A changed static local disables
 * skipping for the whole subtree beneath it, so leaving the defaults in place
 * meant every recomposition of a message re-walked its AST and rebuilt every
 * `AnnotatedString` — a full text-layout invalidation of the entire message.
 *
 * Stable instances let Compose skip that subtree instead.
 */
@Immutable
class KodeMarkdownConfig internal constructor(
    val colors: MarkdownColors,
    val typography: MarkdownTypography,
    val padding: MarkdownPadding,
    val dimens: MarkdownDimens,
    val imageTransformer: ImageTransformer,
    val annotator: MarkdownAnnotator,
    val extendedSpans: MarkdownExtendedSpans,
    val inlineContent: MarkdownInlineContent,
    val components: MarkdownComponents,
    val animations: MarkdownAnimations,
)

val LocalKodeMarkdownConfig = staticCompositionLocalOf<KodeMarkdownConfig> {
    error("KodeMarkdownConfig requested outside of KodeTheme.")
}

/**
 * Builds the config once per theme change, never per message.
 *
 * Typography sizes come from `resolveMarkdownFontSizes` and the heading scale in
 * `NativeMarkdownSelectableText.ios.tsx`; code is `bodySize - 2` there. Without
 * this the default M3 mapping renders `h1` at `displayLarge` (57sp), which looks
 * nothing like the real app.
 */
@Composable
internal fun rememberKodeMarkdownConfig(): KodeMarkdownConfig {
    val extended = KodeTheme.colors
    val textColor = MaterialTheme.colorScheme.onSurface

    // These builders are @Composable in the library, so they cannot be called
    // from inside `remember`. That is fine: this function runs once per theme,
    // not once per message, and the `remember` below pins whichever instances
    // the first pass produced — which is what keeps the static CompositionLocals
    // beneath `Markdown()` from changing identity.
    val padding = markdownPadding(
        block = 4.dp,
        list = 4.dp,
        listItemTop = 2.dp,
        listItemBottom = 2.dp,
        listIndent = 8.dp,
        codeBlock = PaddingValues(8.dp),
        blockQuote = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        blockQuoteText = PaddingValues(vertical = 4.dp),
    )
    val dimens = markdownDimens(
        dividerThickness = 1.dp,
        codeBackgroundCornerSize = 8.dp,
        blockQuoteThickness = 2.dp,
        tableCellPadding = 8.dp,
        tableCornerSize = 8.dp,
    )
    val annotator = markdownAnnotator()
    val extendedSpans = markdownExtendedSpans()
    val inlineContent = markdownInlineContent()
    // Tables are the one element whose default rendering is unusable on a
    // phone: fixed-share columns with single-line, ellipsed cells and no way to
    // reach the clipped text. See [KodeMarkdownTable].
    val components = markdownComponents(
        table = { model -> KodeMarkdownTable(model.content, model.node, model.typography.table) },
    )
    // The default applies `animateContentSize()` to *every* text segment of
    // every message. During a stream that runs a size animation per paragraph
    // per frame, continuously invalidating the lazy item's layout and making
    // row heights unstable while scrolling. Identity disables it.
    val animations = markdownAnimations { this }

    return remember(extended, textColor) {
        val body = TextStyle(
            fontSize = KodeMarkdownSizes.body,
            lineHeight = KodeMarkdownSizes.bodyLineHeight,
            color = textColor,
        )
        val code = TextStyle(
            fontSize = KodeMarkdownSizes.code,
            lineHeight = KodeMarkdownSizes.codeLineHeight,
            fontFamily = KodeMarkdownSizes.monospace,
            color = textColor,
        )

        // T3 Code renders headings at weight 700.
        fun heading(size: TextUnit, lineHeight: TextUnit) = TextStyle(
            fontSize = size,
            lineHeight = lineHeight,
            fontWeight = FontWeight.Bold,
            color = extended.strong,
        )

        KodeMarkdownConfig(
            colors = DefaultMarkdownColors(
                text = textColor,
                codeBackground = extended.codeBackground,
                inlineCodeBackground = extended.inlineCodeBackground,
                dividerColor = extended.divider,
                tableBackground = extended.codeBackground,
            ),
            typography = DefaultMarkdownTypography(
                h1 = heading(KodeMarkdownSizes.h1, KodeMarkdownSizes.h1LineHeight),
                h2 = heading(KodeMarkdownSizes.h2, KodeMarkdownSizes.h2LineHeight),
                h3 = heading(KodeMarkdownSizes.h3, KodeMarkdownSizes.h3LineHeight),
                h4 = heading(KodeMarkdownSizes.h4, KodeMarkdownSizes.hSmallLineHeight),
                h5 = heading(KodeMarkdownSizes.h5, KodeMarkdownSizes.hSmallLineHeight),
                h6 = heading(KodeMarkdownSizes.h6, KodeMarkdownSizes.hSmallLineHeight),
                text = body,
                code = code,
                inlineCode = code.copy(color = extended.inlineCodeText),
                quote = body.copy(fontStyle = FontStyle.Italic, color = extended.quoteText),
                paragraph = body,
                ordered = body,
                bullet = body,
                list = body,
                textLink = TextLinkStyles(
                    style = body.copy(
                        color = extended.link,
                        textDecoration = TextDecoration.Underline,
                    ).toSpanStyle(),
                ),
                table = body,
            ),
            padding = padding,
            dimens = dimens,
            imageTransformer = NoOpImageTransformerImpl(),
            annotator = annotator,
            extendedSpans = extendedSpans,
            inlineContent = inlineContent,
            components = components,
            animations = animations,
        )
    }
}

/**
 * Renders a settled message, from cache when possible.
 *
 * On a cache hit this renders at full height in the same frame, which is what
 * stops rows collapsing to 0dp and snapping back as you scroll. See
 * [MarkdownParseCache] for why the library's own state cannot do this.
 *
 * [minHeight] reserves the row's last measured height on a cold parse, so the
 * list does not shift under the user.
 */
@Composable
fun KodeMarkdown(
    text: String,
    cacheKey: String,
    modifier: Modifier = Modifier,
    minHeight: Dp = Dp.Unspecified,
) {
    val cache = LocalMarkdownParseCache.current
    val config = LocalKodeMarkdownConfig.current

    var state by remember(cacheKey) { mutableStateOf(cache.peek(cacheKey)) }
    LaunchedEffect(cacheKey) {
        if (state == null) state = cache.parse(cacheKey, text)
    }

    val parsed = state
    if (parsed == null) {
        Box(
            modifier.then(
                if (minHeight != Dp.Unspecified) Modifier.height(minHeight) else Modifier,
            ),
        )
        return
    }

    RenderMarkdown(parsed, config, modifier)
}

/**
 * Renders a message that is still growing.
 *
 * Uses [rememberStreamingMarkdownState], which re-parses only the *unstable
 * tail* of the document rather than the whole thing. That is the difference
 * between O(n) and O(tail) work per token batch, and it matters because T3 Code
 * re-sends the full accumulated text on every assistant delta — a naive
 * re-parse rebuilds the entire AST dozens of times per reply.
 *
 * Because the tail parse is cheap, the input needs no sampling: every delta can
 * be applied directly, which also removes a per-message coroutine.
 */
@Composable
fun KodeStreamingMarkdown(
    text: String,
    modifier: Modifier = Modifier,
) {
    val config = LocalKodeMarkdownConfig.current

    // The streaming state is append-only. If the text ever stops being a
    // continuation of what we already hold — an edit or a revert rather than a
    // delta — the only correct move is to rebuild from scratch.
    var generation by remember { mutableIntStateOf(0) }

    key(generation) {
        val state = rememberStreamingMarkdownState()

        LaunchedEffect(state, text) {
            val current = state.content
            when {
                !text.continues(current) -> generation++
                text.length == current.length -> Unit
                else ->
                    // Not cancellable: `append` mutates the accumulated content
                    // and the parser's tail together, and interleaving a cancel
                    // between them would desynchronise the two.
                    withContext(NonCancellable) {
                        state.append(text.substring(current.length))
                    }
            }
        }

        Markdown(
            streamingMarkdownState = state,
            colors = config.colors,
            typography = config.typography,
            modifier = modifier,
            padding = config.padding,
            dimens = config.dimens,
            imageTransformer = config.imageTransformer,
            annotator = config.annotator,
            extendedSpans = config.extendedSpans,
            inlineContent = config.inlineContent,
            components = config.components,
            animations = config.animations,
        )
    }
}

/**
 * Prefix check that avoids materialising the accumulated [CharSequence] into a
 * String — this runs on every delta, for every streaming message.
 */
private fun String.continues(prefix: CharSequence): Boolean {
    if (prefix.length > length) return false
    for (index in 0 until prefix.length) {
        if (this[index] != prefix[index]) return false
    }
    return true
}

@Composable
private fun RenderMarkdown(state: State, config: KodeMarkdownConfig, modifier: Modifier) {
    Markdown(
        state = state,
        colors = config.colors,
        typography = config.typography,
        modifier = modifier,
        padding = config.padding,
        dimens = config.dimens,
        imageTransformer = config.imageTransformer,
        annotator = config.annotator,
        extendedSpans = config.extendedSpans,
        inlineContent = config.inlineContent,
        components = config.components,
        animations = config.animations,
    )
}
