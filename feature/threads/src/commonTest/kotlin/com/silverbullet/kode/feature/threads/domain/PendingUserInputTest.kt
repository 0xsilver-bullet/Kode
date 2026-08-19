package com.silverbullet.kode.feature.threads.domain

import com.silverbullet.kode.core.model.ActivityPayload
import com.silverbullet.kode.core.model.ActivityTone
import com.silverbullet.kode.core.model.OrchestrationThreadActivity
import com.silverbullet.kode.core.model.UserInputOption
import com.silverbullet.kode.core.model.UserInputQuestion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

class PendingUserInputTest {

    // ------------------------------------------------------------- derivation

    @Test
    fun `a request opens and its resolution closes it`() {
        var open = emptyMap<String, PendingUserInput>()
        open = open.applyUserInputActivity(requested("r1", listOf(single("q1"))))
        assertEquals(setOf("r1"), open.keys)

        open = open.applyUserInputActivity(resolved("r1"))
        assertTrue(open.isEmpty())
    }

    @Test
    fun `a request with no questions is ignored`() {
        // An empty card could never be submitted, so showing one strands the user.
        val open = emptyMap<String, PendingUserInput>()
            .applyUserInputActivity(requested("r1", emptyList()))

        assertTrue(open.isEmpty())
    }

    @Test
    fun `a stale respond failure closes the request`() {
        var open = emptyMap<String, PendingUserInput>()
            .applyUserInputActivity(requested("r1", listOf(single("q1"))))

        open = open.applyUserInputActivity(
            respondFailed("r1", "Stale pending user-input request for thread t1"),
        )

        assertTrue(open.isEmpty())
    }

    @Test
    fun `an ordinary respond failure leaves the request open`() {
        // The request still needs answering; clearing it would strand the turn
        // with no way to retry.
        var open = emptyMap<String, PendingUserInput>()
            .applyUserInputActivity(requested("r1", listOf(single("q1"))))

        open = open.applyUserInputActivity(respondFailed("r1", "network unreachable"))

        assertEquals(setOf("r1"), open.keys)
    }

    @Test
    fun `a snapshot derives open requests in sequence order`() {
        // Listed out of order on purpose: the derivation trusts its caller to
        // have sorted, so the test sorts the way the reducer does.
        val activities = listOf(
            resolved("r1", sequence = 3),
            requested("r1", listOf(single("q1")), sequence = 1),
            requested("r2", listOf(single("q2")), sequence = 2),
        ).sortedInActivityOrder()

        assertEquals(setOf("r2"), derivePendingUserInputs(activities).keys)
    }

    @Test
    fun `unrelated activities are ignored`() {
        val open = emptyMap<String, PendingUserInput>().applyUserInputActivity(
            OrchestrationThreadActivity(
                id = "a9",
                tone = ActivityTone.TOOL,
                kind = "tool.completed",
                summary = "Ran a command",
                createdAt = "T10:00:00",
            ),
        )
        assertTrue(open.isEmpty())
    }

    // ------------------------------------------------------------------ drafts

    @Test
    fun `single select replaces the previous choice`() {
        val question = single("q1")
        val draft = QuestionDraft()
            .toggleOption(question, "A")
            .toggleOption(question, "B")

        assertEquals(listOf("B"), draft.selectedLabels)
    }

    @Test
    fun `multi select accumulates and toggles off`() {
        val question = multi("q1")
        var draft = QuestionDraft().toggleOption(question, "A").toggleOption(question, "B")
        assertEquals(listOf("A", "B"), draft.selectedLabels)

        draft = draft.toggleOption(question, "A")
        assertEquals(listOf("B"), draft.selectedLabels)
    }

    @Test
    fun `selecting an option does not destroy typed text`() {
        // T3 Code clears the custom answer here, silently discarding what the
        // user typed.
        val question = single("q1")
        val draft = QuestionDraft(customAnswer = "something bespoke")
            .toggleOption(question, "A")

        assertEquals("something bespoke", draft.customAnswer)
        assertEquals(listOf("A"), draft.selectedLabels)
    }

    @Test
    fun `typing does not destroy the selection`() {
        val draft = QuestionDraft(selectedLabels = listOf("A")).copy(customAnswer = "typed")

        assertEquals(listOf("A"), draft.selectedLabels)
        assertTrue(draft.isSelectionOverridden)
    }

    @Test
    fun `a custom answer wins over the selection`() {
        // Matches `resolvePendingUserInputAnswer`: changing this would send
        // something other than what T3 Code sends.
        val question = single("q1")
        val draft = QuestionDraft(selectedLabels = listOf("A"), customAnswer = "  bespoke  ")

        assertEquals(JsonPrimitive("bespoke"), draft.effectiveAnswer(question))
    }

    @Test
    fun `clearing the custom answer restores the selection`() {
        val question = single("q1")
        val draft = QuestionDraft(selectedLabels = listOf("A"), customAnswer = "x")

        assertEquals(JsonPrimitive("A"), draft.copy(customAnswer = "").effectiveAnswer(question))
    }

    @Test
    fun `blank custom text does not count as an answer`() {
        assertNull(QuestionDraft(customAnswer = "   ").effectiveAnswer(single("q1")))
    }

    @Test
    fun `multi select sends an array and single select a string`() {
        assertEquals(
            JsonArray(listOf(JsonPrimitive("A"), JsonPrimitive("B"))),
            QuestionDraft(selectedLabels = listOf("A", "B")).effectiveAnswer(multi("q1")),
        )
        assertEquals(
            JsonPrimitive("A"),
            QuestionDraft(selectedLabels = listOf("A")).effectiveAnswer(single("q1")),
        )
    }

    // ----------------------------------------------------------------- answers

    @Test
    fun `an incomplete form reports which questions are missing`() {
        // This is what replaces T3 Code's dead, unexplained Submit button.
        val questions = listOf(single("q1"), single("q2"), single("q3"))
        val answers = buildUserInputAnswers(
            questions = questions,
            drafts = mapOf("q2" to QuestionDraft(selectedLabels = listOf("A"))),
        )

        assertFalse(answers.isComplete)
        assertEquals(listOf("q1", "q3"), answers.missingQuestionIds)
        assertEquals(1, answers.answeredCount)
    }

    @Test
    fun `a complete form carries every answer keyed by question id`() {
        val questions = listOf(single("q1"), multi("q2"))
        val answers = buildUserInputAnswers(
            questions = questions,
            drafts = mapOf(
                "q1" to QuestionDraft(selectedLabels = listOf("A")),
                "q2" to QuestionDraft(selectedLabels = listOf("B", "C")),
            ),
        )

        assertTrue(answers.isComplete)
        assertEquals(setOf("q1", "q2"), answers.answers.keys)
        assertEquals(JsonPrimitive("A"), answers.answers["q1"])
        assertEquals(JsonArray(listOf(JsonPrimitive("B"), JsonPrimitive("C"))), answers.answers["q2"])
    }

    @Test
    fun `missing ids follow question order rather than draft order`() {
        val questions = listOf(single("q1"), single("q2"), single("q3"))
        val answers = buildUserInputAnswers(questions, emptyMap())

        // The UI scrolls to the first of these, so the order has to be the
        // order the user sees.
        assertEquals(listOf("q1", "q2", "q3"), answers.missingQuestionIds)
    }

    // ----------------------------------------------------------------- builders

    private fun single(id: String) = UserInputQuestion(
        id = id,
        header = "Header $id",
        question = "Question $id?",
        options = listOf(UserInputOption("A", "first"), UserInputOption("B", "second")),
        multiSelect = false,
    )

    private fun multi(id: String) = single(id).copy(multiSelect = true)

    private fun requested(
        requestId: String,
        questions: List<UserInputQuestion>,
        sequence: Int = 1,
    ) = activity("user-input.requested", requestId, sequence) { it.copy(questions = questions) }

    private fun resolved(requestId: String, sequence: Int = 2) =
        activity("user-input.resolved", requestId, sequence) { it }

    private fun respondFailed(requestId: String, detail: String, sequence: Int = 2) =
        activity("provider.user-input.respond.failed", requestId, sequence) {
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
        summary = "Agent asked a question",
        sequence = sequence,
        createdAt = "T10:00:0$sequence",
        payload = payload(ActivityPayload(requestId = requestId)),
    )
}
