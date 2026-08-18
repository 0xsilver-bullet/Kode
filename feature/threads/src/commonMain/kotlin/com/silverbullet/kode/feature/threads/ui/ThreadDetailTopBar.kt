package com.silverbullet.kode.feature.threads.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.silverbullet.kode.core.designsystem.KodeTheme
import com.silverbullet.kode.core.model.EnvironmentId
import com.silverbullet.kode.core.model.ThreadId
import com.silverbullet.kode.feature.threads.presentation.ThreadDetailViewModel
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
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadDetailTopBar(
    environmentId: EnvironmentId,
    threadId: ThreadId,
    modifier: Modifier = Modifier,
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
    actions: @Composable RowScope.() -> Unit = {},
    viewModel: ThreadDetailViewModel = koinViewModel { parametersOf(environmentId, threadId) },
) {
    val header by viewModel.header.collectAsStateWithLifecycle()

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
        actions = actions,
        windowInsets = windowInsets,
        modifier = modifier,
    )
}
