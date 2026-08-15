package com.silverbullet.kode.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * Frames here match the shapes declared in
 * `packages/contracts/src/orchestration.ts`.
 */
class OrchestrationStreamDecoderTest {

    private val decoder = OrchestrationStreamDecoder(
        Json { ignoreUnknownKeys = true; explicitNulls = false },
    )

    private fun decodeShell(raw: String) = decoder.decodeShellItem(Json.parseToJsonElement(raw))
    private fun decodeThread(raw: String) = decoder.decodeThreadItem(Json.parseToJsonElement(raw))

    // ------------------------------------------------------------------- shell

    @Test
    fun `decodes a shell snapshot`() {
        val item = decodeShell(
            """
            {"kind":"snapshot","snapshot":{
              "snapshotSequence":12,
              "updatedAt":"2026-08-15T10:00:00.000Z",
              "projects":[{"id":"p1","title":"Kode","workspaceRoot":"/repo",
                           "createdAt":"2026-08-15T09:00:00.000Z",
                           "updatedAt":"2026-08-15T09:00:00.000Z"}],
              "threads":[{"id":"t1","projectId":"p1","title":"Port the RPC layer",
                          "runtimeMode":"auto","interactionMode":"default",
                          "branch":null,"worktreePath":null,"latestTurn":null,
                          "createdAt":"2026-08-15T09:30:00.000Z",
                          "updatedAt":"2026-08-15T09:45:00.000Z",
                          "session":null,"latestUserMessageAt":null,
                          "hasPendingApprovals":false,"hasPendingUserInput":false,
                          "hasActionableProposedPlan":false}]
            }}
            """.trimIndent(),
        )

        val snapshot = assertIs<ShellStreamItem.Snapshot>(item).snapshot
        assertEquals(12, snapshot.snapshotSequence)
        assertEquals("Kode", snapshot.projects.single().title)
        assertEquals("Port the RPC layer", snapshot.threads.single().title)
        assertEquals(RuntimeMode.AUTO, snapshot.threads.single().runtimeMode)
    }

    @Test
    fun `decodes thread upsert and removal`() {
        val upserted = decodeShell(
            """
            {"kind":"thread-upserted","sequence":13,"thread":{
              "id":"t1","projectId":"p1","title":"Renamed","runtimeMode":"auto",
              "createdAt":"2026-08-15T09:30:00.000Z","updatedAt":"2026-08-15T10:00:00.000Z",
              "session":null,"latestUserMessageAt":null,"latestTurn":null,
              "branch":null,"worktreePath":null,
              "hasPendingApprovals":true,"hasPendingUserInput":false,
              "hasActionableProposedPlan":false}}
            """.trimIndent(),
        )
        val thread = assertIs<ShellStreamItem.ThreadUpserted>(upserted)
        assertEquals(13, thread.sequence)
        assertTrue(thread.thread.needsAttention)

        val removed = decodeShell("""{"kind":"thread-removed","sequence":14,"threadId":"t1"}""")
        assertEquals(ThreadId("t1"), assertIs<ShellStreamItem.ThreadRemoved>(removed).threadId)
    }

    @Test
    fun `an unknown shell kind degrades instead of throwing`() {
        val item = decodeShell("""{"kind":"thread-hibernated","sequence":15}""")
        assertEquals("thread-hibernated", assertIs<ShellStreamItem.Unsupported>(item).kind)
    }

    // ------------------------------------------------------------------ thread

    @Test
    fun `decodes a streaming assistant message as a full-text upsert`() {
        // The server converts each `thread.message.assistant.delta` command into
        // a `thread.message-sent` event carrying the accumulated text, so this
        // is never an append.
        val item = decodeThread(
            """
            {"kind":"event","event":{
              "sequence":42,"eventId":"e1","aggregateKind":"thread","aggregateId":"t1",
              "occurredAt":"2026-08-15T10:00:01.000Z","commandId":null,
              "causationEventId":null,"correlationId":null,"metadata":{},
              "type":"thread.message-sent",
              "payload":{"threadId":"t1","messageId":"m2","role":"assistant",
                         "text":"Reading the contracts","turnId":"turn1","streaming":true,
                         "createdAt":"2026-08-15T10:00:00.000Z",
                         "updatedAt":"2026-08-15T10:00:01.000Z"}}}
            """.trimIndent(),
        )

        val event = assertIs<OrchestrationEvent.MessageSent>(
            assertIs<ThreadStreamItem.Event>(item).event,
        )
        assertEquals(42, event.sequence)
        assertEquals("m2", event.message.id)
        assertEquals(MessageRole.ASSISTANT, event.message.role)
        assertTrue(event.message.streaming)
    }

    @Test
    fun `decodes an appended activity`() {
        val item = decodeThread(
            """
            {"kind":"event","event":{
              "sequence":43,"eventId":"e2","aggregateKind":"thread","aggregateId":"t1",
              "occurredAt":"2026-08-15T10:00:02.000Z","commandId":null,
              "causationEventId":null,"correlationId":null,"metadata":{},
              "type":"thread.activity-appended",
              "payload":{"threadId":"t1","activity":{
                 "id":"a1","tone":"tool","kind":"tool.bash","summary":"Ran ./gradlew build",
                 "payload":{"anything":true},"turnId":"turn1",
                 "createdAt":"2026-08-15T10:00:02.000Z"}}}}
            """.trimIndent(),
        )

        val event = assertIs<OrchestrationEvent.ActivityAppended>(
            assertIs<ThreadStreamItem.Event>(item).event,
        )
        assertEquals("Ran ./gradlew build", event.activity.summary)
        assertEquals(ActivityTone.TOOL, event.activity.tone)
    }

    @Test
    fun `decodes a session update`() {
        val item = decodeThread(
            """
            {"kind":"event","event":{
              "sequence":44,"eventId":"e3","aggregateKind":"thread","aggregateId":"t1",
              "occurredAt":"2026-08-15T10:00:03.000Z","commandId":null,
              "causationEventId":null,"correlationId":null,"metadata":{},
              "type":"thread.session-set",
              "payload":{"threadId":"t1","session":{
                 "threadId":"t1","status":"running","providerName":"Claude",
                 "runtimeMode":"auto","activeTurnId":"turn1","lastError":null,
                 "updatedAt":"2026-08-15T10:00:03.000Z"}}}}
            """.trimIndent(),
        )

        val event = assertIs<OrchestrationEvent.SessionSet>(
            assertIs<ThreadStreamItem.Event>(item).event,
        )
        assertTrue(event.session.isBusy)
    }

    @Test
    fun `an unhandled event type degrades instead of throwing`() {
        // 25 of the 29 contract event types land here by design.
        val item = decodeThread(
            """
            {"kind":"event","event":{
              "sequence":45,"eventId":"e4","aggregateKind":"thread","aggregateId":"t1",
              "occurredAt":"2026-08-15T10:00:04.000Z","commandId":null,
              "causationEventId":null,"correlationId":null,"metadata":{},
              "type":"thread.pin-reordered","payload":{"threadId":"t1"}}}
            """.trimIndent(),
        )

        val event = assertIs<OrchestrationEvent.Unsupported>(
            assertIs<ThreadStreamItem.Event>(item).event,
        )
        assertEquals("thread.pin-reordered", event.type)
        assertEquals(45, event.sequence)
    }

    @Test
    fun `decodes the synchronized marker`() {
        assertEquals(ShellStreamItem.Synchronized, decodeShell("""{"kind":"synchronized"}"""))
        assertEquals(ThreadStreamItem.Synchronized, decodeThread("""{"kind":"synchronized"}"""))
    }

    @Test
    fun `a malformed known payload still fails loudly`() {
        // Tolerating unknown discriminators must not extend to silently
        // swallowing a payload we claim to understand.
        assertFailsWith<OrchestrationDecodeException> {
            decodeThread(
                """{"kind":"event","event":{"sequence":46,"type":"thread.session-set",
                   "payload":{"threadId":"t1"}}}""",
            )
        }
    }
}
