package com.silverbullet.kode.feature.threads.domain

import com.silverbullet.kode.core.model.OrchestrationLatestTurn
import com.silverbullet.kode.core.model.OrchestrationSession
import com.silverbullet.kode.core.model.OrchestrationThreadShell
import com.silverbullet.kode.core.model.ProjectId
import com.silverbullet.kode.core.model.SessionStatus
import com.silverbullet.kode.core.model.ThreadId
import com.silverbullet.kode.core.model.TurnState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Behaviour ported from `resolveThreadListV2Status` in
 * `apps/mobile/src/features/threads/threadListV2.ts` and `relativeTime` in
 * `apps/mobile/src/lib/time.ts`.
 */
class ThreadRowPresentationTest {

    @Test
    fun `a pending approval outranks everything else`() {
        val thread = thread(
            hasPendingApprovals = true,
            hasPendingUserInput = true,
            status = SessionStatus.RUNNING,
        )
        assertEquals(ThreadRowStatus.Approval, thread.rowStatus())
    }

    @Test
    fun `a thread blocked on the user outranks one merely running`() {
        val thread = thread(hasPendingUserInput = true, status = SessionStatus.RUNNING)
        assertEquals(ThreadRowStatus.Input, thread.rowStatus())
    }

    @Test
    fun `a starting session already reads as working`() {
        assertEquals(ThreadRowStatus.Working, thread(status = SessionStatus.STARTING).rowStatus())
        assertEquals(ThreadRowStatus.Working, thread(status = SessionStatus.RUNNING).rowStatus())
    }

    @Test
    fun `an errored session reads as failed`() {
        assertEquals(ThreadRowStatus.Failed, thread(status = SessionStatus.ERROR).rowStatus())
    }

    @Test
    fun `background work outliving its turn still reads as working`() {
        val thread = thread(status = SessionStatus.IDLE).copy(backgroundLiveness = "live")
        assertEquals(ThreadRowStatus.Working, thread.rowStatus())
    }

    @Test
    fun `a running turn reads as working even while the session is quiet`() {
        val thread = thread(status = SessionStatus.IDLE).copy(
            latestTurn = OrchestrationLatestTurn(
                turnId = "turn-1",
                state = TurnState.RUNNING,
                requestedAt = TIMESTAMP,
            ),
        )
        assertEquals(ThreadRowStatus.Working, thread.rowStatus())
    }

    @Test
    fun `a broken session outranks live background work`() {
        val thread = thread(status = SessionStatus.ERROR).copy(backgroundLiveness = "live")
        assertEquals(ThreadRowStatus.Failed, thread.rowStatus())
    }

    @Test
    fun `a quiet thread has no state worth labelling`() {
        assertEquals(ThreadRowStatus.Ready, thread().rowStatus())
        assertEquals(ThreadRowStatus.Ready, thread(status = SessionStatus.IDLE).rowStatus())
        assertEquals(ThreadRowStatus.Ready, thread(status = null).rowStatus())
    }

    @Test
    fun `ages read at the coarsest useful granularity`() {
        assertEquals("<1m", label(secondsAgo = 0))
        assertEquals("<1m", label(secondsAgo = 59))
        assertEquals("1m", label(secondsAgo = 60))
        assertEquals("59m", label(secondsAgo = 59 * 60))
        assertEquals("1h", label(secondsAgo = 60 * 60))
        assertEquals("23h", label(secondsAgo = 23 * 3_600))
        assertEquals("1d", label(secondsAgo = 24 * 3_600))
        assertEquals("13d", label(secondsAgo = 13 * 24 * 3_600))
    }

    @Test
    fun `a clock running behind the timestamp does not report a negative age`() {
        assertEquals("<1m", label(secondsAgo = -3_600))
    }

    @Test
    fun `an unusable timestamp reads as the youngest age rather than blank`() {
        assertEquals("<1m", relativeTimeLabel(null, NOW_MILLIS))
        assertEquals("<1m", relativeTimeLabel("not a timestamp", NOW_MILLIS))
    }

    @Test
    fun `a row dates itself by the last user message, not by bookkeeping`() {
        val thread = thread().copy(
            latestUserMessageAt = "2026-08-16T09:00:00.000Z",
            updatedAt = "2026-08-16T11:59:00.000Z",
        )
        assertEquals("2026-08-16T09:00:00.000Z", thread.rowTimestamp())
    }

    @Test
    fun `a thread the user has never messaged falls back to when it changed`() {
        assertEquals(TIMESTAMP, thread().rowTimestamp())
    }

    private fun label(secondsAgo: Int): String =
        relativeTimeLabel(TIMESTAMP, NOW_MILLIS + secondsAgo * 1_000L)

    private fun thread(
        hasPendingApprovals: Boolean = false,
        hasPendingUserInput: Boolean = false,
        status: String? = SessionStatus.READY,
    ) = OrchestrationThreadShell(
        id = ThreadId("t1"),
        projectId = ProjectId("p1"),
        title = "Port the RPC layer",
        createdAt = TIMESTAMP,
        updatedAt = TIMESTAMP,
        hasPendingApprovals = hasPendingApprovals,
        hasPendingUserInput = hasPendingUserInput,
        session = status?.let {
            OrchestrationSession(
                threadId = ThreadId("t1"),
                status = it,
                updatedAt = TIMESTAMP,
            )
        },
    )

    private companion object {
        const val TIMESTAMP = "2026-08-16T12:00:00.000Z"

        /** [TIMESTAMP] as epoch milliseconds, so the ages under test are exact. */
        const val NOW_MILLIS = 1_786_881_600_000L
    }
}
