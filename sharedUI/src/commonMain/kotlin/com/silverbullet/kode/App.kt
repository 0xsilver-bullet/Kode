package com.silverbullet.kode

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.silverbullet.kode.core.designsystem.KodeTheme
import com.silverbullet.kode.core.model.ThreadId
import com.silverbullet.kode.core.session.ConnectionState
import com.silverbullet.kode.core.session.EnvironmentSupervisor
import com.silverbullet.kode.feature.connection.ui.ConnectionRoute
import com.silverbullet.kode.feature.threads.ui.ThreadDetailRoute
import com.silverbullet.kode.feature.threads.ui.ThreadListRoute
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

/** Type-safe navigation destinations. */
@Serializable
private data object ThreadListDestination

@Serializable
private data class ThreadDetailDestination(val threadId: String)

/**
 * The app shell.
 *
 * The root [Surface] deliberately fills the whole window with **no** inset
 * padding, so the theme background paints behind the status and navigation
 * bars. Padding here instead is what left the bars showing the raw window
 * background. Insets are applied per destination, by the `Scaffold` that owns
 * each screen.
 *
 * Pairing is not a navigation destination: it replaces the whole UI while there
 * is no environment, because nothing else in the app can function without one.
 */
@Composable
@Preview
fun App() {
    KodeTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            val supervisor = koinInject<EnvironmentSupervisor>()
            val connection by supervisor.state.collectAsStateWithLifecycle()

            if (connection is ConnectionState.Unpaired) {
                Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { padding ->
                    ConnectionRoute(modifier = Modifier.fillMaxSize().padding(padding))
                }
            } else {
                KodeNavHost()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KodeNavHost() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = ThreadListDestination,
        modifier = Modifier.fillMaxSize(),
    ) {
        composable<ThreadListDestination> {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Threads") },
                        actions = { ConnectionBadge() },
                        windowInsets = TopAppBarDefaults.windowInsets,
                    )
                },
                // The bar consumes the top inset; the list consumes the bottom
                // one itself, as content padding, so rows scroll *under* the
                // navigation bar instead of stopping short of it.
                contentWindowInsets = WindowInsets.safeDrawing
                    .only(WindowInsetsSides.Horizontal),
            ) { padding ->
                ThreadListRoute(
                    onOpenThread = { navController.navigate(ThreadDetailDestination(it.value)) },
                    modifier = Modifier.fillMaxSize().padding(padding),
                )
            }
        }

        composable<ThreadDetailDestination> { entry ->
            val threadId = ThreadId(entry.toRoute<ThreadDetailDestination>().threadId)
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Thread") },
                        actions = { ConnectionBadge() },
                        windowInsets = TopAppBarDefaults.windowInsets,
                    )
                },
                // Same reasoning, plus the composer has to react to the IME,
                // which it can only do if the bottom inset is not consumed here.
                contentWindowInsets = WindowInsets.safeDrawing
                    .only(WindowInsetsSides.Horizontal),
            ) { padding ->
                ThreadDetailRoute(
                    threadId = threadId,
                    modifier = Modifier.fillMaxSize().padding(padding),
                )
            }
        }
    }
}

/**
 * Surfaces connection health without stealing the screen: the thread list stays
 * readable while a reconnect is in flight.
 */
@Composable
private fun ConnectionBadge() {
    // Collected here rather than passed down: reading it at the App root meant
    // every connection change recomposed the whole NavHost, and the captured
    // value could go stale because navigation-compose remembers the graph
    // independently of the builder lambda.
    val supervisor = koinInject<EnvironmentSupervisor>()
    val connection by supervisor.state.collectAsStateWithLifecycle()

    val label = when (connection) {
        ConnectionState.Connecting -> "Connecting…"
        is ConnectionState.Reconnecting -> "Reconnecting…"
        is ConnectionState.Blocked -> "Disconnected"
        is ConnectionState.Connected, ConnectionState.Unpaired -> return
    }

    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(end = 12.dp),
    )
}
