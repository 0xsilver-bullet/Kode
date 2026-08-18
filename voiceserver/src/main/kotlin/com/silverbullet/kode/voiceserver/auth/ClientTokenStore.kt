package com.silverbullet.kode.voiceserver.auth

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Long-lived client bearer tokens. Only SHA-256 hashes touch disk, so a leaked
 * `clients.json` reveals nothing usable; the plaintext token exists once, in the pairing
 * response. Mirrors how t3 persists only exchanged credentials, never bootstrap ones.
 */
class ClientTokenStore(private val file: Path) {

    @Serializable
    data class ClientRecord(
        val tokenHash: String,
        val label: String,
        val os: String,
        val createdAtEpochMs: Long,
    )

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val random = SecureRandom()
    private val lock = Any()

    fun issueToken(label: String, os: String, now: Long = System.currentTimeMillis()): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        val token = "kv_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        synchronized(lock) {
            val records = readAll() + ClientRecord(hash(token), label, os, now)
            writeAll(records)
        }
        return token
    }

    fun isValid(token: String): Boolean {
        if (!token.startsWith("kv_")) return false
        val candidate = hash(token)
        val records = synchronized(lock) { readAll() }
        // Constant-time comparison over every record so timing never narrows the search.
        var match = false
        for (record in records) {
            if (MessageDigest.isEqual(record.tokenHash.toByteArray(), candidate.toByteArray())) match = true
        }
        return match
    }

    fun clients(): List<ClientRecord> = synchronized(lock) { readAll() }

    private fun readAll(): List<ClientRecord> {
        if (!Files.isRegularFile(file)) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(ClientRecord.serializer()), Files.readString(file))
        }.getOrDefault(emptyList())
    }

    private fun writeAll(records: List<ClientRecord>) {
        Files.createDirectories(file.parent)
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.writeString(tmp, json.encodeToString(ListSerializer(ClientRecord.serializer()), records))
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun hash(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }
}
