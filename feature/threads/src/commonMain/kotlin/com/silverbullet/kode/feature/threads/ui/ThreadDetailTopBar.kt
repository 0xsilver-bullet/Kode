package com.silverbullet.kode.feature.threads.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.silverbullet.kode.core.designsystem.KodeIcons
import com.silverbullet.kode.core.designsystem.KodeTheme
import com.silverbullet.kode.core.model.EnvironmentId
import com.silverbullet.kode.core.model.ThreadId
import com.silverbullet.kode.feature.threads.presentation.ThreadDetailViewModel
import com.silverbullet.kode.feature.threads.ui.git.GitOverviewSheet
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * The thread screen's app bar: the thread's title, with the project (and, when
 * it disambiguates, the environment) beneath it — T3 Code's mobile header.
 *
 * It lives in this module, not in the navigation graph, because the title is
 * thread state and only the view model has it. Resolving that view model here
 * costs nothing extra: the bar and the screen share one `ViewModelStoreOwner`
 * (the nav back stack entry), so both get the same instance and the thread is
 * still subscribed to exactly once.
 *
 * The leading git control opens the [GitOverviewSheet], mirroring t3code's
 * top-bar git icon — always rendered, never gated on repo state; the sheet
 * itself explains an unavailable repository.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadDetailTopBar(
    environmentId: EnvironmentId,
    threadId: ThreadId,
    modifier: Modifier = Modifier,
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
    actions: @Composable RowScope.() -> Unit = {},
    onOpenReview: () -> Unit = {},
    viewModel: ThreadDetailViewModel = koinViewModel { parametersOf(environmentId, threadId) },
) {
    val header by viewModel.header.collectAsStateWithLifecycle()
    var gitSheetOpen by remember { mutableStateOf(false) }

    TopAppBar(
        title = {
            // Both lines are capped at one: a generated title runs to 72
            // characters, and letting it wrap would push the subtitle out of
            // the bar's fixed height.
            Column {
                Text(
                    text = header.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                header.subtitle?.let { subtitle ->
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = KodeTheme.colors.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = { gitSheetOpen = true }) {
                Icon(KodeIcons.GitBranch, contentDescription = "Git actions")
            }
        },
        actions = actions,
        windowInsets = windowInsets,
        modifier = modifier,
    )

    if (gitSheetOpen) {
        // Collected only while the sheet shows, so the VCS status stream opens
        // on demand and idles out after dismissal (`WhileSubscribed`).
        val status by viewModel.git.status.collectAsStateWithLifecycle()
        val action by viewModel.git.action.collectAsStateWithLifecycle()
        val branch by viewModel.threadBranch.collectAsStateWithLifecycle()
        val worktreePath by viewModel.threadWorktreePath.collectAsStateWithLifecycle()

        GitOverviewSheet(
            status = status,
            busy = action.isRunning,
            fallbackBranch = branch,
            worktreePath = worktreePath,
            onRefresh = viewModel.git::refreshStatus,
            onRunAction = viewModel.git::runAction,
            onRunOnNewFeatureBranch = viewModel.git::runActionOnNewFeatureBranch,
            onPull = viewModel.git::pullLatest,
            onOpenReview = onOpenReview,
            onDismiss = { gitSheetOpen = false },
        )
    }
}
