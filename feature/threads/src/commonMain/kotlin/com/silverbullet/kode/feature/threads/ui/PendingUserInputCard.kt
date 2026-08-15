package com.silverbullet.kode.feature.threads.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.silverbullet.kode.core.designsystem.KodeIcons
import com.silverbullet.kode.core.designsystem.KodeTheme
import com.silverbullet.kode.core.model.UserInputQuestion
import com.silverbullet.kode.feature.threads.domain.QuestionDraft
import com.silverbullet.kode.feature.threads.presentation.UserInputUiState

/**
 * The agent's questions, docked above the composer.
 *
 * Docked rather than inline in the feed: a question that scrolls away into the
 * transcript is easy to lose, and the turn is blocked until it is answered. It
 * takes the composer's place while open — there is nothing useful to type until
 * the agent has its answer — and collapses to a one-line bar so the chat is
 * still readable underneath.
 */
@Composable
fun PendingUserInputCard(
    state: UserInputUiState,
    onToggleCollapsed: () -> Unit,
    onOptionToggled: (questionId: String, label: String) -> Unit,
    onCustomAnswerChanged: (questionId: String, value: String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pending = state.pending ?: return
    val colors = KodeTheme.colors
    val listState = rememberLazyListState()

    // An incomplete submit points at the first gap; jump there so the user is
    // not left hunting through a scrolled list for what is missing.
    LaunchedEffect(state.highlightedQuestionId) {
        val target = state.highlightedQuestionId ?: return@LaunchedEffect
        val index = pending.questions.indexOfFirst { it.id == target }
        if (index >= 0) listState.animateScrollToItem(index)
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            CardHeader(
                state = state,
                onToggleCollapsed = onToggleCollapsed,
            )

            AnimatedVisibility(
                visible = !state.collapsed,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column {
                    LazyColumn(
                        state = listState,
                        // Bounded so the transcript stays partly visible, but
                        // generous enough that a two-option question fits
                        // without scrolling.
                        modifier = Modifier.heightIn(max = 420.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        items(
                            items = pending.questions,
                            key = { it.id },
                            contentType = { "question" },
                        ) { question ->
                            QuestionBlock(
                                question = question,
                                draft = state.drafts[question.id] ?: EmptyDraft,
                                isHighlighted = question.id == state.highlightedQuestionId,
                                onOptionToggled = onOptionToggled,
                                onCustomAnswerChanged = onCustomAnswerChanged,
                            )
                        }
                    }

                    state.error?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }

                    Button(
                        onClick = onSubmit,
                        // Deliberately never disabled — see `submitUserInput`.
                        enabled = !state.isSubmitting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        if (state.isSubmitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text(
                                if (state.answers.isComplete) {
                                    "Send answers"
                                } else {
                                    "Answer ${state.answers.missingQuestionIds.size} more"
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CardHeader(state: UserInputUiState, onToggleCollapsed: () -> Unit) {
    val colors = KodeTheme.colors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleCollapsed)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = KodeIcons.Message,
            contentDescription = null,
            tint = colors.info,
            modifier = Modifier.size(18.dp),
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (state.otherPendingCount > 0) {
                    "Agent needs input · +${state.otherPendingCount} more"
                } else {
                    "Agent needs input"
                },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Progress is on the bar too, so a collapsed card still says how
            // much is left without being reopened.
            Text(
                text = "${state.answeredCount} of ${state.questionCount} answered",
                style = MaterialTheme.typography.bodySmall,
                color = if (state.answers.isComplete) colors.success else colors.muted,
            )
        }

        Icon(
            imageVector = KodeIcons.ChevronDown,
            contentDescription = if (state.collapsed) "Expand questions" else "Collapse questions",
            tint = colors.muted,
            modifier = Modifier
                .size(18.dp)
                .graphicsLayer { rotationZ = if (state.collapsed) 0f else 180f },
        )
    }
}

@Composable
private fun QuestionBlock(
    question: UserInputQuestion,
    draft: QuestionDraft,
    isHighlighted: Boolean,
    onOptionToggled: (questionId: String, label: String) -> Unit,
    onCustomAnswerChanged: (questionId: String, value: String) -> Unit,
) {
    val colors = KodeTheme.colors

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isHighlighted) {
                    Modifier
                        .border(1.dp, colors.warning, RoundedCornerShape(12.dp))
                        .padding(8.dp)
                } else {
                    Modifier
                },
            ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = question.header.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = colors.muted,
        )
        Text(
            text = question.question,
            style = MaterialTheme.typography.bodyLarge,
        )
        if (question.multiSelect) {
            Text(
                text = "Choose any that apply",
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
            )
        }

        question.options.forEach { option ->
            OptionRow(
                label = option.label,
                description = option.description,
                selected = option.label in draft.selectedLabels,
                // A custom answer takes precedence on the wire, so the chips are
                // shown as inactive rather than silently ignored.
                dimmed = draft.hasCustomAnswer,
                onClick = { onOptionToggled(question.id, option.label) },
            )
        }

        OutlinedTextField(
            value = draft.customAnswer,
            onValueChange = { onCustomAnswerChanged(question.id, it) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Or write your own") },
            textStyle = MaterialTheme.typography.bodyLarge,
            keyboardOptions = KeyboardOptions(),
            maxLines = 4,
        )

        if (draft.isSelectionOverridden) {
            // T3 Code destroys the selection here instead of explaining it.
            Text(
                text = "Your written answer will be sent instead of the selection. " +
                    "Clear it to use the selection again.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.warning,
            )
        }
    }
}

@Composable
private fun OptionRow(
    label: String,
    description: String,
    selected: Boolean,
    dimmed: Boolean,
    onClick: () -> Unit,
) {
    val colors = KodeTheme.colors
    val borderColor = when {
        selected && !dimmed -> MaterialTheme.colorScheme.primary
        else -> colors.divider
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .background(
                if (selected && !dimmed) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                },
                RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(modifier = Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            if (selected) {
                Icon(
                    imageVector = KodeIcons.Check,
                    contentDescription = null,
                    tint = if (dimmed) colors.muted else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (dimmed) colors.muted else MaterialTheme.colorScheme.onSurface,
            )
            if (description.isNotBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                )
            }
        }
    }
}

private val EmptyDraft = QuestionDraft()
