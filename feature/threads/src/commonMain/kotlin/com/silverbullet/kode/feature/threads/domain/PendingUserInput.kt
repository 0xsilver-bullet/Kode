package com.silverbullet.kode.feature.threads.domain

import androidx.compose.runtime.Immutable
import com.silverbullet.kode.core.model.OrchestrationThreadActivity
import com.silverbullet.kode.core.model.UserInputQuestion
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** An open request from the agent for the user to answer. */
@Immutable
data class PendingUserInput(
    val requestId: String,
    val createdAt: String,
    val questions: List<UserInputQuestion>,
)

/**
 * A single question's in-progress answer.
 *
 * Selections and custom text are held **together**, unlike T3 Code's mobile
 * client, which clears one the moment you touch the other — so tapping a chip
 * silently discarded text you had typed, and typing made your selection appear
 * unselected. Here both survive; [effectiveAnswer] decides which is actually
 * sent, and the UI says so.
 */
@Immutable
data class QuestionDraft(
    val selectedLabels: List<String> = emptyList(),
    val customAnswer: String = "",
) {
    val hasCustomAnswer: Boolean get() = customAnswer.isNotBlank()

    /** True when a selection exists but custom text is overriding it. */
    val isSelectionOverridden: Boolean get() = hasCustomAnswer && selectedLabels.isNotEmpty()
}

/**
 * The value that will be sent for a question, or null when unanswered.
 *
 * Custom text wins over selected options — that is the server-side precedence in
 * `resolvePendingUserInputAnswer`, and changing it here would silently send
 * something other than what T3 Code would.
 */
fun QuestionDraft.effectiveAnswer(question: UserInputQuestion): JsonElement? {
    val custom = customAnswer.trim()
    if (custom.isNotEmpty()) return JsonPrimitive(custom)

    val selected = selectedLabels.filter { it.isNotBlank() }
    if (selected.isEmpty()) return null

    return if (question.multiSelect) {
        JsonArray(selected.map(::JsonPrimitive))
    } else {
        JsonPrimitive(selected.first())
    }
}

/** Toggles for multi-select, replaces for single-select. */
fun QuestionDraft.toggleOption(question: UserInputQuestion, label: String): QuestionDraft {
    val trimmed = label.trim()
    val next = when {
        !question.multiSelect -> listOf(trimmed)
        trimmed in selectedLabels -> selectedLabels - trimmed
        else -> selectedLabels + trimmed
    }
    return copy(selectedLabels = next)
}

/**
 * The answers to submit, plus which questions are still missing one.
 *
 * The server has no notion of a partial reply — `thread.user-input.respond`
 * carries every answer or the request stays open — so [missingQuestionIds]
 * exists to tell the user *which* ones, rather than leaving a dead Submit
 * button with no explanation.
 */
@Immutable
data class UserInputAnswers(
    val answers: Map<String, JsonElement>,
    val missingQuestionIds: List<String>,
) {
    val isComplete: Boolean get() = missingQuestionIds.isEmpty()
    val answeredCount: Int get() = answers.size
}

fun buildUserInputAnswers(
    questions: List<UserInputQuestion>,
    drafts: Map<String, QuestionDraft>,
): UserInputAnswers {
    val answers = LinkedHashMap<String, JsonElement>(questions.size)
    val missing = ArrayList<String>()

    for (question in questions) {
        val answer = drafts[question.id]?.effectiveAnswer(question)
        if (answer == null) missing += question.id else answers[question.id] = answer
    }

    return UserInputAnswers(answers = answers, missingQuestionIds = missing)
}

// ----------------------------------------------------------------- derivation

private const val KIND_REQUESTED = "user-input.requested"
private const val KIND_RESOLVED = "user-input.resolved"
private const val KIND_RESPOND_FAILED = "provider.user-input.respond.failed"

/**
 * Folds one activity into the set of open requests.
 *
 * Keyed by `requestId`: a request opens on `user-input.requested` and closes on
 * `user-input.resolved`. A respond failure only closes it when the server says
 * the request was *stale or unknown* — any other failure means the request is
 * still open and the user needs to try again, so clearing it would strand them.
 */
fun Map<String, PendingUserInput>.applyUserInputActivity(
    activity: OrchestrationThreadActivity,
): Map<String, PendingUserInput> {
    val payload = activity.payload ?: return this
    val requestId = payload.requestId ?: return this

    return when (activity.kind) {
        KIND_REQUESTED -> {
            val questions = payload.questions.orEmpty()
            // A request with no decodable questions is unanswerable; ignoring it
            // is better than showing an empty card that can never be submitted.
            if (questions.isEmpty()) {
                this
            } else {
                this + (
                    requestId to PendingUserInput(
                        requestId = requestId,
                        createdAt = activity.createdAt,
                        questions = questions,
                    )
                    )
            }
        }

        KIND_RESOLVED -> this - requestId

        KIND_RESPOND_FAILED ->
            if (payload.detail.isStalePendingRequest()) this - requestId else this

        else -> this
    }
}

/**
 * Rebuilds open requests from scratch, for a snapshot.
 *
 * [activities] must already be in `sortedInActivityOrder` — see
 * [derivePendingApprovals], which shares the caller's single sorted list.
 */
fun derivePendingUserInputs(
    activities: List<OrchestrationThreadActivity>,
): Map<String, PendingUserInput> {
    var open = emptyMap<String, PendingUserInput>()
    activities.forEach { open = open.applyUserInputActivity(it) }
    return open
}

/** Port of `isStalePendingRequestFailureDetail`. Shared with approvals. */
internal fun String?.isStalePendingRequest(): Boolean {
    val normalized = this?.lowercase() ?: return false
    return STALE_REQUEST_PHRASES.any { it in normalized }
}

private val STALE_REQUEST_PHRASES = listOf(
    "stale pending approval request",
    "stale pending user-input request",
    "unknown pending approval request",
    "unknown pending permission request",
    "unknown pending user-input request",
)
