package com.silverbullet.kode.feature.threads.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.silverbullet.kode.core.designsystem.KodeIcons
import com.silverbullet.kode.core.designsystem.KodeTheme
import com.silverbullet.kode.core.model.EnvironmentId
import com.silverbullet.kode.core.model.ThreadId
import com.silverbullet.kode.feature.threads.presentation.ReviewSectionUi
import com.silverbullet.kode.feature.threads.presentation.ReviewUiState
import com.silverbullet.kode.feature.threads.presentation.ReviewViewModel
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Review Changes — the port of t3code's `ReviewSheet`.
 *
 * The chrome (app bar, section pills, truncation banner, loading/error/empty
 * states) is ordinary Compose; the diff body itself is [DiffCanvas], a single
 * draw-phase canvas with its own virtualization and scrolling, because a lazy
 * list of per-row composables cannot survive a fling through thousands of
 * monospace rows. See DiffCanvas.kt for the architecture notes.
 */
@Composable
fun ReviewRoute(
    environmentId: EnvironmentId,
    threadId: ThreadId,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ReviewViewModel = koinViewModel { parametersOf(environmentId, threadId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    ReviewScreen(
        state = state,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onSelectSection = viewModel::selectSection,
        onToggleFile = viewModel::toggleFileCollapsed,
        onToggleViewed = viewModel::toggleFileViewed,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    state: ReviewUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSelectSection: (String) -> Unit,
    onToggleFile: (String) -> Unit,
    onToggleViewed: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Review changes") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(KodeIcons.ChevronDown, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(KodeIcons.Refresh, contentDescription = "Refresh diff")
                    }
                },
                windowInsets = TopAppBarDefaults.windowInsets,
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.sections.isNotEmpty()) {
                SectionPills(
                    sections = state.sections,
                    selectedId = state.selectedId,
                    onSelect = onSelectSection,
                )
            }

            if (state.truncated) {
                Text(
                    text = "Diff output hit the server size cap. Showing the available excerpt.",
                    style = MaterialTheme.typography.bodySmall,
                    color = KodeTheme.colors.warning,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }

                    state.error != null -> CenteredMessage(state.error)
                    state.emptyMessage != null -> CenteredMessage(state.emptyMessage)
                    else -> DiffCanvas(
                        rows = state.rows,
                        collapsedFileIds = state.collapsedFileIds,
                        viewedFileIds = state.viewedFileIds,
                        maxLineLength = state.maxLineLength,
                        onToggleFile = onToggleFile,
                        onToggleViewed = onToggleViewed,
                        modifier = Modifier.fillMaxSize(),
                        resetKey = state.selectedId,
                    )
                }
            }
        }
    }
}

@Composable
private fun CenteredMessage(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = KodeTheme.colors.muted,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SectionPills(
    sections: List<ReviewSectionUi>,
    selectedId: String?,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        sections.forEach { section ->
            val selected = section.id == selectedId
            Column(
                modifier = Modifier
                    .background(
                        if (selected) {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        RoundedCornerShape(12.dp),
                    )
                    .clickable { onSelect(section.id) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = section.title,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
                section.subtitle?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = KodeTheme.colors.muted,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
