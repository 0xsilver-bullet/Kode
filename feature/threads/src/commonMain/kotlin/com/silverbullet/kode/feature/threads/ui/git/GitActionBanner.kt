package com.silverbullet.kode.feature.threads.ui.git

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.silverbullet.kode.core.designsystem.KodeIcons
import com.silverbullet.kode.core.designsystem.KodeTheme
import com.silverbullet.kode.feature.threads.presentation.GitActionUiState

/**
 * The floating progress/result banner for git actions — Kode's take on
 * t3code's `GitActionProgressOverlay`. Shown at the top of the thread screen
 * while a stacked action runs, then as its success/error toast for five
 * seconds. Tapping a success with a PR opens it; tapping anything else
 * dismisses it.
 */
@Composable
fun GitActionBanner(
    state: GitActionUiState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.isVisible) return
    val colors = KodeTheme.colors
    val uriHandler = LocalUriHandler.current
    val notice = state.notice

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier
                .clickable {
                    val url = notice?.prUrl
                    if (url != null) uriHandler.openUri(url) else onDismiss()
                }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                state.isRunning -> CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = colors.toolAccent,
                )

                notice?.success == true -> Icon(
                    imageVector = KodeIcons.Check,
                    contentDescription = null,
                    tint = colors.success,
                    modifier = Modifier.size(16.dp),
                )

                else -> Icon(
                    imageVector = KodeIcons.Warning,
                    contentDescription = null,
                    tint = colors.danger,
                    modifier = Modifier.size(16.dp),
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.runningLabel ?: notice?.title.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val detail = if (state.isRunning) state.runningDetail else notice?.description
                detail?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (notice?.prUrl != null) {
                Icon(
                    imageVector = KodeIcons.ArrowUpRight,
                    contentDescription = "Open pull request",
                    tint = colors.link,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
