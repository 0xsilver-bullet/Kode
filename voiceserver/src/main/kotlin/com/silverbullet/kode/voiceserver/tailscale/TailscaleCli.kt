package com.silverbullet.kode.voiceserver.tailscale

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.TimeUnit

/**
 * Thin wrapper over the tailscale CLI, ported from t3's `packages/tailscale`.
 *
 * Two of t3's hard-won rules are kept:
 *  - stderr is never surfaced raw — tailscale can leak `tskey-…` secrets into it, so
 *    failures are classified into [ServeFailure] kinds instead;
 *  - the CLI is invoked through an injectable [CommandRunner] so every decision path is
 *    testable without a tailnet.
 */
class TailscaleCli(
    private val binary: String = "tailscale",
    private val runner: CommandRunner = ProcessCommandRunner(),
) {
    fun interface CommandRunner {
        fun run(args: List<String>, timeoutMs: Long): CommandResult
    }

    data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String) {
        companion object {
            /** The binary itself could not be executed (not installed / not on PATH). */
            val UNAVAILABLE = CommandResult(exitCode = 127, stdout = "", stderr = "")
        }
    }

    data class Status(
        /** MagicDNS name without the trailing dot, e.g. `machine.tail1234.ts.net`. */
        val dnsName: String?,
        val ipv4: String?,
        val running: Boolean,
    )

    enum class ServeFailure { NOT_LOGGED_IN, PERMISSION_DENIED, UNAVAILABLE, SERVE_NOT_ENABLED, UNKNOWN }

    sealed interface ServeResult {
        data object Enabled : ServeResult

        data class Failed(
            val kind: ServeFailure,
            /**
             * The tailnet-admin approval link the CLI prints when Serve/HTTPS is not
             * enabled ("Serve is not enabled on your tailnet. To enable, visit: …").
             * The CLI then polls forever waiting for approval, so the runner's timeout
             * kills it and this URL is how the operator finishes the job.
             */
            val enableUrl: String? = null,
        ) : ServeResult
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** Null when tailscale is not installed or `status` itself fails. */
    fun status(): Status? {
        val result = runner.run(listOf(binary, "status", "--json"), STATUS_TIMEOUT_MS)
        if (result.exitCode != 0 || result.stdout.isBlank()) return null
        val root = runCatching { json.parseToJsonElement(result.stdout).jsonObject }.getOrNull() ?: return null
        val self = root["Self"] as? JsonObject
        val dnsName = self?.get("DNSName")?.jsonPrimitive?.content
            ?.trimEnd('.')
            ?.takeIf { it.isNotBlank() }
        val ipv4 = self?.get("TailscaleIPs")?.jsonArray
            ?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
            ?.firstOrNull { it.count { ch -> ch == '.' } == 3 }
        val running = root["BackendState"]?.jsonPrimitive?.content == "Running"
        return Status(dnsName = dnsName, ipv4 = ipv4, running = running)
    }

    /** `tailscale serve --bg --https=<httpsPort> http://127.0.0.1:<localPort>` */
    fun enableServe(httpsPort: Int, localPort: Int): ServeResult {
        val result = runner.run(
            listOf(binary, "serve", "--bg", "--https=$httpsPort", "http://127.0.0.1:$localPort"),
            SERVE_TIMEOUT_MS,
        )
        if (result.exitCode == 0) return ServeResult.Enabled
        val combined = result.stdout + "\n" + result.stderr
        if (combined.contains("is not enabled on your tailnet", ignoreCase = true)) {
            val enableUrl = Regex("""https://login\.tailscale\.com/\S+""").find(combined)?.value
            return ServeResult.Failed(ServeFailure.SERVE_NOT_ENABLED, enableUrl)
        }
        return ServeResult.Failed(classify(result))
    }

    /** Best-effort `tailscale serve --https=<httpsPort> off`; failures are ignorable. */
    fun disableServe(httpsPort: Int) {
        runner.run(listOf(binary, "serve", "--https=$httpsPort", "off"), SERVE_TIMEOUT_MS)
    }

    private fun classify(result: CommandResult): ServeFailure {
        if (result === CommandResult.UNAVAILABLE || result.exitCode == 127) return ServeFailure.UNAVAILABLE
        val stderr = result.stderr.lowercase()
        return when {
            "logged out" in stderr || "not logged in" in stderr -> ServeFailure.NOT_LOGGED_IN
            "permission denied" in stderr || "access denied" in stderr -> ServeFailure.PERMISSION_DENIED
            else -> ServeFailure.UNKNOWN
        }
    }

    private class ProcessCommandRunner : CommandRunner {
        override fun run(args: List<String>, timeoutMs: Long): CommandResult = try {
            val process = ProcessBuilder(args).start()
            // stdin gets immediate EOF and both streams drain on their own threads:
            // reading them inline would block past any timeout when the CLI decides to
            // prompt or simply not exit (observed with `tailscale serve` on macOS).
            process.outputStream.close()
            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val outReader = Thread { runCatching { process.inputStream.bufferedReader().forEachLine { stdout.appendLine(it) } } }
            val errReader = Thread { runCatching { process.errorStream.bufferedReader().forEachLine { stderr.appendLine(it) } } }
            outReader.isDaemon = true
            errReader.isDaemon = true
            outReader.start()
            errReader.start()

            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) process.destroyForcibly()
            outReader.join(1_000)
            errReader.join(1_000)
            CommandResult(
                exitCode = if (finished) process.exitValue() else -1,
                stdout = stdout.toString(),
                stderr = stderr.toString(),
            )
        } catch (cause: java.io.IOException) {
            CommandResult.UNAVAILABLE
        }
    }

    private companion object {
        const val STATUS_TIMEOUT_MS = 5_000L
        const val SERVE_TIMEOUT_MS = 15_000L
    }
}
