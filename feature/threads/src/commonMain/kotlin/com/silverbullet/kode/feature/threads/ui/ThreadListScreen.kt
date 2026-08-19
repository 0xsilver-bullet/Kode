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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.silverbullet.kode.core.designsystem.KodeIcons
import com.silverbullet.kode.core.designsystem.KodeTheme
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
import com.silverbullet.kode.feature.threads.presentation.ThreadRow
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

@Composable
private fun ThreadRowItem(row: ThreadRow, onClick: () -> Unit) {
    val thread = row.thread
    // A revealed row swallows its own tap, so a swipe that overshot into a
    // press dismisses the actions instead of opening the thread.
    val rowClick = rememberSwipeRevealRowClick(onClick)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Opaque on purpose: the action panel sits behind the row, and a
            // transparent row would show it through instead of revealing it.
            .background(MaterialTheme.colorScheme.background)
            .clickable(onClick = rowClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = thread.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = row.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        when {
            thread.needsAttention -> StatusDot(MaterialTheme.colorScheme.tertiary)
            thread.isBusy -> CircularProgressIndicator(modifier = Modifier.size(16.dp))
            else -> Unit
        }
    }
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

/** Marks a thread waiting on an approval or user input. */
@Composable
private fun StatusDot(color: Color) {
    Box(modifier = Modifier.size(8.dp).background(color, CircleShape))
}

@Composable
private fun CenteredMessage(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
