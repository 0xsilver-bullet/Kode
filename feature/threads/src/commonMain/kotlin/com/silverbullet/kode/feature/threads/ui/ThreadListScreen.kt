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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.silverbullet.kode.core.model.ThreadId
import com.silverbullet.kode.feature.threads.presentation.ThreadListUiState
import com.silverbullet.kode.feature.threads.presentation.ThreadListViewModel
import com.silverbullet.kode.feature.threads.presentation.ThreadRow
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun ThreadListRoute(
    onOpenThread: (ThreadId) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ThreadListViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ThreadListScreen(uiState = uiState, onOpenThread = onOpenThread, modifier = modifier)
}

@Composable
fun ThreadListScreen(
    uiState: ThreadListUiState,
    onOpenThread: (ThreadId) -> Unit,
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

            uiState.rows.isEmpty() -> CenteredMessage {
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
                    items = uiState.rows,
                    key = { it.thread.id.value },
                    contentType = { "thread" },
                ) { row ->
                    ThreadListItem(row = row, onClick = { onOpenThread(row.thread.id) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ThreadListItem(row: ThreadRow, onClick: () -> Unit) {
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

/** Marks a thread waiting on an approval or user input. */
@Composable
private fun StatusDot(color: Color) {
    Box(modifier = Modifier.size(8.dp).background(color, CircleShape))
}

@Composable
private fun CenteredMessage(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
