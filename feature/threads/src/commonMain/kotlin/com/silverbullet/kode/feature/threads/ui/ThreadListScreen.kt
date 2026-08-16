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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
        modifier = modifier,
    )
}

@Composable
fun ThreadListScreen(
    uiState: ThreadListUiState,
    onOpenThread: (EnvironmentId, ThreadId) -> Unit,
    onToggleSettledShelf: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
                            ThreadRowItem(
                                row = entry.row,
                                onClick = {
                                    onOpenThread(entry.row.environmentId, entry.row.thread.id)
                                },
                            )
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

@Composable
private fun ThreadRowItem(row: ThreadRow, onClick: () -> Unit) {
    val thread = row.thread

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
