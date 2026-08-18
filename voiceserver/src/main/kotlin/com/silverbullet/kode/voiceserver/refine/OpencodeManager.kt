package com.silverbullet.kode.voiceserver.refine

import com.silverbullet.kode.voiceserver.VoiceServerConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.util.Base64

/**
 * Owns the `opencode serve` instance the refiner talks to.
 *
 * When [VoiceServerConfig.opencodeUrl] is set we never spawn anything. Otherwise a child
 * process is started on demand and each of the failure modes observed in t3's
 * integration is closed off:
 *  - readiness comes from polling `GET /global/health`, not scraping the stdout banner;
 *  - every acquire re-checks process liveness *and* health before reuse;
 *  - the child always gets a password (never an open localhost server);
 *  - an idle timer shuts the child down instead of pinning it forever.
 */
class OpencodeManager(
    private val config: VoiceServerConfig,
    private val httpClient: HttpClient,
    private val scope: CoroutineScope,
) {
    data class Endpoint(val baseUrl: String, val basicAuthHeader: String?)

    private val log = LoggerFactory.getLogger(OpencodeManager::class.java)
    private val mutex = Mutex()
    private var managedProcess: Process? = null
    private var managedPassword: String? = null
    private var idleJob: Job? = null
    private var lastUsedAt = 0L

    suspend fun acquire(): Endpoint {
        config.opencodeUrl?.let { external ->
            return Endpoint(external, basicHeader(config.opencodePassword))
        }
        return mutex.withLock {
            lastUsedAt = System.currentTimeMillis()
            val existing = managedProcess
            val baseUrl = "http://127.0.0.1:${config.opencodePort}"
            if (existing != null && existing.isAlive && isHealthy(baseUrl, basicHeader(managedPassword))) {
                Endpoint(baseUrl, basicHeader(managedPassword))
            } else {
                existing?.let { stopLocked() }
                startLocked()
            }
        }
    }

    suspend fun shutdown() {
        mutex.withLock { stopLocked() }
    }

    private suspend fun startLocked(): Endpoint {
        val password = randomPassword()
        val baseUrl = "http://127.0.0.1:${config.opencodePort}"
        // The child's output goes to a file, not /dev/null: opencode reports request
        // failures as "check server logs", so those logs must exist somewhere.
        val childLog = config.dataDir.resolve("opencode.log").toFile()
        log.info("Starting managed opencode serve on {} (logs: {})", baseUrl, childLog)
        val process = ProcessBuilder(
            config.opencodeBinary,
            "serve",
            "--hostname=127.0.0.1",
            "--port=${config.opencodePort}",
        )
            .redirectOutput(ProcessBuilder.Redirect.appendTo(childLog))
            .redirectErrorStream(true)
            .apply { environment()["OPENCODE_SERVER_PASSWORD"] = password }
            .start()

        val header = basicHeader(password)
        val ready = withTimeoutOrNull(30_000) {
            while (true) {
                if (!process.isAlive) return@withTimeoutOrNull false
                if (isHealthy(baseUrl, header)) return@withTimeoutOrNull true
                delay(250)
            }
            @Suppress("UNREACHABLE_CODE") false
        } ?: false

        if (!ready) {
            destroy(process)
            error("opencode serve did not become healthy within 30s (binary: ${config.opencodeBinary})")
        }

        managedProcess = process
        managedPassword = password
        scheduleIdleShutdown()
        return Endpoint(baseUrl, header)
    }

    private fun scheduleIdleShutdown() {
        idleJob?.cancel()
        idleJob = scope.launch {
            while (true) {
                delay(config.opencodeIdleShutdownMs)
                val idleFor = System.currentTimeMillis() - lastUsedAt
                if (idleFor >= config.opencodeIdleShutdownMs) {
                    mutex.withLock {
                        if (System.currentTimeMillis() - lastUsedAt >= config.opencodeIdleShutdownMs) {
                            log.info("Stopping idle opencode serve")
                            stopLocked()
                        }
                    }
                    return@launch
                }
            }
        }
    }

    private fun stopLocked() {
        idleJob?.cancel()
        idleJob = null
        managedProcess?.let(::destroy)
        managedProcess = null
        managedPassword = null
    }

    private fun destroy(process: Process) {
        runCatching {
            process.toHandle().descendants().forEach(ProcessHandle::destroy)
            process.destroy()
            if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly)
                process.destroyForcibly()
            }
        }
    }

    private suspend fun isHealthy(baseUrl: String, authHeader: String?): Boolean = runCatching {
        // The shared client has no engine-level timeout, so bound the probe here: a
        // wedged child must read as unhealthy, not stall every acquire.
        withTimeoutOrNull(2_000) {
            val response: HttpResponse = httpClient.get("$baseUrl/global/health") {
                authHeader?.let { header(HttpHeaders.Authorization, it) }
            }
            response.status.isSuccess()
        } ?: false
    }.getOrDefault(false)

    private fun basicHeader(password: String?): String? {
        if (password.isNullOrEmpty()) return null
        val encoded = Base64.getEncoder().encodeToString("opencode:$password".toByteArray())
        return "Basic $encoded"
    }

    private fun randomPassword(): String {
        val bytes = ByteArray(24).also(SecureRandom()::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
