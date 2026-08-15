package com.silverbullet.kode.core.common

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Whether the device believes it has a usable network.
 *
 * The supervisor uses this to avoid burning retry attempts against a dead
 * radio: while offline it releases the session and waits for a signal rather
 * than running its backoff ladder.
 */
interface NetworkMonitor {
    val isOnline: Flow<Boolean>
}

/**
 * App foreground transitions.
 *
 * The distinction matters, and is taken from `supervisor.ts`: a *plain* return
 * to the foreground should probe the existing socket, because a healthy session
 * survives foregrounding and tearing it down would only add latency. A return
 * after a **meaningful** background suspension should replace the session
 * outright, because the OS may have silently killed the socket while the
 * process was frozen — probing that would just wait for a timeout.
 */
interface AppLifecycleMonitor {
    val activations: Flow<AppActivation>
}

enum class AppActivation {
    /** Foregrounded after a short absence. Probe the existing session. */
    Resumed,

    /** Foregrounded after long enough that the socket is probably dead. */
    ResumedAfterSuspension,
}

/**
 * How long in the background counts as "meaningful".
 *
 * Below this the socket has almost certainly survived; above it, Doze and
 * network-stack teardown make a silent kill likely enough that probing is
 * slower than reconnecting.
 */
const val MEANINGFUL_SUSPENSION_MILLIS: Long = 30_000

/** Used on platforms that do not supply a real monitor yet, such as iOS. */
class AlwaysOnlineNetworkMonitor : NetworkMonitor {
    override val isOnline: Flow<Boolean> = flowOf(true)
}

/** Emits nothing: the supervisor then relies purely on transport failures. */
class NoOpAppLifecycleMonitor : AppLifecycleMonitor {
    override val activations: Flow<AppActivation> = flowOf()
}
