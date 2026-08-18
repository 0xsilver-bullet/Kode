package com.silverbullet.kode.voiceserver

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/**
 * Handshake file for the `pair` CLI, modeled on t3's server-runtime-state: a running
 * server writes `{pid, port, adminSecret}` into the data dir so `voiceserver pair` can
 * find it and mint pairing links over loopback without any shared long-lived secret.
 */
@Serializable
data class RuntimeState(
    val pid: Long,
    val port: Int,
    val adminSecret: String,
) {
    companion object {
        private const val FILE_NAME = "runtime-state.json"
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

        fun write(dataDir: Path, state: RuntimeState) {
            Files.createDirectories(dataDir)
            Files.writeString(dataDir.resolve(FILE_NAME), json.encodeToString(serializer(), state))
        }

        fun read(dataDir: Path): RuntimeState? {
            val file = dataDir.resolve(FILE_NAME)
            if (!Files.isRegularFile(file)) return null
            val state = runCatching { json.decodeFromString(serializer(), Files.readString(file)) }.getOrNull()
                ?: return null
            return state.takeIf { ProcessHandle.of(it.pid).map(ProcessHandle::isAlive).orElse(false) }
        }

        fun clear(dataDir: Path) {
            runCatching { Files.deleteIfExists(dataDir.resolve(FILE_NAME)) }
        }
    }
}
