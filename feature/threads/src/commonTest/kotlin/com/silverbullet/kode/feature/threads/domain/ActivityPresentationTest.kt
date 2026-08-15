package com.silverbullet.kode.feature.threads.domain

import com.silverbullet.kode.core.model.ActivityPayload
import com.silverbullet.kode.core.model.ActivityPayloadData
import com.silverbullet.kode.core.model.ActivityPayloadItem
import com.silverbullet.kode.core.model.ActivityTone
import com.silverbullet.kode.core.model.ApprovalRequestKind
import com.silverbullet.kode.core.model.OrchestrationThreadActivity
import com.silverbullet.kode.core.model.ToolItemType
import com.silverbullet.kode.core.model.ToolLifecycleStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behaviour ported from `workEntryIcon`, `workEntryStatus`,
 * `workLogEntryIsToolLike` and `deriveWorkLogEntries` in
 * `apps/mobile/src/lib/threadActivity.ts`.
 */
class ActivityPresentationTest {

    @Test
    fun `lifecycle bookends and noise are dropped`() {
        // Keeping these would render two rows per tool call plus checkpoint
        // chatter the user never asked for.
        listOf(
            "tool.started",
            "task.started",
            "task.updated",
            "tool.progress",
            "context-window.updated",
        ).forEach { kind ->
            assertFalse(activity(kind = kind).isRenderable(), kind)
        }

        assertFalse(activity(summary = "Checkpoint captured").isRenderable())
        assertTrue(activity(kind = "tool.completed").isRenderable())
    }

    @Test
    fun `plan mode boundaries are dropped`() {
        val activity = activity(
            kind = "tool.completed",
            payload = ActivityPayload(detail = "ExitPlanMode: done"),
        )
        assertFalse(activity.isRenderable())
    }

    @Test
    fun `approval request kinds pick their icon`() {
        assertEquals(
            ActivityIcon.Command,
            activity(payload = ActivityPayload(requestKind = ApprovalRequestKind.COMMAND))
                .toPresentation().icon,
        )
        assertEquals(
            ActivityIcon.Eye,
            activity(payload = ActivityPayload(requestKind = ApprovalRequestKind.FILE_READ))
                .toPresentation().icon,
        )
        assertEquals(
            ActivityIcon.Edit,
            activity(payload = ActivityPayload(requestKind = ApprovalRequestKind.FILE_CHANGE))
                .toPresentation().icon,
        )
    }

    @Test
    fun `tool item types pick their icon`() {
        fun iconFor(itemType: String) =
            activity(payload = ActivityPayload(itemType = itemType)).toPresentation().icon

        assertEquals(ActivityIcon.Command, iconFor(ToolItemType.COMMAND_EXECUTION))
        assertEquals(ActivityIcon.Edit, iconFor(ToolItemType.FILE_CHANGE))
        assertEquals(ActivityIcon.Globe, iconFor(ToolItemType.WEB_SEARCH))
        assertEquals(ActivityIcon.Eye, iconFor(ToolItemType.IMAGE_VIEW))
        assertEquals(ActivityIcon.Wrench, iconFor(ToolItemType.MCP_TOOL_CALL))
        assertEquals(ActivityIcon.Hammer, iconFor(ToolItemType.DYNAMIC_TOOL_CALL))
    }

    @Test
    fun `tone drives the icon when nothing more specific applies`() {
        assertEquals(ActivityIcon.Alert, activity(tone = ActivityTone.ERROR).toPresentation().icon)
        assertEquals(ActivityIcon.Check, activity(tone = ActivityTone.INFO).toPresentation().icon)
        assertEquals(ActivityIcon.Zap, activity(tone = ActivityTone.TOOL).toPresentation().icon)
        // Subagent progress reads as "thinking" regardless of the wire tone.
        assertEquals(
            ActivityIcon.Agent,
            activity(kind = "task.progress", tone = ActivityTone.TOOL).toPresentation().icon,
        )
    }

    @Test
    fun `runtime warnings and user input win over tone`() {
        assertEquals(
            ActivityIcon.Warning,
            activity(kind = "runtime.warning", tone = ActivityTone.ERROR).toPresentation().icon,
        )
        assertEquals(
            ActivityIcon.Message,
            activity(kind = "user-input.requested").toPresentation().icon,
        )
    }

    @Test
    fun `a completed tool call reads as success`() {
        val presentation = activity(
            tone = ActivityTone.TOOL,
            payload = ActivityPayload(
                itemType = ToolItemType.COMMAND_EXECUTION,
                status = ToolLifecycleStatus.COMPLETED,
            ),
        ).toPresentation()

        assertEquals(ActivityStatus.Success, presentation.status)
    }

    @Test
    fun `failure is sniffed from output when no status is reported`() {
        // Providers do not report a status for every shell failure, so a
        // non-zero exit in the text must not render as a success tick.
        val presentation = activity(
            tone = ActivityTone.TOOL,
            payload = ActivityPayload(
                itemType = ToolItemType.COMMAND_EXECUTION,
                status = ToolLifecycleStatus.COMPLETED,
                detail = "bash: frobnicate: command not found",
            ),
        ).toPresentation()

        assertEquals(ActivityStatus.Failure, presentation.status)
    }

    @Test
    fun `a non-zero exit code reads as failure`() {
        val presentation = activity(
            tone = ActivityTone.TOOL,
            payload = ActivityPayload(
                itemType = ToolItemType.COMMAND_EXECUTION,
                detail = "<exited with exit code 2>",
            ),
        ).toPresentation()

        assertEquals(ActivityStatus.Failure, presentation.status)
    }

    @Test
    fun `exit code zero is not a failure`() {
        val presentation = activity(
            tone = ActivityTone.TOOL,
            payload = ActivityPayload(
                itemType = ToolItemType.COMMAND_EXECUTION,
                status = ToolLifecycleStatus.COMPLETED,
                detail = "<exited with exit code 0>",
            ),
        ).toPresentation()

        assertEquals(ActivityStatus.Success, presentation.status)
    }

    @Test
    fun `non-tool activities carry no status`() {
        assertNull(activity(tone = ActivityTone.INFO).toPresentation().status)
    }

    @Test
    fun `the command is pulled out of the nested payload`() {
        val presentation = activity(
            payload = ActivityPayload(
                itemType = ToolItemType.COMMAND_EXECUTION,
                data = ActivityPayloadData(
                    item = ActivityPayloadItem(command = "./gradlew assemble"),
                ),
            ),
        ).toPresentation()

        assertEquals("./gradlew assemble", presentation.preview)
        assertEquals(ActivityIcon.Command, presentation.icon)
    }

    @Test
    fun `the expanded body combines command and detail and changed files`() {
        val presentation = activity(
            payload = ActivityPayload(
                detail = "2 files changed",
                data = ActivityPayloadData(
                    command = "git commit",
                    changedFiles = listOf("a.kt", "b.kt"),
                ),
            ),
        ).toPresentation()

        assertTrue(presentation.canExpand)
        assertEquals("git commit\n\n2 files changed\n\na.kt\nb.kt", presentation.expandedDetail)
    }

    @Test
    fun `a payload title overrides the generic summary`() {
        val presentation = activity(
            summary = "Tool completed",
            payload = ActivityPayload(title = "Read src/main.kt"),
        ).toPresentation()

        assertEquals("Read src/main.kt", presentation.summary)
    }

    @Test
    fun `a preview identical to the summary is not repeated`() {
        val presentation = activity(
            summary = "npm test",
            payload = ActivityPayload(data = ActivityPayloadData(command = "npm test")),
        ).toPresentation()

        assertNull(presentation.preview)
    }

    private fun activity(
        kind: String = "tool.completed",
        tone: String = ActivityTone.TOOL,
        summary: String = "Did a thing",
        payload: ActivityPayload? = null,
    ) = OrchestrationThreadActivity(
        id = "a1",
        tone = tone,
        kind = kind,
        summary = summary,
        createdAt = "2026-08-15T10:00:00.000Z",
        payload = payload,
    )
}
