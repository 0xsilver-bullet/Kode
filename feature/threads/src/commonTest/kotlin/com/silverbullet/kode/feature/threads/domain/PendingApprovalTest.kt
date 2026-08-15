package com.silverbullet.kode.feature.threads.domain

import com.silverbullet.kode.core.model.ActivityPayload
import com.silverbullet.kode.core.model.ActivityTone
import com.silverbullet.kode.core.model.ApprovalRequestKind
import com.silverbullet.kode.core.model.OrchestrationThreadActivity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PendingApprovalTest {

    @Test
    fun `a request opens and its resolution closes it`() {
        var open = emptyMap<String, PendingApproval>()
        open = open.applyApprovalActivity(requested("r1", kind = ApprovalRequestKind.COMMAND))
        assertEquals(setOf("r1"), open.keys)

        open = open.applyApprovalActivity(resolved("r1"))
        assertTrue(open.isEmpty())
    }

    @Test
    fun `a request with no resolvable kind is dropped`() {
        // There would be nothing to describe to the user, so a blank card is
        // worse than none.
        val open = emptyMap<String, PendingApproval>()
            .applyApprovalActivity(requested("r1", kind = null, requestType = null))

        assertTrue(open.isEmpty())
    }

    @Test
    fun `provider request types fall back to a kind`() {
        // Some providers send only `requestType`; dropping those would silently
        // lose Codex-style approvals.
        fun kindFor(requestType: String) = emptyMap<String, PendingApproval>()
            .applyApprovalActivity(requested("r1", kind = null, requestType = requestType))
            .getValue("r1").requestKind

        assertEquals(ApprovalRequestKind.COMMAND, kindFor("command_execution_approval"))
        assertEquals(ApprovalRequestKind.COMMAND, kindFor("exec_command_approval"))
        assertEquals(ApprovalRequestKind.FILE_READ, kindFor("file_read_approval"))
        assertEquals(ApprovalRequestKind.FILE_CHANGE, kindFor("file_change_approval"))
        assertEquals(ApprovalRequestKind.FILE_CHANGE, kindFor("apply_patch_approval"))
    }

    @Test
    fun `an explicit kind wins over the request type`() {
        val open = emptyMap<String, PendingApproval>().applyApprovalActivity(
            requested("r1", kind = ApprovalRequestKind.FILE_READ, requestType = "apply_patch_approval"),
        )

        assertEquals(ApprovalRequestKind.FILE_READ, open.getValue("r1").requestKind)
    }

    @Test
    fun `an unknown request type resolves to nothing`() {
        assertNull(resolveRequestKind(requestKind = null, requestType = "something_new"))
    }

    @Test
    fun `a stale respond failure closes the request`() {
        var open = emptyMap<String, PendingApproval>()
            .applyApprovalActivity(requested("r1", kind = ApprovalRequestKind.COMMAND))

        open = open.applyApprovalActivity(
            respondFailed("r1", "Unknown pending approval request for thread t1"),
        )

        assertTrue(open.isEmpty())
    }

    @Test
    fun `an ordinary respond failure leaves the request open`() {
        // Still waiting on the user; clearing it would strand the turn.
        var open = emptyMap<String, PendingApproval>()
            .applyApprovalActivity(requested("r1", kind = ApprovalRequestKind.COMMAND))

        open = open.applyApprovalActivity(respondFailed("r1", "socket closed"))

        assertEquals(setOf("r1"), open.keys)
    }

    @Test
    fun `a snapshot derives open approvals in sequence order`() {
        val activities = listOf(
            requested("r1", ApprovalRequestKind.COMMAND, sequence = 1),
            requested("r2", ApprovalRequestKind.FILE_CHANGE, sequence = 2),
            resolved("r1", sequence = 3),
        )

        assertEquals(setOf("r2"), derivePendingApprovals(activities).keys)
    }

    @Test
    fun `each kind gets a readable title`() {
        assertEquals("Run a command", approval(ApprovalRequestKind.COMMAND).title)
        assertEquals("Read a file", approval(ApprovalRequestKind.FILE_READ).title)
        assertEquals("Edit a file", approval(ApprovalRequestKind.FILE_CHANGE).title)
        // T3 Code renders the raw kind here, which reads as "file-change".
        assertEquals("Approve this action", approval("something-else").title)
    }

    @Test
    fun `blank detail is dropped rather than shown as an empty block`() {
        val open = emptyMap<String, PendingApproval>()
            .applyApprovalActivity(requested("r1", ApprovalRequestKind.COMMAND, detail = "   "))

        assertNull(open.getValue("r1").detail)
    }

    @Test
    fun `user input activities do not open approvals`() {
        val open = emptyMap<String, PendingApproval>().applyApprovalActivity(
            OrchestrationThreadActivity(
                id = "a1",
                tone = ActivityTone.APPROVAL,
                kind = "user-input.requested",
                summary = "Agent asked a question",
                createdAt = "T10:00:00",
                payload = ActivityPayload(requestId = "r1"),
            ),
        )

        assertTrue(open.isEmpty())
    }

    // ----------------------------------------------------------------- builders

    private fun approval(kind: String) = PendingApproval(
        requestId = "r1",
        requestKind = kind,
        createdAt = "T10:00:00",
    )

    private fun requested(
        requestId: String,
        kind: String?,
        requestType: String? = null,
        detail: String? = "rm -rf build/",
        sequence: Int = 1,
    ) = activity("approval.requested", requestId, sequence) {
        it.copy(requestKind = kind, requestType = requestType, detail = detail)
    }

    private fun resolved(requestId: String, sequence: Int = 2) =
        activity("approval.resolved", requestId, sequence) { it }

    private fun respondFailed(requestId: String, detail: String, sequence: Int = 2) =
        activity("provider.approval.respond.failed", requestId, sequence) {
            it.copy(detail = detail)
        }

    private fun activity(
        kind: String,
        requestId: String,
        sequence: Int,
        payload: (ActivityPayload) -> ActivityPayload,
    ) = OrchestrationThreadActivity(
        id = "$kind:$requestId",
        tone = ActivityTone.APPROVAL,
        kind = kind,
        summary = "Approval needed",
        sequence = sequence,
        createdAt = "T10:00:0$sequence",
        payload = payload(ActivityPayload(requestId = requestId)),
    )
}
