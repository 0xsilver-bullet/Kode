package com.silverbullet.kode.feature.threads.domain

import com.silverbullet.kode.core.model.OrchestrationProjectShell
import com.silverbullet.kode.core.model.OrchestrationShellSnapshot
import com.silverbullet.kode.core.model.OrchestrationThreadShell
import com.silverbullet.kode.core.model.ProjectId
import com.silverbullet.kode.core.model.ShellStreamItem
import com.silverbullet.kode.core.model.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShellStateTest {

    @Test
    fun `a snapshot replaces rather than merges`() {
        // Entries deleted while we were disconnected must not survive a
        // reconnect's fresh snapshot.
        var state = ShellState().reduce(
            ShellStreamItem.Snapshot(snapshot(threads = listOf(thread("t1"), thread("t2")))),
        )
        assertEquals(2, state.threads.size)

        state = state.reduce(ShellStreamItem.Snapshot(snapshot(threads = listOf(thread("t2")))))

        assertEquals(setOf(ThreadId("t2")), state.threads.keys)
    }

    @Test
    fun `an upsert replaces the whole thread by id`() {
        var state = ShellState().reduce(
            ShellStreamItem.Snapshot(snapshot(threads = listOf(thread("t1", title = "Old")))),
        )

        state = state.reduce(
            ShellStreamItem.ThreadUpserted(sequence = 2, thread = thread("t1", title = "New")),
        )

        assertEquals(1, state.threads.size)
        assertEquals("New", state.threads.getValue(ThreadId("t1")).title)
    }

    @Test
    fun `a removal drops the thread`() {
        var state = ShellState().reduce(
            ShellStreamItem.Snapshot(snapshot(threads = listOf(thread("t1")))),
        )
        state = state.reduce(ShellStreamItem.ThreadRemoved(sequence = 2, threadId = ThreadId("t1")))

        assertTrue(state.threads.isEmpty())
    }

    @Test
    fun `archived threads are hidden from the list`() {
        val state = ShellState().reduce(
            ShellStreamItem.Snapshot(
                snapshot(
                    threads = listOf(
                        thread("t1"),
                        thread("t2", archivedAt = "2026-08-15T09:00:00.000Z"),
                    ),
                ),
            ),
        )

        assertEquals(listOf(ThreadId("t1")), state.visibleThreads.map { it.id })
    }

    @Test
    fun `threads sort by latest user message and fall back to updatedAt`() {
        val state = ShellState().reduce(
            ShellStreamItem.Snapshot(
                snapshot(
                    threads = listOf(
                        thread("old", updatedAt = "2026-08-15T09:00:00.000Z"),
                        thread(
                            "newest",
                            updatedAt = "2026-08-15T09:30:00.000Z",
                            latestUserMessageAt = "2026-08-15T11:00:00.000Z",
                        ),
                        thread("middle", updatedAt = "2026-08-15T10:00:00.000Z"),
                    ),
                ),
            ),
        )

        assertEquals(
            listOf(ThreadId("newest"), ThreadId("middle"), ThreadId("old")),
            state.visibleThreads.map { it.id },
        )
    }

    @Test
    fun `sync status advances from synchronizing to live`() {
        var state = ShellState().reduce(ShellStreamItem.Snapshot(snapshot()))
        assertEquals(SyncStatus.Synchronizing, state.status)

        state = state.reduce(ShellStreamItem.Synchronized)
        assertEquals(SyncStatus.Live, state.status)
    }

    @Test
    fun `an unsupported item is ignored`() {
        val state = ShellState().reduce(ShellStreamItem.Snapshot(snapshot()))
        assertEquals(state, state.reduce(ShellStreamItem.Unsupported("thread-hibernated")))
    }

    @Test
    fun `a thread resolves its project`() {
        val state = ShellState().reduce(
            ShellStreamItem.Snapshot(
                snapshot(projects = listOf(project("p1")), threads = listOf(thread("t1"))),
            ),
        )

        val row = state.visibleThreads.single()
        assertEquals("Kode", state.projectFor(row)?.title)
    }

    @Test
    fun `a thread with no known project resolves to null`() {
        // Ordering between project and thread events is not guaranteed, so the
        // UI must tolerate a thread arriving first.
        val state = ShellState().reduce(
            ShellStreamItem.Snapshot(snapshot(projects = emptyList(), threads = listOf(thread("t1")))),
        )

        assertNull(state.projectFor(state.visibleThreads.single()))
    }

    // ------------------------------------------------------------------ builders

    private fun snapshot(
        projects: List<OrchestrationProjectShell> = listOf(project("p1")),
        threads: List<OrchestrationThreadShell> = emptyList(),
    ) = OrchestrationShellSnapshot(
        snapshotSequence = 1,
        projects = projects,
        threads = threads,
        updatedAt = "2026-08-15T10:00:00.000Z",
    )

    private fun project(id: String) = OrchestrationProjectShell(
        id = ProjectId(id),
        title = "Kode",
        workspaceRoot = "/repo",
        createdAt = "2026-08-15T09:00:00.000Z",
        updatedAt = "2026-08-15T09:00:00.000Z",
    )

    private fun thread(
        id: String,
        title: String = "Thread $id",
        updatedAt: String = "2026-08-15T10:00:00.000Z",
        latestUserMessageAt: String? = null,
        archivedAt: String? = null,
    ) = OrchestrationThreadShell(
        id = ThreadId(id),
        projectId = ProjectId("p1"),
        title = title,
        createdAt = "2026-08-15T09:00:00.000Z",
        updatedAt = updatedAt,
        latestUserMessageAt = latestUserMessageAt,
        archivedAt = archivedAt,
    )
}
