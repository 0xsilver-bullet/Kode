package com.silverbullet.kode.voiceserver

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties

/**
 * All configuration comes from environment variables, optionally overlaid on a properties
 * file at `<dataDir>/voiceserver.properties` (env wins). Nothing here ever lives in the
 * repo, so secrets like [deepgramApiKey] cannot leak into git.
 *
 * Property keys are the env names lowercased with `_` → `.` (e.g. `kode.voice.port`).
 */
data class VoiceServerConfig(
    val host: String,
    val port: Int,
    /**
     * Base URL clients should reach this server at, e.g. `https://machine.tailnet.ts.net`
     * when exposed through `tailscale serve`. Used only for printed pairing links; when
     * unset, links use a best-effort LAN address.
     */
    val publicUrl: String?,
    val label: String,
    val dataDir: Path,
    /** `auto` (default) | `on` (require tailscale) | `off`. */
    val tailscaleMode: String,
    /** HTTPS port for the `tailscale serve` mapping; 8443 avoids t3's default 443. */
    val tailscaleHttpsPort: Int,
    val tailscaleBinary: String,
    val deepgramApiKey: String?,
    val deepgramBaseUrl: String,
    val deepgramModel: String,
    val deepgramEndpointingMs: Int,
    val deepgramUtteranceEndMs: Int,
    /** Directories the glossary builder may read. Everything else is ignored. */
    val allowedRoots: List<Path>,
    /** `provider/model` slug for the refiner, split on the first slash like opencode does. */
    val refineModel: String,
    val refineTimeoutMs: Long,
    /** When set, use this external `opencode serve` instead of spawning a managed one. */
    val opencodeUrl: String?,
    val opencodePassword: String?,
    val opencodeBinary: String,
    val opencodePort: Int,
    val opencodeIdleShutdownMs: Long,
) {
    companion object {
        const val DEFAULT_PORT: Int = 8484

        fun load(
            env: Map<String, String> = System.getenv(),
            home: Path = Paths.get(System.getProperty("user.home")),
        ): VoiceServerConfig {
            val dataDir = env["KODE_VOICE_DATA_DIR"]?.let(Paths::get) ?: home.resolve(".kode-voice")
            val fileProps = Properties().apply {
                val file = dataDir.resolve("voiceserver.properties")
                if (Files.isRegularFile(file)) Files.newBufferedReader(file).use(::load)
            }

            fun value(envKey: String): String? =
                env[envKey]?.takeIf { it.isNotBlank() }
                    ?: fileProps.getProperty(envKey.lowercase().replace('_', '.'))?.takeIf { it.isNotBlank() }

            val allowedRoots = value("KODE_VOICE_ALLOWED_ROOTS")
                ?.split(':')
                ?.filter { it.isNotBlank() }
                ?.map { Paths.get(it) }
                ?: listOf(home)

            return VoiceServerConfig(
                host = value("KODE_VOICE_HOST") ?: "0.0.0.0",
                port = value("KODE_VOICE_PORT")?.toInt() ?: DEFAULT_PORT,
                publicUrl = value("KODE_VOICE_PUBLIC_URL")?.trimEnd('/'),
                label = value("KODE_VOICE_LABEL") ?: defaultLabel(),
                dataDir = dataDir,
                tailscaleMode = value("KODE_VOICE_TAILSCALE") ?: "auto",
                tailscaleHttpsPort = value("KODE_VOICE_TAILSCALE_PORT")?.toInt() ?: 8443,
                tailscaleBinary = value("KODE_VOICE_TAILSCALE_BIN") ?: "tailscale",
                deepgramApiKey = value("DEEPGRAM_API_KEY"),
                deepgramBaseUrl = value("KODE_VOICE_DEEPGRAM_URL") ?: "wss://api.deepgram.com",
                deepgramModel = value("KODE_VOICE_DEEPGRAM_MODEL") ?: "nova-3",
                deepgramEndpointingMs = value("KODE_VOICE_ENDPOINTING_MS")?.toInt() ?: 300,
                deepgramUtteranceEndMs = value("KODE_VOICE_UTTERANCE_END_MS")?.toInt() ?: 1500,
                allowedRoots = allowedRoots,
                refineModel = value("KODE_VOICE_REFINE_MODEL") ?: "anthropic/claude-haiku-4-5",
                refineTimeoutMs = value("KODE_VOICE_REFINE_TIMEOUT_MS")?.toLong() ?: 60_000,
                opencodeUrl = value("KODE_VOICE_OPENCODE_URL")?.trimEnd('/'),
                opencodePassword = value("KODE_VOICE_OPENCODE_PASSWORD"),
                opencodeBinary = value("KODE_VOICE_OPENCODE_BIN") ?: "opencode",
                opencodePort = value("KODE_VOICE_OPENCODE_PORT")?.toInt() ?: 43_110,
                opencodeIdleShutdownMs = value("KODE_VOICE_OPENCODE_IDLE_MS")?.toLong() ?: 300_000,
            )
        }

        private fun defaultLabel(): String = runCatching {
            java.net.InetAddress.getLocalHost().hostName.substringBefore('.')
        }.getOrDefault("kode-voice")
    }
}
