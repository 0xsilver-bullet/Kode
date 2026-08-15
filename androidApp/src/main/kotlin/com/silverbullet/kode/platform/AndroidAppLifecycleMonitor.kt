package com.silverbullet.kode.platform

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.silverbullet.kode.core.common.AppActivation
import com.silverbullet.kode.core.common.AppLifecycleMonitor
import com.silverbullet.kode.core.common.MEANINGFUL_SUSPENSION_MILLIS
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers

/**
 * Process-wide foreground transitions.
 *
 * `ProcessLifecycleOwner` rather than an Activity: it does not fire on rotation
 * or on moving between activities, so the supervisor is not asked to reconnect
 * for events that never touched the socket.
 *
 * Classifies each return by how long the app was away — see [AppActivation].
 */
class AndroidAppLifecycleMonitor(
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : AppLifecycleMonitor {

    override val activations: Flow<AppActivation> = callbackFlow {
        var backgroundedAt: TimeMark? = null

        val observer = object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                backgroundedAt = timeSource.markNow()
            }

            override fun onStart(owner: LifecycleOwner) {
                val away = backgroundedAt
                backgroundedAt = null

                // A first start has no prior background period and is not an
                // activation at all — the supervisor is already connecting.
                if (away == null) return

                trySend(
                    if (away.elapsedNow().inWholeMilliseconds >= MEANINGFUL_SUSPENSION_MILLIS) {
                        AppActivation.ResumedAfterSuspension
                    } else {
                        AppActivation.Resumed
                    },
                )
            }
        }

        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        lifecycle.addObserver(observer)
        awaitClose { lifecycle.removeObserver(observer) }
        // Lifecycle observers must be added and removed on the main thread.
    }.flowOn(Dispatchers.Main.immediate)
}
