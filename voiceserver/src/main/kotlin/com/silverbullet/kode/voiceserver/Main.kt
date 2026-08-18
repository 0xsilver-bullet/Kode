package com.silverbullet.kode.voiceserver

import com.silverbullet.kode.voice.contract.VoiceProtocol
import com.silverbullet.kode.voiceserver.auth.ClientTokenStore
import com.silverbullet.kode.voiceserver.auth.PairingCodeRegistry
import com.silverbullet.kode.voiceserver.deepgram.DeepgramLiveClient
import com.silverbullet.kode.voiceserver.glossary.ProjectGlossaryService
import com.silverbullet.kode.voiceserver.refine.OpencodeManager
import com.silverbullet.kode.voiceserver.refine.OpencodeRefiner
import com.silverbullet.kode.voiceserver.tailscale.TailscaleCli
import com.silverbullet.kode.voiceserver.tailscale.TailscaleExposure
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.net.NetworkInterface
import java.nio.file.Files
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        null, "serve" -> serve()
        "pair" -> pair()
        else -> {
            System.err.println("Usage: voiceserver [serve|pair]")
            exitProcess(2)
        }
    }
}

private fun serve() {
    val config = VoiceServerConfig.load()
    Files.createDirectories(config.dataDir)

    if (config.deepgramApiKey.isNullOrBlank()) {
        System.err.println(
            "WARNING: DEEPGRAM_API_KEY is not set — voice sessions will fail until it is " +
                "(env var, or deepgram.api.key in ${config.dataDir.resolve("voiceserver.properties")}).",
        )
    }

    val httpClient = HttpClient(CIO) {
        install(WebSockets)
        engine {
            // CIO defaults to a 15s request timeout, which is shorter than a cold
            // opencode instance load (first prompt against a directory) and would
            // preempt the refiner's own 60s budget. Timeouts are owned explicitly by
            // each call site: withTimeout in the refiner, bounded health checks in
            // OpencodeManager, a bounded connect in DeepgramLiveClient.
            requestTimeout = 0
        }
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val glossary = ProjectGlossaryService(config.allowedRoots)
    val manager = OpencodeManager(config, httpClient, scope)
    val adminSecret = randomSecret()
    val services = VoiceServices(
        config = config,
        serverId = loadOrCreateServerId(config),
        tokenStore = ClientTokenStore(config.dataDir.resolve("clients.json")),
        pairingCodes = PairingCodeRegistry(),
        adminSecret = adminSecret,
        glossary = glossary,
        deepgram = DeepgramLiveClient(httpClient, config),
        refiner = OpencodeRefiner(config, manager, httpClient, glossary),
        advertisedBaseUrl = config.publicUrl ?: "http://${bestLanAddress()}:${config.port}",
    )

    var tailscaleCleanup: (() -> Unit)? = null
    RuntimeState.write(config.dataDir, RuntimeState(pid = ProcessHandle.current().pid(), port = config.port, adminSecret = adminSecret))
    Runtime.getRuntime().addShutdownHook(
        Thread {
            RuntimeState.clear(config.dataDir)
            tailscaleCleanup?.invoke()
            runBlocking { manager.shutdown() }
        },
    )

    // Tailscale exposure runs *after* the server is listening (its verification probe
    // hits this very server through the tailnet), mirroring t3's startup ordering.
    val server = embeddedServer(Netty, host = config.host, port = config.port) {
        voiceServerModule(services)
    }.start(wait = false)

    if (config.publicUrl == null) {
        val exposure = try {
            runBlocking {
                TailscaleExposure(TailscaleCli(config.tailscaleBinary), httpClient).establish(
                    mode = TailscaleExposure.Mode.parse(config.tailscaleMode),
                    serverId = services.serverId,
                    localPort = config.port,
                    httpsPort = config.tailscaleHttpsPort,
                )
            }
        } catch (cause: TailscaleExposure.TailscaleRequiredException) {
            System.err.println("ERROR: ${cause.message}")
            server.stop(500, 1_000)
            exitProcess(1)
        }
        if (exposure != null) {
            services.advertisedBaseUrl = exposure.advertisedBaseUrl
            tailscaleCleanup = exposure.cleanup
        }
    }

    val initialCode = services.pairingCodes.mint()
    val pairingUrl = com.silverbullet.kode.voice.contract.VoicePairingLink.build(services.advertisedBaseUrl, initialCode)

    println("kode-voice server \"${config.label}\" listening on ${config.host}:${config.port}")
    println("Advertised address: ${services.advertisedBaseUrl}")
    println("Descriptor: ${services.advertisedBaseUrl}${VoiceProtocol.WELL_KNOWN_PATH}")
    println()
    println("Pair a device (valid 15 minutes, one use — run `voiceserver pair` for a fresh code):")
    println()
    println(TerminalQr.render(pairingUrl))
    println(pairingUrl)
    println()

    Thread.currentThread().join()
}

private fun pair() {
    val config = VoiceServerConfig.load()
    val state = RuntimeState.read(config.dataDir)
    if (state == null) {
        System.err.println("No running voice server found (no live runtime-state in ${config.dataDir}). Start one with `voiceserver serve`.")
        exitProcess(1)
    }
    val client = HttpClient(CIO)
    try {
        runBlocking {
            val response = client.post("http://127.0.0.1:${state.port}/v1/admin/pairing-links") {
                header("X-Admin-Secret", state.adminSecret)
            }
            if (!response.status.isSuccess()) {
                System.err.println("Server refused to mint a pairing link: ${response.status}")
                exitProcess(1)
            }
            val link = Json { ignoreUnknownKeys = true }
                .decodeFromString(PairingLinkResponse.serializer(), response.bodyAsText())
            println("Pair a device (valid 15 minutes, one use):")
            println()
            println(TerminalQr.render(link.url))
            println(link.url)
        }
    } finally {
        client.close()
    }
}

private fun loadOrCreateServerId(config: VoiceServerConfig): String {
    val file = config.dataDir.resolve("server-id")
    if (Files.isRegularFile(file)) {
        val existing = Files.readString(file).trim()
        if (existing.isNotEmpty()) return existing
    }
    val id = UUID.randomUUID().toString()
    Files.createDirectories(config.dataDir)
    Files.writeString(file, id)
    return id
}

/** Best-effort LAN IPv4 for printed pairing links when no public URL is configured. */
private fun bestLanAddress(): String =
    runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<java.net.Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull() ?: "127.0.0.1"

private fun randomSecret(): String {
    val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
