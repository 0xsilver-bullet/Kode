package com.silverbullet.kode.feature.threads.domain

import com.silverbullet.kode.core.model.ActivityPayload
import com.silverbullet.kode.core.model.ActivityTone
import com.silverbullet.kode.core.model.MessageRole
import com.silverbullet.kode.core.model.OrchestrationLatestTurn
import com.silverbullet.kode.core.model.OrchestrationMessage
import com.silverbullet.kode.core.model.OrchestrationThreadActivity
import com.silverbullet.kode.core.model.ToolItemType
import com.silverbullet.kode.core.model.ToolLifecycleStatus
import com.silverbullet.kode.core.model.TurnState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The feed pipeline is what keeps a long thread cheap to render: without folds
 * and collapsing, a 40-turn thread is hundreds of rows and every assistant row
 * carries a markdown parse. Behaviour ported from
 * `deriveThreadFeedPresentation` in `apps/mobile/src/lib/threadActivity.ts`.
 */
class ThreadFeedTest {

    @Test
    fun `a settled turn folds to one row plus its final assistant message`() {
        val feed = buildFeed(
            messages = listOf(
                user("m1", "turn1", "T10:00:00"),
                assistant("m2", "turn1", "thinking", "T10:00:05"),
                assistant("m3", "turn1", "done", "T10:00:20"),
            ),
            activities = listOf(
                tool("a1", "turn1", "T10:00:10"),
                tool("a2", "turn1", "T10:00:15"),
            ),
            latestTurn = null,
            expansion = FeedExpansion(),
        )

        // The user prompt, intermediate reply and both tool rows collapse into
        // a single fold; only the closing assistant message survives.
        assertEquals(listOf("turn-fold:turn1", "message:m3"), feed.map { it.id })
        assertEquals(4, assertIs<FeedEntry.TurnFold>(feed.first()).hiddenCount)
    }

    @Test
    fun `expanding a turn restores its rows`() {
        val messages = listOf(user("m1", "turn1", "T10:00:00"), assistant("m2", "turn1", "done", "T10:00:20"))
        val activities = listOf(tool("a1", "turn1", "T10:00:10"))

        val expanded = buildFeed(
            messages = messages,
            activities = activities,
            latestTurn = null,
            expansion = FeedExpansion(turns = setOf("turn1")),
        )

        assertEquals(listOf("message:m1", "activity:a1", "message:m2"), expanded.map { it.id })
    }

    @Test
    fun `the running turn is never folded`() {
        val feed = buildFeed(
            messages = listOf(user("m1", "turn1", "T10:00:00")),
            activities = listOf(tool("a1", "turn1", "T10:00:10")),
            latestTurn = OrchestrationLatestTurn(
                turnId = "turn1",
                state = TurnState.RUNNING,
                requestedAt = "T10:00:00",
            ),
            expansion = FeedExpansion(),
        )

        assertTrue(feed.none { it is FeedEntry.TurnFold })
        assertEquals(listOf("message:m1", "activity:a1"), feed.map { it.id })
    }

    @Test
    fun `a turn that is still streaming is never folded`() {
        val feed = buildFeed(
            messages = listOf(
                user("m1", "turn1", "T10:00:00"),
                assistant("m2", "turn1", "partial", "T10:00:05", streaming = true),
            ),
            activities = emptyList(),
            latestTurn = null,
            expansion = FeedExpansion(),
        )

        assertTrue(feed.none { it is FeedEntry.TurnFold })
    }

    @Test
    fun `a run of tool calls collapses to the newest plus a toggle`() {
        val feed = buildFeed(
            messages = listOf(user("m1", "turn1", "T10:00:00")),
            activities = listOf(
                tool("a1", "turn1", "T10:00:01", summary = "one"),
                tool("a2", "turn1", "T10:00:02", summary = "two"),
                tool("a3", "turn1", "T10:00:03", summary = "three"),
            ),
            latestTurn = runningTurn(),
            expansion = FeedExpansion(),
        )

        // Only the most recent row shows; the rest sit behind the toggle.
        assertEquals(listOf("message:m1", "activity:a3", "work-toggle:a1"), feed.map { it.id })
        val toggle = assertIs<FeedEntry.WorkToggle>(feed.last())
        assertEquals(2, toggle.hiddenCount)
        assertTrue(toggle.onlyToolActivities)
    }

    @Test
    fun `expanding a work group shows every row`() {
        val feed = buildFeed(
            messages = emptyList(),
            activities = listOf(
                tool("a1", "turn1", "T10:00:01"),
                tool("a2", "turn1", "T10:00:02"),
            ),
            latestTurn = runningTurn(),
            expansion = FeedExpansion(workGroups = setOf("a1")),
        )

        assertEquals(listOf("activity:a1", "activity:a2", "work-toggle:a1"), feed.map { it.id })
        assertTrue(assertIs<FeedEntry.WorkToggle>(feed.last()).expanded)
    }

    @Test
    fun `tool rows with no signal are dropped entirely`() {
        // Neutral rows are the bulk of a long run and carry nothing actionable.
        val feed = buildFeed(
            messages = emptyList(),
            activities = listOf(
                tool("a1", "turn1", "T10:00:01", status = null),
                tool("a2", "turn1", "T10:00:02", status = null),
            ),
            latestTurn = runningTurn(),
            expansion = FeedExpansion(),
        )

        assertTrue(feed.isEmpty())
    }

    @Test
    fun `a message breaks a run into separate groups`() {
        val feed = buildFeed(
            messages = listOf(assistant("m1", "turn1", "note", "T10:00:02")),
            activities = listOf(
                tool("a1", "turn1", "T10:00:01"),
                tool("a2", "turn1", "T10:00:03"),
            ),
            latestTurn = runningTurn(),
            expansion = FeedExpansion(),
        )

        assertEquals(listOf("activity:a1", "message:m1", "activity:a2"), feed.map { it.id })
    }

    @Test
    fun `adjacent lifecycle rows for the same call collapse into one`() {
        val updated = activity("a1", "turn1", "T10:00:01", kind = "tool.updated")
        val completed = activity("a2", "turn1", "T10:00:02", kind = "tool.updated")

        val collapsed = collapseWorkLog(listOf(updated, completed).map { it.toPresentation() })

        assertEquals(1, collapsed.size)
        assertEquals("a2", collapsed.single().id)
    }

    @Test
    fun `a terminal row does not absorb the next one`() {
        val done = activity("a1", "turn1", "T10:00:01", kind = "tool.completed")
        val next = activity("a2", "turn1", "T10:00:02", kind = "tool.updated")

        val collapsed = collapseWorkLog(listOf(done, next).map { it.toPresentation() })

        assertEquals(listOf("a1", "a2"), collapsed.map { it.id })
    }

    @Test
    fun `subagent rows collapse by identity even when far apart`() {
        val first = activity("a1", "turn1", "T10:00:01", kind = "task.progress", taskId = "task-1")
        val other = activity("a2", "turn1", "T10:00:02", kind = "tool.completed")
        val later = activity("a3", "turn1", "T10:00:03", kind = "task.progress", taskId = "task-1")

        val collapsed = collapseWorkLog(listOf(first, other, later).map { it.toPresentation() })

        // One row per subagent, holding the newest content, in its original slot.
        assertEquals(listOf("a3", "a2"), collapsed.map { it.id })
    }

    // ------------------------------------------------------------------ builders

    private fun runningTurn() = OrchestrationLatestTurn(
        turnId = "turn1",
        state = TurnState.RUNNING,
        requestedAt = "T10:00:00",
    )

    private fun user(id: String, turnId: String, createdAt: String) =
        message(id, MessageRole.USER, "hello", turnId, createdAt, false)

    private fun assistant(
        id: String,
        turnId: String,
        text: String,
        createdAt: String,
        streaming: Boolean = false,
    ) = message(id, MessageRole.ASSISTANT, text, turnId, createdAt, streaming)

    private fun message(
        id: String,
        role: String,
        text: String,
        turnId: String,
        createdAt: String,
        streaming: Boolean,
    ) = OrchestrationMessage(
        id = id,
        role = role,
        text = text,
        turnId = turnId,
        streaming = streaming,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    private fun tool(
        id: String,
        turnId: String,
        createdAt: String,
        summary: String = "Ran a command",
        status: String? = ToolLifecycleStatus.COMPLETED,
    ) = activity(id, turnId, createdAt, summary = summary, status = status).toPresentation()

    private fun activity(
        id: String,
        turnId: String,
        createdAt: String,
        kind: String = "tool.completed",
        summary: String = "Ran a command",
        status: String? = ToolLifecycleStatus.COMPLETED,
        taskId: String? = null,
    ) = OrchestrationThreadActivity(
        id = id,
        tone = ActivityTone.TOOL,
        kind = kind,
        summary = summary,
        turnId = turnId,
        createdAt = createdAt,
        payload = ActivityPayload(
            itemType = ToolItemType.COMMAND_EXECUTION,
            status = status,
            taskId = taskId,
        ),
    )
}
