package com.silverbullet.kode.core.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import com.mikepenz.markdown.compose.LocalImageTransformer
import com.mikepenz.markdown.compose.LocalMarkdownAnimations
import com.mikepenz.markdown.compose.LocalMarkdownAnnotator
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownComponents
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.LocalMarkdownExtendedSpans
import com.mikepenz.markdown.compose.LocalMarkdownInlineContent
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import com.mikepenz.markdown.compose.LocalMarkdownTypography
import com.mikepenz.markdown.compose.LocalReferenceLinkHandler
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.model.ReferenceLinkHandler
import com.mikepenz.markdown.model.State
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode

/**
 * One top-level block of a settled markdown message — a paragraph, code fence,
 * list, table — plus the whitespace trivia that follows it.
 *
 * This is the unit the thread feed renders as a lazy-list item. Rendering a
 * whole message as one item meant that the moment a message scrolled into
 * view, *all* of its blocks built their `AnnotatedString`s and ran text layout
 * in a single frame — the hitch felt whenever a new turn entered the viewport.
 * One item per block bounds that work to the block actually crossing the edge.
 *
 * The nodes reference the message's [content] by offset, so a group is only
 * meaningful together with the exact string it was parsed from. Node instances
 * come from the cached parse, so structural equality between rebuilds holds and
 * rows stay skippable.
 */
@Immutable
data class MarkdownBlockGroup(
    val nodes: List<ASTNode>,
    val content: String,
    val referenceLinkHandler: ReferenceLinkHandler,
) {
    /** Lazy-list content type, so paragraph rows recycle paragraph slots. */
    val contentType: String get() = nodes.first().type.name
}

/**
 * Splits a parsed document into renderable block groups.
 *
 * Every child of the root — EOL and whitespace tokens included — lands in
 * exactly one group, in document order, so rendering the groups in sequence
 * with [KodeMarkdownBlockGroup] reproduces `MarkdownSuccess`'s output down to
 * the spacer each element carries. Trivia rides with the block *before* it
 * (leading trivia joins the first block), because a trailing-newline group of
 * its own would render as a stray spacer row.
 */
fun State.Success.splitIntoBlockGroups(): List<MarkdownBlockGroup> {
    val groups = ArrayList<MutableList<ASTNode>>()
    var openIsTriviaOnly = false

    for (child in node.children) {
        val trivia = child.type == MarkdownTokenTypes.EOL ||
            child.type == MarkdownTokenTypes.WHITE_SPACE
        when {
            trivia -> {
                if (groups.isEmpty()) {
                    groups.add(mutableListOf(child))
                    openIsTriviaOnly = true
                } else {
                    groups.last().add(child)
                }
            }

            openIsTriviaOnly -> {
                groups.last().add(child)
                openIsTriviaOnly = false
            }

            else -> groups.add(mutableListOf(child))
        }
    }

    return groups.map { MarkdownBlockGroup(it, content, referenceLinkHandler) }
}

/**
 * Parses (or reuses the cached parse of) a settled message and returns its
 * block groups, or null when the text failed to parse and the caller should
 * fall back to the monolithic rendering path.
 */
suspend fun MarkdownParseCache.parseBlockGroups(
    key: String,
    markdown: String,
): List<MarkdownBlockGroup>? =
    (parse(key, markdown) as? State.Success)?.splitIntoBlockGroups()

/**
 * Renders one block group of a settled message.
 *
 * Provides exactly the CompositionLocals `Markdown()` would, sourced from the
 * theme-stable [KodeMarkdownConfig], so a block rendered here is
 * pixel-identical to the same block inside a full `Markdown()` column —
 * including the leading block spacer every element carries.
 */
@Composable
fun KodeMarkdownBlockGroup(
    group: MarkdownBlockGroup,
    modifier: Modifier = Modifier,
) {
    val config = LocalKodeMarkdownConfig.current
    CompositionLocalProvider(
        LocalReferenceLinkHandler provides group.referenceLinkHandler,
        LocalMarkdownPadding provides config.padding,
        LocalMarkdownDimens provides config.dimens,
        LocalMarkdownColors provides config.colors,
        LocalMarkdownTypography provides config.typography,
        LocalImageTransformer provides config.imageTransformer,
        LocalMarkdownAnnotator provides config.annotator,
        LocalMarkdownExtendedSpans provides config.extendedSpans,
        LocalMarkdownInlineContent provides config.inlineContent,
        LocalMarkdownComponents provides config.components,
        LocalMarkdownAnimations provides config.animations,
    ) {
        Column(modifier) {
            group.nodes.forEach { node ->
                MarkdownElement(node, config.components, group.content)
            }
        }
    }
}
