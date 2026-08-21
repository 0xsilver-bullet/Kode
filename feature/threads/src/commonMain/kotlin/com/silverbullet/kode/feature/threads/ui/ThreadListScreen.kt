package com.silverbullet.kode.feature.threads.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.silverbullet.kode.core.designsystem.KodeExtendedColors
import com.silverbullet.kode.core.designsystem.KodeIcons
import com.silverbullet.kode.core.designsystem.KodeMarkdownSizes
import com.silverbullet.kode.core.designsystem.KodeTheme
import com.silverbullet.kode.core.designsystem.ProviderMark
import com.silverbullet.kode.core.designsystem.SwipeReveal
import com.silverbullet.kode.core.designsystem.SwipeRevealAction
import com.silverbullet.kode.core.designsystem.SwipeRevealCoordinator
import com.silverbullet.kode.core.designsystem.rememberSwipeRevealCoordinator
import com.silverbullet.kode.core.designsystem.rememberSwipeRevealRowClick
import com.silverbullet.kode.core.model.EnvironmentId
import com.silverbullet.kode.core.model.ThreadId
import com.silverbullet.kode.feature.threads.presentation.ThreadListItem
import com.silverbullet.kode.feature.threads.presentation.ThreadListUiState
import com.silverbullet.kode.feature.threads.presentation.ThreadListViewModel
import com.silverbullet.kode.feature.threads.domain.ThreadRowStatus
import com.silverbullet.kode.feature.threads.presentation.ThreadRow
import com.silverbullet.kode.feature.threads.presentation.ThreadRowVariant
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun ThreadListRoute(
    onOpenThread: (EnvironmentId, ThreadId) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ThreadListViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ThreadListScreen(
        uiState = uiState,
        onOpenThread = onOpenThread,
        onToggleSettledShelf = viewModel::toggleSettledShelf,
        onSettleThread = viewModel::settleThread,
        onDismissActionError = viewModel::dismissActionError,
        modifier = modifier,
    )
}

@Composable
fun ThreadListScreen(
    uiState: ThreadListUiState,
    onOpenThread: (EnvironmentId, ThreadId) -> Unit,
    onToggleSettledShelf: () -> Unit,
    onSettleThread: (EnvironmentId, ThreadId) -> Unit,
    onDismissActionError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val swipeCoordinator = rememberSwipeRevealCoordinator()

    // A revealed row must not ride along with the list. Closing on the *start*
    // of a scroll matches T3 Code mobile, where the swipe gate arms as soon as
    // the list begins to drag.
    LaunchedEffect(listState, swipeCoordinator) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling -> if (scrolling) swipeCoordinator.closeOpenRow() }
    }

    Column(modifier = modifier.fillMaxSize()) {
        uiState.error?.let { error ->
            // A failed subscription on a healthy socket is a synchronization
            // problem, not a disconnection — say so rather than implying a
            // reconnect that is not scheduled.
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        uiState.actionError?.let { error ->
            // Tappable rather than timed: a rejected action is the user's to
            // read and dismiss, and the app has no snackbar host to borrow.
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .clickable(onClick = onDismissActionError)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        when {
            uiState.isLoading -> CenteredMessage { CircularProgressIndicator() }

            uiState.isEmpty -> CenteredMessage {
                Text(
                    "No threads yet. Start one from the desktop app.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            // Rows scroll under the navigation bar; the inset is content
            // padding so the last row can still be scrolled clear of it.
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = WindowInsets.navigationBars.asPaddingValues(),
            ) {
                items(
                    items = uiState.items,
                    key = { it.key },
                    contentType = { entry ->
                        when (entry) {
                            is ThreadListItem.Thread -> "thread"
                            is ThreadListItem.SettledShelf -> "settled-shelf"
                        }
                    },
                ) { entry ->
                    when (entry) {
                        is ThreadListItem.Thread -> {
                            SwipeableThreadRow(
                                row = entry.row,
                                coordinator = swipeCoordinator,
                                onOpenThread = onOpenThread,
                                onSettleThread = onSettleThread,
                            )
                            // Outside the swipe container: the divider belongs
                            // to the list, and sliding it with the row would
                            // tear the seam between neighbours.
                            HorizontalDivider()
                        }

                        is ThreadListItem.SettledShelf -> SettledShelfHeader(
                            count = entry.count,
                            expanded = entry.expanded,
                            onClick = onToggleSettledShelf,
                        )
                    }
                }
            }
        }
    }
}

/**
 * A thread row with its swipe action, or the bare row when there is none.
 *
 * The container is omitted rather than disabled for threads that cannot be
 * settled — an unsupported server, a thread already pinned settled, or one that
 * is working or waiting on the user. Disabling a revealed row would leave it
 * stranded open the moment a turn started.
 */
@Composable
private fun SwipeableThreadRow(
    row: ThreadRow,
    coordinator: SwipeRevealCoordinator,
    onOpenThread: (EnvironmentId, ThreadId) -> Unit,
    onSettleThread: (EnvironmentId, ThreadId) -> Unit,
) {
    if (!row.canSettle) {
        ThreadRowItem(
            row = row,
            onClick = { onOpenThread(row.environmentId, row.thread.id) },
        )
        return
    }

    SwipeReveal(
        coordinator = coordinator,
        actions = {
            SwipeRevealAction(
                icon = KodeIcons.Check,
                label = "Settle",
                contentDescription = "Settle " + row.thread.title,
                // The circle takes the accent rather than a bespoke green:
                // settling is filing work away, not a destructive act.
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                onClick = { onSettleThread(row.environmentId, row.thread.id) },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        },
    ) {
        ThreadRowItem(
            row = row,
            onClick = { onOpenThread(row.environmentId, row.thread.id) },
        )
    }
}

/**
 * Two row idioms, following T3 Code's thread list.
 *
 * A [ThreadRowVariant.Card] row is what the inbox is for, and spends three
 * lines on it: project and state, the title, then where it is running. A
 * [ThreadRowVariant.Slim] row is settled history, and keeps only the title and
 * its age — the point of the shelf is that finished work stops competing for
 * attention with work that still wants it.
 *
 * Neither uses a tonal container. State reads through the coloured status label
 * and the text hierarchy, which is what lets the list stay flat and scannable.
 */
@Composable
private fun ThreadRowItem(row: ThreadRow, onClick: () -> Unit) {
    // A revealed row swallows its own tap, so a swipe that overshot into a
    // press dismisses the actions instead of opening the thread.
    val rowClick = rememberSwipeRevealRowClick(onClick)
    val modifier = Modifier
        .fillMaxWidth()
        // Opaque on purpose: the action panel sits behind the row, and a
        // transparent row would show it through instead of revealing it.
        .background(MaterialTheme.colorScheme.background)
        .clickable(onClick = rowClick)

    when (row.variant) {
        ThreadRowVariant.Card -> ThreadCardRow(row, modifier)
        ThreadRowVariant.Slim -> ThreadSlimRow(row, modifier)
    }
}

@Composable
private fun ThreadCardRow(row: ThreadRow, modifier: Modifier) {
    val colors = KodeTheme.colors
    val statusLabel = row.status.label()

    Column(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (row.projectTitle != null) {
                ProjectGlyph(tint = colors.muted)
                Text(
                    text = row.projectTitle,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                // The status still belongs at the trailing edge, so a spacer
                // stands in for the title's weight.
                Spacer(Modifier.weight(1f))
            }
            // The status label takes the slot the age would occupy: a thread
            // that is working or waiting says so instead of saying when.
            Text(
                text = statusLabel ?: row.timeLabel,
                style = MaterialTheme.typography.labelMedium,
                color = row.status.color(colors) ?: colors.muted,
            )
        }

        Text(
            text = row.thread.title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            // Two lines: thread titles are written as sentences, and the second
            // line is usually where the subject of the work actually appears.
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        val meta = threadMetaLine(row, colors)
        if (meta != null || row.providerDriver != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (meta != null) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (row.errorText != null) colors.danger else colors.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                ProviderMark(
                    driver = row.providerDriver,
                    contentDescription = null,
                    // Recessed: which agent is running the thread is context,
                    // not the thing the row is about.
                    modifier = Modifier.size(14.dp).alpha(0.6f),
                )
            }
        }
    }
}

@Composable
private fun ThreadSlimRow(row: ThreadRow, modifier: Modifier) {
    val colors = KodeTheme.colors
    Row(
        modifier = modifier
            .heightIn(min = 44.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Settled history recedes rather than disappears: the project glyph is
        // dimmed further than the muted text it sits next to.
        if (row.projectTitle != null) ProjectGlyph(tint = colors.muted.copy(alpha = 0.4f))
        Text(
            text = row.thread.title,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = row.timeLabel,
            style = MaterialTheme.typography.bodySmall,
            // Monospaced so a column of ages does not shuffle sideways as the
            // digits change.
            fontFamily = KodeMarkdownSizes.monospace,
            color = colors.muted,
        )
    }
}

/** The folder glyph standing in for a project, at T3 Code's 15px. */
@Composable
private fun ProjectGlyph(tint: Color) {
    Icon(
        imageVector = KodeIcons.Folder,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(15.dp),
    )
}

/**
 * A card row's third line: where the thread is, or why it broke.
 *
 * `branch · machine` share one truncating line, with the machine last so a
 * tight fit cuts the label that repeats down the list rather than the branch,
 * and so a non-git project's row is still filled by the machine alone. A failed
 * thread gives the line over to the error instead — what went wrong is more use
 * than where it went wrong.
 */
private fun threadMetaLine(row: ThreadRow, colors: KodeExtendedColors): AnnotatedString? {
    row.errorText?.let { return AnnotatedString(it) }

    val branch = row.thread.branch
    val environment = row.environmentLabel
    if (branch == null && environment == null) return null

    return buildAnnotatedString {
        if (branch != null) {
            withStyle(SpanStyle(fontFamily = KodeMarkdownSizes.monospace)) { append(branch) }
        }
        if (branch != null && environment != null) append("  ·  ")
        if (environment != null) {
            withStyle(SpanStyle(color = colors.muted)) { append(environment) }
        }
    }
}

/** The label a state earns. [ThreadRowStatus.Ready] earns none — see the enum. */
private fun ThreadRowStatus.label(): String? = when (this) {
    ThreadRowStatus.Approval -> "Approval"
    ThreadRowStatus.Input -> "Input"
    ThreadRowStatus.Working -> "Working"
    ThreadRowStatus.Failed -> "Failed"
    ThreadRowStatus.Ready -> null
}

/**
 * The Kanagawa tone for a state, or null when it takes the muted default.
 *
 * T3 Code reserves colour for "act now", "in motion" and "broken", and the
 * hues here are that convention mapped onto the app's own palette rather than
 * its Tailwind ones, so a status reads like the rest of Kode.
 */
private fun ThreadRowStatus.color(colors: KodeExtendedColors): Color? = when (this) {
    ThreadRowStatus.Approval -> colors.warning
    ThreadRowStatus.Input -> colors.thinkingAccent
    ThreadRowStatus.Working -> colors.info
    ThreadRowStatus.Failed -> colors.danger
    ThreadRowStatus.Ready -> null
}

/**
 * Divides the inbox from finished work.
 *
 * Collapsed by default: settled threads are history, and the point of the shelf
 * is that they stop competing for attention with threads that still want it.
 */
@Composable
private fun SettledShelfHeader(count: Int, expanded: Boolean, onClick: () -> Unit) {
    val colors = KodeTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = KodeIcons.ChevronDown,
            contentDescription = if (expanded) "Hide settled threads" else "Show settled threads",
            tint = colors.muted,
            modifier = Modifier
                .size(16.dp)
                .graphicsLayer { rotationZ = if (expanded) 180f else 0f },
        )
        Text(
            text = "Settled",
            style = MaterialTheme.typography.titleSmall,
            color = colors.muted,
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.bodySmall,
            color = colors.muted,
        )
    }
    HorizontalDivider(color = colors.divider)
}

@Composable
private fun CenteredMessage(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
