package com.silverbullet.kode.feature.threads.domain

import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.parseMarkdown
import com.silverbullet.kode.core.designsystem.MarkdownBlockGroup
import com.silverbullet.kode.core.designsystem.splitIntoBlockGroups
import com.silverbullet.kode.core.model.ChatAttachment
import com.silverbullet.kode.core.model.MessageRole
import com.silverbullet.kode.core.model.OrchestrationMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.intellij.markdown.MarkdownTokenTypes

/**
 * The block split is what keeps scrolling smooth at turn boundaries: a settled
 * assistant message becomes one lazy row per top-level markdown block, so the
 * list never lays out a whole message in the frame its first pixel appears.
 */
class ThreadFeedSplitTest {

    @Test
    fun `a settled assistant message explodes into one entry per block`() = runTest {
        val entries = splitSettledAssistantMessages(
            entries = listOf(entry(assistant("m1", "First paragraph.\n\nSecond paragraph."))),
            parse = ::realParse,
        )

        assertEquals(listOf("message:m1:block:0", "message:m1:block:1"), entries.map { it.id })
        assertTrue(entries.all { it is FeedEntry.MessageBlock })
    }

    @Test
    fun `every root node lands in exactly one group in document order`() {
        val parsed = assertIs<State.Success>(
            parseMarkdown("# Title\n\nBody text.\n\n- one\n- two\n"),
        )

        val groups = parsed.splitIntoBlockGroups()

        // Reassembling the groups must reproduce the exact child sequence
        // `MarkdownSuccess` would have rendered — trivia included — because
        // spacing comes from per-element spacers, not from the list.
        val reassembled = groups.flatMap { it.nodes }
        assertEquals(parsed.node.children.size, reassembled.size)
        parsed.node.children.forEachIndexed { index, node ->
            assertSame(node, reassembled[index])
        }
        // And no group may be trivia-only: it would render as a stray spacer row.
        assertTrue(
            groups.none { group ->
                group.nodes.all {
                    it.type == MarkdownTokenTypes.EOL || it.type == MarkdownTokenTypes.WHITE_SPACE
                }
            },
        )
    }

    @Test
    fun `leading trivia joins the first block`() {
        val parsed = assertIs<State.Success>(parseMarkdown("\n\nHello."))
        val groups = parsed.splitIntoBlockGroups()
        assertEquals(1, groups.size)
        assertEquals(parsed.node.children.size, groups.single().nodes.size)
    }

    @Test
    fun `streaming and user messages are left whole`() = runTest {
        val streaming = entry(assistant("m1", "partial reply", streaming = true))
        val user = entry(
            OrchestrationMessage(
                id = "m2",
                role = MessageRole.USER,
                text = "a\n\nlong\n\nprompt",
                createdAt = "T10:00:00",
                updatedAt = "T10:00:00",
            ),
        )

        val entries = splitSettledAssistantMessages(listOf(streaming, user), ::realParse)

        assertEquals(listOf(streaming, user), entries)
    }

    @Test
    fun `a failed parse keeps the monolithic entry`() = runTest {
        val original = entry(assistant("m1", "some text"))

        val entries = splitSettledAssistantMessages(listOf(original)) { _, _ -> null }

        assertEquals(listOf<FeedEntry>(original), entries)
    }

    @Test
    fun `attachments follow the last block`() = runTest {
        val message = assistant("m1", "Here is the screenshot.").copy(
            attachments = listOf(
                ChatAttachment(
                    id = "att1",
                    name = "shot.png",
                    mimeType = "image/png",
                    sizeBytes = 1024,
                ),
            ),
        )

        val entries = splitSettledAssistantMessages(listOf(entry(message)), ::realParse)

        assertEquals(listOf("message:m1:block:0", "message:m1:attachments"), entries.map { it.id })
        val attachments = assertIs<FeedEntry.MessageAttachments>(entries.last())
        assertEquals("att1", attachments.attachments.single().id)
    }

    @Test
    fun `non-message entries pass through untouched`() = runTest {
        val fold = FeedEntry.TurnFold(
            turnId = "turn1",
            hiddenCount = 3,
            interrupted = false,
            createdAt = "T10:00:00",
        )

        val entries = splitSettledAssistantMessages(
            listOf(fold, entry(assistant("m1", "Done."))),
            ::realParse,
        )

        assertEquals(listOf("turn-fold:turn1", "message:m1:block:0"), entries.map { it.id })
    }

    private fun realParse(
        @Suppress("UNUSED_PARAMETER") key: String,
        text: String,
    ): List<MarkdownBlockGroup>? =
        (parseMarkdown(text) as? State.Success)?.splitIntoBlockGroups()

    private fun entry(message: OrchestrationMessage) = FeedEntry.Message(message)

    private fun assistant(id: String, text: String, streaming: Boolean = false) =
        OrchestrationMessage(
            id = id,
            role = MessageRole.ASSISTANT,
            text = text,
            turnId = "turn1",
            streaming = streaming,
            createdAt = "T10:00:00",
            updatedAt = "T10:00:00",
        )
}
