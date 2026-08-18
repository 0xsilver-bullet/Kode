package com.silverbullet.kode

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.ktor3.KtorNetworkFetcherFactory
import io.ktor.client.HttpClient
import com.silverbullet.kode.core.designsystem.KodeIcons
import com.silverbullet.kode.core.designsystem.KodeTheme
import com.silverbullet.kode.core.model.EnvironmentId
import com.silverbullet.kode.core.model.ThreadId
import com.silverbullet.kode.core.session.ConnectionState
import com.silverbullet.kode.core.session.EnvironmentFleet
import com.silverbullet.kode.feature.connection.ui.AddEnvironmentRoute
import com.silverbullet.kode.feature.connection.ui.ConnectionRoute
import com.silverbullet.kode.feature.connection.ui.EnvironmentsRoute
import com.silverbullet.kode.feature.connection.ui.SettingsRoute
import com.silverbullet.kode.feature.threads.ui.NewThreadRoute
import com.silverbullet.kode.feature.threads.ui.ThreadDetailRoute
import com.silverbullet.kode.feature.threads.ui.ThreadDetailTopBar
import com.silverbullet.kode.feature.threads.ui.ThreadListRoute
import com.silverbullet.kode.feature.voice.ui.VoicePromptEntry
import com.silverbullet.kode.feature.voice.ui.VoiceSettingsRoute
import com.silverbullet.kode.voice.contract.VoiceThreadMessage
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

/** Type-safe navigation destinations. */
@Serializable
private data object ThreadListDestination

@Serializable
private data class ThreadDetailDestination(val environmentId: String, val threadId: String)

@Serializable
private data object NewThreadDestination

@Serializable
private data object SettingsDestination

@Serializable
private data object SettingsEnvironmentsDestination

@Serializable
private data object AddEnvironmentDestination

@Serializable
private data object VoiceSettingsDestination

/**
 * The app shell.
 *
 * The root [Surface] deliberately fills the whole window with **no** inset
 * padding, so the theme background paints behind the status and navigation
 * bars. Padding here instead is what left the bars showing the raw window
 * background. Insets are applied per destination, by the `Scaffold` that owns
 * each screen.
 *
 * Onboarding is not a navigation destination: it replaces the whole UI while
 * the environment catalog is empty, because nothing else in the app can
 * function without one. While the catalog is still being read from disk
 * (`null`), neither is shown — flashing the pairing form at every launch would
 * be worse than a blank frame.
 */
@Composable
@Preview
fun App() {
    // Coil's singleton loader needs a network fetcher wired to a Ktor engine;
    // without one, every asset URL fails to load with no visible error. Set at
    // the root so both the composer's thumbnails and the feed's sent images
    // share one loader — and therefore one memory and disk cache.
    // Resolved outside the factory lambda: that lambda is not composable, and
    // reusing the app's single client keeps attachment fetches on the same
    // connection pool as the RPC socket. It carries no auth plugin, which is
    // what makes it safe here — asset URLs are signed, not bearer-authorized.
    val httpClient = koinInject<HttpClient>()
    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .components { add(KtorNetworkFetcherFactory(httpClient = { httpClient })) }
            .build()
    }

    KodeTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            val fleet = koinInject<EnvironmentFleet>()
            val environments by fleet.environments.collectAsStateWithLifecycle()

            when {
                environments == null -> Unit

                environments.orEmpty().isEmpty() ->
                    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { padding ->
                        ConnectionRoute(modifier = Modifier.fillMaxSize().padding(padding))
                    }

                else -> KodeNavHost()
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
                        actions = {
                            ConnectionBadge()
                            IconButton(onClick = { navController.navigate(SettingsDestination) }) {
                                Icon(KodeIcons.Gear, contentDescription = "Settings")
                            }
                        },
                        windowInsets = TopAppBarDefaults.windowInsets,
                    )
                },
                // The bar consumes the top inset; the list consumes the bottom
                // one itself, as content padding, so rows scroll *under* the
                // navigation bar instead of stopping short of it.
                floatingActionButton = {
                    FloatingActionButton(
                        onClick = { navController.navigate(NewThreadDestination) },
                    ) {
                        Icon(KodeIcons.Plus, contentDescription = "New thread")
                    }
                },
                contentWindowInsets = WindowInsets.safeDrawing
                    .only(WindowInsetsSides.Horizontal),
            ) { padding ->
                ThreadListRoute(
                    onOpenThread = { environmentId, threadId ->
                        navController.navigate(
                            ThreadDetailDestination(environmentId.value, threadId.value),
                        )
                    },
                    modifier = Modifier.fillMaxSize().padding(padding),
                )
            }
        }

        composable<NewThreadDestination> {
            SubScreenScaffold(navController = navController, title = "New thread") { padding ->
                NewThreadRoute(
                    onCreated = { environmentId, threadId ->
                        // Replace rather than stack: returning from the thread
                        // should land on the list, not back on a spent form.
                        navController.popBackStack()
                        navController.navigate(
                            ThreadDetailDestination(environmentId.value, threadId.value),
                        )
                    },
                    modifier = Modifier.fillMaxSize().padding(padding),
                )
            }
        }

        composable<ThreadDetailDestination> { entry ->
            val route = entry.toRoute<ThreadDetailDestination>()
            Scaffold(
                topBar = {
                    // The bar reads the thread's own title, so it belongs to the
                    // feature that owns that state rather than to this graph.
                    ThreadDetailTopBar(
                        environmentId = EnvironmentId(route.environmentId),
                        threadId = ThreadId(route.threadId),
                        actions = { ConnectionBadge() },
                    )
                },
                // Same reasoning, plus the composer has to react to the IME,
                // which it can only do if the bottom inset is not consumed here.
                contentWindowInsets = WindowInsets.safeDrawing
                    .only(WindowInsetsSides.Horizontal),
            ) { padding ->
                ThreadDetailRoute(
                    environmentId = EnvironmentId(route.environmentId),
                    threadId = ThreadId(route.threadId),
                    modifier = Modifier.fillMaxSize().padding(padding),
                    // The adapter between the two feature modules: threads exposes a
                    // primitive context, voice consumes primitives. Neither sees the other.
                    voiceComposerSlot = { context ->
                        VoicePromptEntry(
                            environmentId = context.environmentId,
                            threadKey = context.threadId.value,
                            projectDir = context.projectDir,
                            recentMessages = {
                                context.recentMessages().map { (role, text) ->
                                    VoiceThreadMessage(role = role, text = text)
                                }
                            },
                            attachmentPreviews = context.attachmentPreviews,
                            sendPrompt = context.sendPrompt,
                        )
                    },
                )
            }
        }

        composable<SettingsDestination> {
            SubScreenScaffold(navController = navController, title = "Settings") { padding ->
                SettingsRoute(
                    onOpenEnvironments = {
                        navController.navigate(SettingsEnvironmentsDestination)
                    },
                    onOpenVoice = {
                        navController.navigate(VoiceSettingsDestination)
                    },
                    modifier = Modifier.fillMaxSize().padding(padding),
                )
            }
        }

        composable<SettingsEnvironmentsDestination> {
            SubScreenScaffold(
                navController = navController,
                title = "Environments",
                actions = {
                    IconButton(
                        onClick = { navController.navigate(AddEnvironmentDestination) },
                    ) {
                        Icon(KodeIcons.Plus, contentDescription = "Add environment")
                    }
                },
            ) { padding ->
                EnvironmentsRoute(modifier = Modifier.fillMaxSize().padding(padding))
            }
        }

        composable<AddEnvironmentDestination> {
            SubScreenScaffold(navController = navController, title = "Add Environment") { padding ->
                AddEnvironmentRoute(
                    onAdded = { navController.popBackStack() },
                    modifier = Modifier.fillMaxSize().padding(padding),
                )
            }
        }

        composable<VoiceSettingsDestination> {
            SubScreenScaffold(navController = navController, title = "Voice") { padding ->
                VoiceSettingsRoute(modifier = Modifier.fillMaxSize().padding(padding))
            }
        }
    }
}

/** The shared chrome for pushed screens: a titled bar with a back affordance. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubScreenScaffold(
    navController: NavHostController,
    title: String,
    actions: @Composable () -> Unit = {},
    content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(KodeIcons.ChevronDown, contentDescription = "Back")
                    }
                },
                actions = { actions() },
                windowInsets = TopAppBarDefaults.windowInsets,
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing
            .only(WindowInsetsSides.Horizontal),
        content = content,
    )
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
    val fleet = koinInject<EnvironmentFleet>()
    val environments by fleet.environments.collectAsStateWithLifecycle()
    val handles = environments.orEmpty()
    if (handles.isEmpty()) return

    // Aggregated across the fleet: all good says nothing, anything else says
    // how much of the fleet is reachable.
    val states = handles.map { handle ->
        val state by handle.state.collectAsStateWithLifecycle()
        state
    }
    val connected = states.count { it is ConnectionState.Connected }
    if (connected == handles.size) return

    val label = if (handles.size == 1) {
        when (states.single()) {
            ConnectionState.Connecting -> "Connecting…"
            ConnectionState.Offline -> "Offline"
            is ConnectionState.Reconnecting -> "Reconnecting…"
            is ConnectionState.Blocked -> "Disconnected"
            is ConnectionState.Connected -> return
        }
    } else {
        "$connected/${handles.size} connected"
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
