package com.silverbullet.kode.core.session

import com.silverbullet.kode.core.common.AlwaysOnlineNetworkMonitor
import com.silverbullet.kode.core.common.AppActivation
import com.silverbullet.kode.core.common.MEANINGFUL_SUSPENSION_MILLIS
import com.silverbullet.kode.core.common.NoOpAppLifecycleMonitor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * The supervisor's own state machine is exercised through its inputs; these
 * cover the contracts those inputs must satisfy.
 */
class ConnectivityTest {

    @Test
    fun `the default network monitor reports online`() = runTest {
        // Hosts without a real monitor must not be stuck offline forever.
        assertTrue(AlwaysOnlineNetworkMonitor().isOnline.first())
    }

    @Test
    fun `the no-op lifecycle monitor emits nothing`() = runTest {
        // The supervisor then relies purely on transport failures, which is a
        // degraded but correct mode.
        assertEquals(emptyList(), NoOpAppLifecycleMonitor().activations.toList())
    }

    @Test
    fun `the suspension threshold separates a glance from a real absence`() {
        // Below the threshold a socket almost certainly survived, so probing is
        // cheaper than reconnecting; above it, reconnecting is cheaper than
        // waiting for a probe to time out.
        assertTrue(MEANINGFUL_SUSPENSION_MILLIS > 0)
        // Matches MOBILE_BACKGROUND_RECONNECT_AFTER_MS in t3code.
        assertEquals(10_000L, MEANINGFUL_SUSPENSION_MILLIS)
    }

    @Test
    fun `activation kinds are distinct`() {
        // Collapsing these would lose the probe-vs-replace decision entirely.
        assertEquals(2, AppActivation.entries.size)
    }
}
