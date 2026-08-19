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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Behaviour ported from `effectiveSettled` in
 * `packages/client-runtime/src/state/threadSettled.ts`.
 */
class ThreadSettlementTest {

    private val now = "2026-08-16T12:00:00.000Z"
    private val longAgo = "2026-08-01T12:00:00.000Z"
    private val recently = "2026-08-16T11:59:00.000Z"

    @Test
    fun `a quiet thread settles once it is older than the window`() {
        assertTrue(thread(latestUserMessageAt = longAgo).isEffectivelySettled(now))
    }

    @Test
    fun `a recently active thread stays in the inbox`() {
        assertFalse(thread(latestUserMessageAt = "2026-08-15T12:00:00.000Z").isEffectivelySettled(now))
    }

    @Test
    fun `an explicit settle files it away immediately`() {
        val thread = thread(
            latestUserMessageAt = "2026-08-16T09:00:00.000Z",
            settledOverride = SettledOverride.SETTLED,
        )
        assertTrue(thread.isEffectivelySettled(now))
    }

    @Test
    fun `the keep-active pin suppresses auto-settle`() {
        val thread = thread(latestUserMessageAt = longAgo, settledOverride = SettledOverride.ACTIVE)
        assertFalse(thread.isEffectivelySettled(now))
    }

    @Test
    fun `blocked work outranks an explicit settle`() {
        // Burying a thread that is waiting on the user would strand the turn.
        val approval = thread(
            latestUserMessageAt = longAgo,
            settledOverride = SettledOverride.SETTLED,
            hasPendingApprovals = true,
        )
        val question = thread(
            latestUserMessageAt = longAgo,
            settledOverride = SettledOverride.SETTLED,
            hasPendingUserInput = true,
        )

        assertFalse(approval.isEffectivelySettled(now))
        assertFalse(question.isEffectivelySettled(now))
    }

    @Test
    fun `a running session outranks an explicit settle`() {
        listOf(SessionStatus.RUNNING, SessionStatus.STARTING).forEach { status ->
            val thread = thread(
                latestUserMessageAt = longAgo,
                settledOverride = SettledOverride.SETTLED,
                sessionStatus = status,
            )
            assertFalse(thread.isEffectivelySettled(now), status)
        }
    }

    @Test
    fun `an idle session does not hold a stale thread open`() {
        val thread = thread(latestUserMessageAt = longAgo, sessionStatus = SessionStatus.IDLE)
        assertTrue(thread.isEffectivelySettled(now))
    }

    @Test
    fun `a just-sent message keeps the thread active while its turn starts`() {
        // Otherwise a thread you messaged from the phone could drop into the
        // shelf during the seconds before the turn begins.
        val thread = thread(latestUserMessageAt = recently, settledOverride = SettledOverride.SETTLED)
        assertFalse(thread.isEffectivelySettled(now))
    }

    @Test
    fun `a settle the server accepted after the message wins the race`() {
        val thread = thread(
            latestUserMessageAt = recently,
            settledOverride = SettledOverride.SETTLED,
            settledAt = "2026-08-16T11:59:30.000Z",
        )
        assertTrue(thread.isEffectivelySettled(now))
    }

    @Test
    fun `a message newer than the settle keeps the thread active`() {
        val thread = thread(
            latestUserMessageAt = recently,
            settledOverride = SettledOverride.SETTLED,
            settledAt = "2026-08-16T11:58:00.000Z",
        )
        assertFalse(thread.isEffectivelySettled(now))
    }

    @Test
    fun `a turn that has picked the message up clears the queued state`() {
        val thread = thread(
            latestUserMessageAt = recently,
            latestTurn = OrchestrationLatestTurn(
                turnId = "turn1",
                state = TurnState.COMPLETED,
                requestedAt = recently,
                completedAt = "2026-08-16T11:59:30.000Z",
            ),
            settledOverride = SettledOverride.SETTLED,
        )
        assertTrue(thread.isEffectivelySettled(now))
    }

    @Test
    fun `an errored session clears the queued state`() {
        val thread = thread(
            latestUserMessageAt = recently,
            settledOverride = SettledOverride.SETTLED,
            sessionStatus = SessionStatus.ERROR,
        )
        assertTrue(thread.isEffectivelySettled(now))
    }

    @Test
    fun `a malformed timestamp never causes a surprise settle`() {
        assertFalse(thread(latestUserMessageAt = "not-a-date").isEffectivelySettled(now))
        assertFalse(thread(latestUserMessageAt = longAgo).isEffectivelySettled("not-a-date"))
    }

    @Test
    fun `a thread with no activity at all stays active`() {
        assertFalse(thread(latestUserMessageAt = null).isEffectivelySettled(now))
    }

    @Test
    fun `disabling auto-settle leaves only explicit pins`() {
        val stale = thread(latestUserMessageAt = longAgo)
        assertFalse(stale.isEffectivelySettled(now, autoSettleAfterDays = null))

        val pinned = thread(latestUserMessageAt = longAgo, settledOverride = SettledOverride.SETTLED)
        assertTrue(pinned.isEffectivelySettled(now, autoSettleAfterDays = null))
    }

    @Test
    fun `last activity takes the newest of message and turn timestamps`() {
        val thread = thread(
            latestUserMessageAt = longAgo,
            latestTurn = OrchestrationLatestTurn(
                turnId = "turn1",
                state = TurnState.COMPLETED,
                requestedAt = longAgo,
                completedAt = "2026-08-15T12:00:00.000Z",
            ),
        )

        assertEquals("2026-08-15T12:00:00.000Z", thread.lastActivityAt())
        // The newer turn timestamp keeps it out of the shelf.
        assertFalse(thread.isEffectivelySettled(now))
    }

    @Test
    fun `a quiet thread is settleable even though it would auto-settle anyway`() {
        // Pinning the state is the point: an auto-settled thread drifts back
        // into the inbox on any activity, an explicitly settled one does not.
        assertTrue(thread(latestUserMessageAt = longAgo).isSettleable(now))
    }

    @Test
    fun `a running thread is not settleable`() {
        assertFalse(
            thread(
                latestUserMessageAt = recently,
                sessionStatus = SessionStatus.RUNNING,
            ).isSettleable(now),
        )
    }

    @Test
    fun `a starting thread is not settleable`() {
        assertFalse(
            thread(
                latestUserMessageAt = longAgo,
                sessionStatus = SessionStatus.STARTING,
            ).isSettleable(now),
        )
    }

    @Test
    fun `a thread waiting on an approval is not settleable`() {
        assertFalse(thread(latestUserMessageAt = longAgo, hasPendingApprovals = true).isSettleable(now))
    }

    @Test
    fun `a thread waiting on user input is not settleable`() {
        assertFalse(thread(latestUserMessageAt = longAgo, hasPendingUserInput = true).isSettleable(now))
    }

    @Test
    fun `a thread whose turn has not been picked up yet is not settleable`() {
        assertFalse(thread(latestUserMessageAt = recently).isSettleable(now))
    }

    @Test
    fun `an idle thread is settleable`() {
        assertTrue(
            thread(
                latestUserMessageAt = "2026-08-15T12:00:00.000Z",
                sessionStatus = SessionStatus.STOPPED,
            ).isSettleable(now),
        )
    }

    @Test
    fun `partitioning preserves order within each half`() {
        val threads = listOf(
            thread(id = "a", latestUserMessageAt = longAgo),
            thread(id = "b", latestUserMessageAt = "2026-08-15T12:00:00.000Z"),
            thread(id = "c", latestUserMessageAt = longAgo),
            thread(id = "d", latestUserMessageAt = "2026-08-15T13:00:00.000Z"),
        )

        val partition = threads.partitionBySettlement(now)

        assertEquals(listOf("b", "d"), partition.active.map { it.id.value })
        assertEquals(listOf("a", "c"), partition.settled.map { it.id.value })
    }

    private fun thread(
        id: String = "t1",
        latestUserMessageAt: String? = null,
        latestTurn: OrchestrationLatestTurn? = null,
        settledOverride: String? = null,
        settledAt: String? = null,
        sessionStatus: String? = null,
        hasPendingApprovals: Boolean = false,
        hasPendingUserInput: Boolean = false,
    ) = OrchestrationThreadShell(
        id = ThreadId(id),
        projectId = ProjectId("p1"),
        title = "Thread $id",
        createdAt = "2026-08-01T00:00:00.000Z",
        updatedAt = now,
        latestUserMessageAt = latestUserMessageAt,
        latestTurn = latestTurn,
        settledOverride = settledOverride,
        settledAt = settledAt,
        hasPendingApprovals = hasPendingApprovals,
        hasPendingUserInput = hasPendingUserInput,
        session = sessionStatus?.let {
            OrchestrationSession(threadId = ThreadId(id), status = it, updatedAt = now)
        },
    )
}
