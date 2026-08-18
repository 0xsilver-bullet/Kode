package com.silverbullet.kode.voiceserver

import com.silverbullet.kode.voiceserver.auth.ClientTokenStore
import com.silverbullet.kode.voiceserver.auth.PairingCodeRegistry
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthStoresTest {

    @Test
    fun `pairing codes are one-time`() {
        val registry = PairingCodeRegistry()
        val code = registry.mint()
        assertTrue(registry.redeem(code))
        assertFalse(registry.redeem(code))
    }

    @Test
    fun `pairing codes expire`() {
        var now = 0L
        val registry = PairingCodeRegistry(ttlMillis = 1_000, now = { now })
        val code = registry.mint()
        now = 2_000
        assertFalse(registry.redeem(code))
    }

    @Test
    fun `unknown codes are rejected`() {
        assertFalse(PairingCodeRegistry().redeem("NOPE"))
    }

    @Test
    fun `tokens survive a store restart and tampering fails`() {
        val dir = Files.createTempDirectory("kode-voice-test")
        val file = dir.resolve("clients.json")

        val token = ClientTokenStore(file).issueToken(label = "Pixel", os = "android")
        assertTrue(token.startsWith("kv_"))

        val reloaded = ClientTokenStore(file)
        assertTrue(reloaded.isValid(token))
        assertFalse(reloaded.isValid(token.dropLast(1) + "x"))
        assertFalse(reloaded.isValid("kv_forged"))
        assertEquals(1, reloaded.clients().size)
        // Only a hash is on disk.
        assertFalse(Files.readString(file).contains(token))
    }
}
