package com.silverbullet.kode.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.core.content.getSystemService
import com.silverbullet.kode.core.common.NetworkMonitor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Network availability from `ConnectivityManager`.
 *
 * Reports on `NET_CAPABILITY_VALIDATED` rather than mere availability: a Wi-Fi
 * network that has associated but not yet reached the internet is exactly the
 * case where reconnecting is pointless, and treating it as online would have
 * the supervisor spend its retry ladder against it.
 */
class AndroidNetworkMonitor(context: Context) : NetworkMonitor {

    private val connectivityManager = context.getSystemService<ConnectivityManager>()

    override val isOnline: Flow<Boolean> = callbackFlow {
        val manager = connectivityManager
        if (manager == null) {
            // Without the service we cannot tell; assuming online keeps the
            // supervisor working rather than stalling it forever.
            trySend(true)
            awaitClose { }
            return@callbackFlow
        }

        // Tracked as a set because a device can hold several networks at once
        // (Wi-Fi plus cellular); we are offline only when none of them work.
        val validated = mutableSetOf<Network>()

        fun publish() {
            trySend(validated.isNotEmpty())
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                val usable = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (usable) validated.add(network) else validated.remove(network)
                publish()
            }

            override fun onLost(network: Network) {
                validated.remove(network)
                publish()
            }
        }

        manager.registerNetworkCallback(
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build(),
            callback,
        )

        // Seed from the current network so the first emission does not wait for
        // a change that may never come.
        val active = manager.activeNetwork
        val capabilities = active?.let(manager::getNetworkCapabilities)
        if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) {
            validated.add(active)
        }
        publish()

        awaitClose { manager.unregisterNetworkCallback(callback) }
    }.conflate().distinctUntilChanged()
}
