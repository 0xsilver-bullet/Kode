package com.silverbullet.kode.feature.threads.domain

import com.silverbullet.kode.core.model.InteractionMode
import com.silverbullet.kode.core.model.MessageRole
import com.silverbullet.kode.core.model.ModelSelection
import com.silverbullet.kode.core.model.OrchestrationEvent
import com.silverbullet.kode.core.model.OrchestrationMessage
import com.silverbullet.kode.core.model.OrchestrationSession
import com.silverbullet.kode.core.model.OrchestrationThread
import com.silverbullet.kode.core.model.OrchestrationThreadActivity
import com.silverbullet.kode.core.model.OrchestrationThreadDetailSnapshot
import com.silverbullet.kode.core.model.ProjectId
import com.silverbullet.kode.core.model.RuntimeMode
import com.silverbullet.kode.core.model.SessionStatus
import com.silverbullet.kode.core.model.ThreadId
import com.silverbullet.kode.core.model.ThreadStreamItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThreadDetailStateTest {

    private val threadId = ThreadId("t1")

    @Test
    fun `a streaming assistant message replaces in place rather than appending`() {
        // This is the single most important behaviour in the timeline. The
        // server re-sends the whole accumulated text on every delta, so
        // appending would render one copy of the reply per token batch.
        var state = ThreadDetailState().reduce(snapshotItem())

        state = state.reduce(messageEvent(sequence = 2, text = "Reading", streaming = true))
        state = state.reduce(messageEvent(sequence = 3, text = "Reading the", streaming = true))
        state = state.reduce(
            messageEvent(sequence = 4, text = "Reading the contracts", streaming = false),
        )

        val assistantMessages = state.messages.filter { it.role == MessageRole.ASSISTANT }
        assertEquals(1, assistantMessages.size)
        assertEquals("Reading the contracts", assistantMessages.single().text)
        assertFalse(assistantMessages.single().streaming)
    }

    @Test
    fun `a streamed message keeps its position among messages`() {
        var state = ThreadDetailState().reduce(snapshotItem())
        state = state.reduce(messageEvent(sequence = 2, text = "a", streaming = true))
        state = state.reduce(
            ThreadStreamItem.Event(
                OrchestrationEvent.MessageSent(
                    sequence = 3,
                    threadId = threadId,
                    message = message(id = "m3", role = MessageRole.USER, text = "follow up"),
                ),
            ),
        )
        state = state.reduce(messageEvent(sequence = 4, text = "ab", streaming = false))

        // The growing assistant message must not jump to the end past the newer
        // user message.
        assertEquals(listOf("m1", "m2", "m3"), state.messages.map { it.id })
    }

    @Test
    fun `a replayed activity is not duplicated`() {
        var state = ThreadDetailState().reduce(snapshotItem())
        val event = activityEvent(sequence = 2, id = "a1")

        state = state.reduce(event)
        state = state.reduce(event)

        assertEquals(1, state.activities.size)
    }

    @Test
    fun `a model change is reflected in the thread`() {
        // Ignoring this event was why a configuration change looked like it had
        // silently failed: the command was accepted, but nothing updated the
        // thread the UI renders from.
        var state = ThreadDetailState().reduce(snapshotItem())
        state = state.reduce(
            ThreadStreamItem.Event(
                OrchestrationEvent.MetaUpdated(
                    sequence = 2,
                    threadId = threadId,
                    modelSelection = ModelSelection("anthropic", "opus"),
                    title = null,
                ),
            ),
        )

        assertEquals(ModelSelection("anthropic", "opus"), state.thread?.modelSelection)
        // A null field in the event must not wipe the existing value.
        assertEquals("Port the RPC layer", state.thread?.title)
    }

    @Test
    fun `runtime and interaction mode changes are reflected`() {
        var state = ThreadDetailState().reduce(snapshotItem())
        state = state.reduce(
            ThreadStreamItem.Event(
                OrchestrationEvent.RuntimeModeSet(2, threadId, RuntimeMode.FULL_ACCESS),
            ),
        )
        state = state.reduce(
            ThreadStreamItem.Event(
                OrchestrationEvent.InteractionModeSet(3, threadId, InteractionMode.PLAN),
            ),
        )

        assertEquals(RuntimeMode.FULL_ACCESS, state.thread?.runtimeMode)
        assertEquals(InteractionMode.PLAN, state.thread?.interactionMode)
    }

    @Test
    fun `session updates drive the working indicator`() {
        var state = ThreadDetailState().reduce(snapshotItem())
        assertFalse(state.isBusy)

        state = state.reduce(sessionEvent(sequence = 2, status = SessionStatus.RUNNING))
        assertTrue(state.isBusy)

        state = state.reduce(sessionEvent(sequence = 3, status = SessionStatus.IDLE))
        assertFalse(state.isBusy)
    }

    @Test
    fun `an unsupported event leaves the state untouched`() {
        val state = ThreadDetailState().reduce(snapshotItem())
        val after = state.reduce(
            ThreadStreamItem.Event(OrchestrationEvent.Unsupported(9, "thread.pin-reordered")),
        )

        assertEquals(state.messages, after.messages)
        assertEquals(state.activities, after.activities)
    }

    @Test
    fun `an unsupported stream item is ignored entirely`() {
        val state = ThreadDetailState().reduce(snapshotItem())
        assertEquals(state, state.reduce(ThreadStreamItem.Unsupported("something-new")))
    }

    @Test
    fun `the synchronized marker promotes the sync status`() {
        val state = ThreadDetailState()
            .reduce(snapshotItem())
            .reduce(ThreadStreamItem.Synchronized)

        assertEquals(SyncStatus.Live, state.status)
    }

    @Test
    fun `a later snapshot replaces the timeline rather than merging`() {
        // Resubscribing after a reconnect must not leave stale entries behind.
        var state = ThreadDetailState().reduce(snapshotItem())
        state = state.reduce(activityEvent(sequence = 2, id = "a1"))

        state = state.reduce(
            ThreadStreamItem.Snapshot(
                OrchestrationThreadDetailSnapshot(
                    snapshotSequence = 10,
                    thread = thread(messages = listOf(message(id = "m1"))),
                ),
            ),
        )

        assertEquals(listOf("m1"), state.messages.map { it.id })
        assertTrue(state.activities.isEmpty())
    }

    // ------------------------------------------------------------------ builders

    private fun snapshotItem() = ThreadStreamItem.Snapshot(
        OrchestrationThreadDetailSnapshot(snapshotSequence = 1, thread = thread()),
    )

    private fun thread(
        messages: List<OrchestrationMessage> = listOf(message(id = "m1")),
    ) = OrchestrationThread(
        id = threadId,
        projectId = ProjectId("p1"),
        title = "Port the RPC layer",
        createdAt = "2026-08-15T09:00:00.000Z",
        updatedAt = "2026-08-15T09:00:00.000Z",
        messages = messages,
    )

    private fun message(
        id: String,
        role: String = MessageRole.USER,
        text: String = "hello",
        createdAt: String = "2026-08-15T10:00:00.000Z",
    ) = OrchestrationMessage(
        id = id,
        role = role,
        text = text,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    private fun messageEvent(
        sequence: Int,
        text: String,
        streaming: Boolean = false,
        createdAt: String = "2026-08-15T10:00:10.000Z",
    ) = ThreadStreamItem.Event(
        OrchestrationEvent.MessageSent(
            sequence = sequence,
            threadId = threadId,
            message = OrchestrationMessage(
                id = "m2",
                role = MessageRole.ASSISTANT,
                text = text,
                streaming = streaming,
                createdAt = createdAt,
                updatedAt = createdAt,
            ),
        ),
    )

    private fun activityEvent(
        sequence: Int,
        id: String,
        createdAt: String = "2026-08-15T10:00:05.000Z",
    ) = ThreadStreamItem.Event(
        OrchestrationEvent.ActivityAppended(
            sequence = sequence,
            threadId = threadId,
            activity = OrchestrationThreadActivity(
                id = id,
                tone = "tool",
                kind = "tool.bash",
                summary = "Ran ./gradlew build",
                createdAt = createdAt,
            ),
        ),
    )

    private fun sessionEvent(sequence: Int, status: String) = ThreadStreamItem.Event(
        OrchestrationEvent.SessionSet(
            sequence = sequence,
            threadId = threadId,
            session = OrchestrationSession(
                threadId = threadId,
                status = status,
                updatedAt = "2026-08-15T10:00:00.000Z",
            ),
        ),
    )
}
