package com.silverbullet.kode.feature.threads.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.silverbullet.kode.core.designsystem.KodeIcons
import com.silverbullet.kode.core.designsystem.KodeMarkdown
import com.silverbullet.kode.core.designsystem.KodeMarkdownSizes
import com.silverbullet.kode.core.designsystem.KodeStreamingMarkdown
import com.silverbullet.kode.core.designsystem.KodeTheme
import com.silverbullet.kode.core.model.MessageRole
import com.silverbullet.kode.core.model.OrchestrationMessage
import com.silverbullet.kode.core.model.ThreadId
import com.silverbullet.kode.feature.threads.domain.ActivityIcon
import com.silverbullet.kode.feature.threads.domain.ActivityPresentation
import com.silverbullet.kode.feature.threads.domain.ActivityStatus
import com.silverbullet.kode.feature.threads.domain.FeedEntry
import com.silverbullet.kode.feature.threads.presentation.ComposerState
import com.silverbullet.kode.feature.threads.presentation.ThreadDetailViewModel
import com.silverbullet.kode.feature.threads.presentation.ThreadFeedUiState
import kotlinx.coroutines.flow.distinctUntilChanged
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Monospace styles for tool output, hoisted to top level.
 *
 * Building these with `.copy()` inside a row allocated a `TextStyle` (plus its
 * internal span/paragraph merge) per row per composition, and a fresh instance
 * defeats `Text`'s own parameter skipping.
 */
private val ToolPreviewStyle = TextStyle(
    fontFamily = KodeMarkdownSizes.monospace,
    fontSize = KodeMarkdownSizes.code,
)

/** Monospace block, for commands and paths awaiting approval. */
internal val ToolDetailStyle = TextStyle(
    fontFamily = KodeMarkdownSizes.monospace,
    fontSize = KodeMarkdownSizes.code,
    lineHeight = KodeMarkdownSizes.codeLineHeight,
)

@Composable
fun ThreadDetailRoute(
    threadId: ThreadId,
    modifier: Modifier = Modifier,
    viewModel: ThreadDetailViewModel = koinViewModel { parametersOf(threadId) },
) {
    val feed by viewModel.feed.collectAsStateWithLifecycle()

    ThreadDetailScreen(
        feed = feed,
        onToggleTurn = viewModel::toggleTurn,
        onToggleWorkGroup = viewModel::toggleWorkGroup,
        modifier = modifier,
        footer = { footerModifier ->
            // Both collect their own state, so a keystroke never reaches the
            // feed's recomposition scope and a streamed token never recomposes
            // whatever the user is typing into.
            val approval by viewModel.approval.collectAsStateWithLifecycle()
            val userInput by viewModel.userInput.collectAsStateWithLifecycle()

            if (approval.isActive) {
                // Approvals outrank questions: one gates an action the agent is
                // part-way through, and deciding it is a single tap.
                PendingApprovalCard(
                    state = approval,
                    onToggleCollapsed = viewModel::toggleApprovalCollapsed,
                    onDecide = viewModel::decideApproval,
                    modifier = footerModifier,
                )
            } else if (userInput.isActive) {
                // The question card takes the composer's place: there is nothing
                // useful to send until the agent has its answer.
                PendingUserInputCard(
                    state = userInput,
                    onToggleCollapsed = viewModel::toggleUserInputCollapsed,
                    onOptionToggled = viewModel::onOptionToggled,
                    onCustomAnswerChanged = viewModel::onCustomAnswerChanged,
                    onSubmit = viewModel::submitUserInput,
                    modifier = footerModifier,
                )
            } else {
                val composerState by viewModel.composer.collectAsStateWithLifecycle()
                Composer(
                    state = composerState,
                    onDraftChanged = viewModel::onDraftChanged,
                    onSend = viewModel::send,
                    modifier = footerModifier,
                )
            }
        },
    )
}

@Composable
fun ThreadDetailScreen(
    feed: ThreadFeedUiState,
    onToggleTurn: (String) -> Unit,
    onToggleWorkGroup: (String) -> Unit,
    modifier: Modifier = Modifier,
    footer: @Composable (Modifier) -> Unit,
) {
    val listState = rememberLazyListState()
    FollowFeedTail(listState, feed.entries.size)

    Column(modifier = modifier.fillMaxSize()) {
        feed.error?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            if (feed.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(
                        items = feed.entries,
                        key = { it.id },
                        // Separate recycling pools per row shape, so the lazy
                        // list reuses subcomposition slots instead of
                        // rebuilding them.
                        contentType = { it.contentType },
                    ) { entry ->
                        FeedRow(
                            entry = entry,
                            streamingMessageId = feed.streamingMessageId,
                            onToggleTurn = onToggleTurn,
                            onToggleWorkGroup = onToggleWorkGroup,
                        )
                    }
                }
            }
        }

        if (feed.isBusy) WorkingIndicator()

        HorizontalDivider(color = KodeTheme.colors.divider)
        footer(
            // `union` takes whichever is larger. With the keyboard up that is
            // the IME inset, which already covers the navigation bar; with it
            // down it is the navigation bar alone. Applying both additively
            // would leave a gap above the keyboard.
            Modifier.windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
        )
    }
}

private val FeedEntry.contentType: String
    get() = when (this) {
        is FeedEntry.Message -> "message:" + message.role
        is FeedEntry.Activity -> "activity"
        is FeedEntry.WorkToggle -> "work-toggle"
        is FeedEntry.TurnFold -> "turn-fold"
    }

@Composable
private fun FeedRow(
    entry: FeedEntry,
    streamingMessageId: String?,
    onToggleTurn: (String) -> Unit,
    onToggleWorkGroup: (String) -> Unit,
) {
    when (entry) {
        is FeedEntry.Message -> MessageRow(
            message = entry.message,
            isStreaming = entry.message.id == streamingMessageId,
        )

        is FeedEntry.Activity -> ActivityRow(entry.activity)
        is FeedEntry.WorkToggle -> WorkToggleRow(entry, onToggleWorkGroup)
        is FeedEntry.TurnFold -> TurnFoldRow(entry, onToggleTurn)
    }
}

/**
 * Keeps the newest content in view while the assistant streams, without
 * hijacking the list.
 *
 * Three things matter. It only follows when the user is already at the bottom,
 * so scrolling up to re-read is not yanked back. It jumps rather than animates,
 * because a new animation per token batch would queue and fight itself. And it
 * refuses to scroll while a drag or fling is in progress — `scrollToItem` takes
 * the scroll mutex at default priority, which would cancel the user's gesture
 * mid-flick.
 */
@Composable
private fun FollowFeedTail(listState: LazyListState, itemCount: Int) {
    val isAtBottom by remember(listState) {
        derivedStateOf {
            val layout = listState.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            last.index >= layout.totalItemsCount - 1
        }
    }

    // `itemCount` is a plain parameter, so it has to be funnelled through
    // `rememberUpdatedState` for `snapshotFlow` to observe it. Keying the effect
    // on the count would restart the collector on every token batch.
    val latestCount by rememberUpdatedState(itemCount)
    LaunchedEffect(listState) {
        snapshotFlow { latestCount }
            .distinctUntilChanged()
            .collect { count ->
                if (count > 0 && isAtBottom && !listState.isScrollInProgress) {
                    listState.scrollToItem(count - 1)
                }
            }
    }
}

@Composable
private fun MessageRow(message: OrchestrationMessage, isStreaming: Boolean) {
    when (message.role) {
        MessageRole.USER -> UserBubble(message)
        else -> AssistantMessage(message, isStreaming)
    }
}

@Composable
private fun UserBubble(message: OrchestrationMessage) {
    val colors = KodeTheme.colors
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(colors.userBubble, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            // User input is plain text, matching T3 Code — the composer does not
            // author markdown, so skipping the parser here is free.
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.userBubbleText,
            )
        }
    }
}

@Composable
private fun AssistantMessage(message: OrchestrationMessage, isStreaming: Boolean) {
    if (message.text.isEmpty()) return

    if (isStreaming) {
        KodeStreamingMarkdown(text = message.text, modifier = Modifier.fillMaxWidth())
    } else {
        // Keyed on id *and* length so a settled message reuses its cached parse
        // across scrolling, while an edited one re-parses.
        KodeMarkdown(
            text = message.text,
            cacheKey = message.id + ":" + message.text.length,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** One row standing in for a whole settled turn. */
@Composable
private fun TurnFoldRow(entry: FeedEntry.TurnFold, onToggle: (String) -> Unit) {
    val colors = KodeTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(entry.turnId) }
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (entry.interrupted) KodeIcons.Warning else KodeIcons.Check,
            contentDescription = null,
            tint = if (entry.interrupted) colors.warning else colors.muted,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = if (entry.interrupted) {
                "You stopped this response"
            } else {
                "Worked · ${entry.hiddenCount} steps"
            },
            style = MaterialTheme.typography.bodySmall,
            color = colors.muted,
        )
    }
}

/** "+3 previous tool calls" / "Show fewer tool calls". */
@Composable
private fun WorkToggleRow(entry: FeedEntry.WorkToggle, onToggle: (String) -> Unit) {
    val noun = when {
        entry.onlyToolActivities && entry.hiddenCount == 1 -> "tool call"
        entry.onlyToolActivities -> "tool calls"
        entry.hiddenCount == 1 -> "log entry"
        else -> "log entries"
    }

    Text(
        text = if (entry.expanded) "Show fewer $noun" else "+${entry.hiddenCount} previous $noun",
        style = MaterialTheme.typography.bodySmall,
        color = KodeTheme.colors.muted,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(entry.groupId) }
            .padding(start = 24.dp, top = 2.dp, bottom = 2.dp),
    )
}

/**
 * A tool row: icon, summary, and an optional one-line preview.
 *
 * Deliberately has no expand/collapse of its own. Per-row expansion state dies
 * when the lazy list disposes the row, and an `AnimatedVisibility` per row
 * allocates transition machinery for every row that scrolls past. Detail lives
 * behind the group toggle instead.
 */
@Composable
private fun ActivityRow(activity: ActivityPresentation) {
    val colors = KodeTheme.colors

    val tint = when (activity.status) {
        ActivityStatus.Failure -> colors.danger
        ActivityStatus.Success -> colors.success
        else -> when (activity.icon) {
            ActivityIcon.Warning -> colors.warning
            ActivityIcon.Alert -> colors.danger
            ActivityIcon.Agent -> colors.thinkingAccent
            else -> colors.toolAccent
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = activity.icon.vector(),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp).padding(top = 2.dp),
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = activity.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = if (activity.status == ActivityStatus.Failure) {
                    colors.danger
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            activity.preview?.let { preview ->
                Text(
                    text = preview,
                    style = ToolPreviewStyle,
                    color = colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun ActivityIcon.vector() = when (this) {
    ActivityIcon.Agent -> KodeIcons.Agent
    ActivityIcon.Alert -> KodeIcons.Alert
    ActivityIcon.Check -> KodeIcons.Check
    ActivityIcon.Command -> KodeIcons.Command
    ActivityIcon.Edit -> KodeIcons.Edit
    ActivityIcon.Eye -> KodeIcons.Eye
    ActivityIcon.Globe -> KodeIcons.Globe
    ActivityIcon.Hammer -> KodeIcons.Hammer
    ActivityIcon.Message -> KodeIcons.Message
    ActivityIcon.Warning -> KodeIcons.Warning
    ActivityIcon.Wrench -> KodeIcons.Wrench
    ActivityIcon.Zap -> KodeIcons.Zap
}

@Composable
private fun WorkingIndicator() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
            color = KodeTheme.colors.toolAccent,
        )
        Text(
            "Working…",
            style = MaterialTheme.typography.bodySmall,
            color = KodeTheme.colors.muted,
        )
    }
}

@Composable
private fun Composer(
    state: ComposerState,
    onDraftChanged: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(12.dp)) {
        state.error?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = state.draft,
                onValueChange = onDraftChanged,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message") },
                textStyle = MaterialTheme.typography.bodyLarge,
                maxLines = 5,
            )

            Button(
                onClick = onSend,
                enabled = state.canSend,
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                if (state.isSending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("Send")
                }
            }
        }
    }
}
