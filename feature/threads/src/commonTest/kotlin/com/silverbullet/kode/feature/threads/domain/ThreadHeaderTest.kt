package com.silverbullet.kode.feature.threads.domain

import com.silverbullet.kode.core.model.OrchestrationProjectShell
import com.silverbullet.kode.core.model.OrchestrationThread
import com.silverbullet.kode.core.model.OrchestrationThreadShell
import com.silverbullet.kode.core.model.ProjectId
import com.silverbullet.kode.core.model.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Pins what the thread screen's app bar shows, against T3 Code's mobile header. */
class ThreadHeaderTest {

    @Test
    fun `prefers the detail snapshot's title`() {
        val header = buildThreadHeader(
            thread = thread(title = "Port the RPC layer"),
            shellThread = shellThread(title = "stale title"),
            projects = projects,
            environmentLabel = "MacBook",
            multiEnvironment = false,
        )

        assertEquals("Port the RPC layer", header.title)
    }

    @Test
    fun `falls back to the shell projection before the snapshot lands`() {
        val header = buildThreadHeader(
            thread = null,
            shellThread = shellThread(title = "Port the RPC layer"),
            projects = projects,
            environmentLabel = "MacBook",
            multiEnvironment = false,
        )

        assertEquals("Port the RPC layer", header.title)
        assertEquals("Kode · main", header.subtitle)
    }

    @Test
    fun `names the environment only when more than one is paired`() {
        val single = buildThreadHeader(
            thread = thread(),
            shellThread = null,
            projects = projects,
            environmentLabel = "MacBook",
            multiEnvironment = false,
        )
        val many = buildThreadHeader(
            thread = thread(),
            shellThread = null,
            projects = projects,
            environmentLabel = "MacBook",
            multiEnvironment = true,
        )

        assertEquals("Kode · main", single.subtitle)
        assertEquals("Kode · main · MacBook", many.subtitle)
    }

    @Test
    fun `drops the subtitle when nothing identifies the thread`() {
        val header = buildThreadHeader(
            thread = thread(branch = null),
            shellThread = null,
            projects = emptyMap(),
            environmentLabel = null,
            multiEnvironment = false,
        )

        assertNull(header.subtitle)
    }

    @Test
    fun `falls back to a placeholder while both projections are missing`() {
        val header = buildThreadHeader(
            thread = null,
            shellThread = null,
            projects = projects,
            environmentLabel = "MacBook",
            multiEnvironment = false,
        )

        assertEquals("Thread", header.title)
    }

    private val projects = mapOf(
        ProjectId("p1") to OrchestrationProjectShell(
            id = ProjectId("p1"),
            title = "Kode",
            workspaceRoot = "/Users/aly/projects/Kode",
            createdAt = TIMESTAMP,
            updatedAt = TIMESTAMP,
        ),
    )

    private fun thread(
        title: String = "Port the RPC layer",
        branch: String? = "main",
    ) = OrchestrationThread(
        id = ThreadId("t1"),
        projectId = ProjectId("p1"),
        title = title,
        branch = branch,
        createdAt = TIMESTAMP,
        updatedAt = TIMESTAMP,
    )

    private fun shellThread(
        title: String = "Port the RPC layer",
        branch: String? = "main",
    ) = OrchestrationThreadShell(
        id = ThreadId("t1"),
        projectId = ProjectId("p1"),
        title = title,
        branch = branch,
        createdAt = TIMESTAMP,
        updatedAt = TIMESTAMP,
    )

    private companion object {
        const val TIMESTAMP = "2026-08-15T09:00:00.000Z"
    }
}
