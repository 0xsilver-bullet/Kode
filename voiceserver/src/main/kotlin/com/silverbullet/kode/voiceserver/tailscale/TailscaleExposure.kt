package com.silverbullet.kode.voiceserver.tailscale

import com.silverbullet.kode.voice.contract.VoiceJson
import com.silverbullet.kode.voice.contract.VoiceProtocol
import com.silverbullet.kode.voice.contract.VoiceServerDescriptor
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory

/**
 * Decides the tailnet-facing URL for the voice server, following t3's `--tailscale-serve`
 * behavior and the decisions locked in QA:
 *
 *  - `auto` (default): use tailscale when the CLI reports a running backend, else fall
 *    back to LAN quietly; `on` makes a missing/stopped tailscale a startup error; `off`
 *    skips entirely. An explicit `KODE_VOICE_PUBLIC_URL` wins before this code runs.
 *  - Exposure is `tailscale serve` (HTTPS + MagicDNS, like t3), falling back to the
 *    plain tailnet IP when serve cannot be used — WireGuard still encrypts that path.
 *  - Conflict guard: before touching the HTTPS port we probe it; a mapping that answers
 *    as this same server (matching serverId) is adopted, anything else is left alone
 *    (t3's refuse-to-clobber rule) and the IP fallback is used instead.
 *  - [Exposure.cleanup] removes only a mapping this process created or adopted.
 */
class TailscaleExposure(
    private val cli: TailscaleCli,
    private val httpClient: HttpClient,
    private val probeRetries: Int = DEFAULT_PROBE_RETRIES,
    private val probeRetryDelayMs: Long = DEFAULT_PROBE_RETRY_DELAY_MS,
) {
    enum class Mode { AUTO, ON, OFF;
        companion object {
            fun parse(value: String?): Mode = when (value?.lowercase()) {
                null, "", "auto" -> AUTO
                "on", "true", "1", "require" -> ON
                "off", "false", "0" -> OFF
                else -> AUTO
            }
        }
    }

    data class Exposure(
        val advertisedBaseUrl: String,
        /** Non-null only when this process owns a serve mapping to remove on shutdown. */
        val cleanup: (() -> Unit)?,
    )

    class TailscaleRequiredException(message: String) : Exception(message)

    private val log = LoggerFactory.getLogger(TailscaleExposure::class.java)

    /**
     * Returns the tailnet exposure, or null to keep the LAN URL. Throws
     * [TailscaleRequiredException] only in [Mode.ON] when tailscale is unusable.
     */
    suspend fun establish(mode: Mode, serverId: String, localPort: Int, httpsPort: Int): Exposure? {
        if (mode == Mode.OFF) return null

        val status = cli.status()
        if (status == null || !status.running) {
            val reason = if (status == null) {
                "tailscale CLI is not available"
            } else {
                "tailscale is installed but not connected (backend not running)"
            }
            if (mode == Mode.ON) {
                throw TailscaleRequiredException("KODE_VOICE_TAILSCALE=on but $reason.")
            }
            log.info("Tailscale exposure skipped: {} — advertising the LAN address.", reason)
            return null
        }

        val ipFallback = status.ipv4?.let { Exposure("http://$it:$localPort", cleanup = null) }

        val dnsName = status.dnsName
        if (dnsName == null) {
            log.info("Tailnet has no MagicDNS name; advertising the tailnet IP instead.")
            return ipFallback
        }
        val httpsUrl = if (httpsPort == 443) "https://$dnsName" else "https://$dnsName:$httpsPort"

        // Refuse-to-clobber guard: if something already answers on that port and it is
        // not this very server, leave the mapping alone.
        when (probe(httpsUrl, serverId)) {
            ProbeOutcome.OURS -> {
                log.info("Adopting existing tailscale serve mapping at {}", httpsUrl)
                return Exposure(httpsUrl, cleanup = { cli.disableServe(httpsPort) })
            }
            ProbeOutcome.OTHER_SERVICE -> {
                log.warn(
                    "Port {} already has a tailscale serve mapping for a different service; " +
                        "leaving it untouched and advertising the tailnet IP. " +
                        "Set KODE_VOICE_TAILSCALE_PORT to a free port for HTTPS.",
                    httpsPort,
                )
                return ipFallback
            }
            ProbeOutcome.NOTHING -> Unit
        }

        when (val result = cli.enableServe(httpsPort = httpsPort, localPort = localPort)) {
            is TailscaleCli.ServeResult.Enabled -> Unit
            is TailscaleCli.ServeResult.Failed -> {
                if (result.kind == TailscaleCli.ServeFailure.SERVE_NOT_ENABLED) {
                    log.warn(
                        "Tailscale Serve/HTTPS is not enabled on your tailnet — enable it once at {} " +
                            "and restart for an https URL. Advertising the tailnet IP meanwhile " +
                            "(still end-to-end encrypted by WireGuard).",
                        result.enableUrl ?: "https://login.tailscale.com (Settings → Feature previews / HTTPS)",
                    )
                } else {
                    log.warn(
                        "tailscale serve could not be enabled ({}); advertising the tailnet IP instead.",
                        result.kind,
                    )
                }
                return ipFallback
            }
        }

        // Verify end to end. The first HTTPS hit on a fresh mapping can wait on
        // certificate provisioning, so give it a few patient attempts — and advertise
        // anyway on timeout, because the mapping is in place and certs finish async.
        repeat(probeRetries) { attempt ->
            if (probe(httpsUrl, serverId) == ProbeOutcome.OURS) {
                log.info("Tailscale serve verified: {}", httpsUrl)
                return Exposure(httpsUrl, cleanup = { cli.disableServe(httpsPort) })
            }
            if (attempt < probeRetries - 1) delay(probeRetryDelayMs)
        }
        log.warn(
            "Tailscale serve is enabled but {} did not verify yet (certificate provisioning can " +
                "take a minute on first use); advertising it anyway.",
            httpsUrl,
        )
        return Exposure(httpsUrl, cleanup = { cli.disableServe(httpsPort) })
    }

    private enum class ProbeOutcome { OURS, OTHER_SERVICE, NOTHING }

    private suspend fun probe(baseUrl: String, serverId: String): ProbeOutcome =
        withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            runCatching {
                val response = httpClient.get(baseUrl + VoiceProtocol.WELL_KNOWN_PATH)
                if (!response.status.isSuccess()) return@runCatching ProbeOutcome.OTHER_SERVICE
                val descriptor = runCatching {
                    VoiceJson.decodeFromString(VoiceServerDescriptor.serializer(), response.bodyAsText())
                }.getOrNull() ?: return@runCatching ProbeOutcome.OTHER_SERVICE
                if (descriptor.service == VoiceProtocol.SERVICE_MARKER && descriptor.serverId == serverId) {
                    ProbeOutcome.OURS
                } else {
                    ProbeOutcome.OTHER_SERVICE
                }
            }.getOrDefault(ProbeOutcome.NOTHING)
        } ?: ProbeOutcome.NOTHING

    private companion object {
        const val PROBE_TIMEOUT_MS = 4_000L
        const val DEFAULT_PROBE_RETRIES = 6
        const val DEFAULT_PROBE_RETRY_DELAY_MS = 2_000L
    }
}
