package com.silverbullet.kode.voiceserver.auth

import java.security.SecureRandom

/**
 * One-time pairing codes, in-memory only: restarting the server invalidates outstanding
 * codes, which is the safe failure mode. Same unambiguous alphabet t3 uses (no 0/O/1/I).
 */
class PairingCodeRegistry(
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val DEFAULT_TTL_MILLIS: Long = 15 * 60 * 1000L
        private const val ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
        private const val CODE_LENGTH = 12
    }

    private val random = SecureRandom()
    private val active = HashMap<String, Long>()

    @Synchronized
    fun mint(): String {
        prune()
        val code = buildString(CODE_LENGTH) {
            repeat(CODE_LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        }
        active[code] = now() + ttlMillis
        return code
    }

    /** Consumes the code: a second redeem of the same code always fails. */
    @Synchronized
    fun redeem(code: String): Boolean {
        prune()
        return active.remove(code.trim().uppercase()) != null
    }

    private fun prune() {
        val cutoff = now()
        active.entries.removeAll { it.value <= cutoff }
    }
}
