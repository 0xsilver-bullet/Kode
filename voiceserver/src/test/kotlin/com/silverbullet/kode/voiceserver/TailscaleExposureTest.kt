package com.silverbullet.kode.voiceserver

import com.silverbullet.kode.voiceserver.tailscale.TailscaleCli
import com.silverbullet.kode.voiceserver.tailscale.TailscaleExposure
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TailscaleExposureTest {

    private val runningStatusJson = """
        {"BackendState":"Running","Self":{"DNSName":"machine.tail1234.ts.net.","Online":true,
         "TailscaleIPs":["100.101.102.103","fd7a:115c:a1e0::1"]}}
    """.trimIndent()

    /** Scripted CLI: records invocations, answers from the provided handlers. */
    private class FakeRunner(
        private val statusResult: TailscaleCli.CommandResult,
        private val serveResult: TailscaleCli.CommandResult =
            TailscaleCli.CommandResult(0, "", ""),
    ) : TailscaleCli.CommandRunner {
        val commands = mutableListOf<List<String>>()

        override fun run(args: List<String>, timeoutMs: Long): TailscaleCli.CommandResult {
            commands += args
            return when {
                args.contains("status") -> statusResult
                args.contains("serve") -> serveResult
                else -> TailscaleCli.CommandResult(1, "", "")
            }
        }
    }

    /**
     * Probe responder: `answers` maps request ordinal → (status, body). Unlisted
     * ordinals throw, like a port with no serve mapping (connection refused) — an HTTP
     * *error response* would instead mean some other service is listening there.
     */
    private fun probeClient(vararg answers: Pair<Int, Pair<HttpStatusCode, String>>): HttpClient {
        val byOrdinal = answers.toMap()
        var calls = 0
        return HttpClient(
            MockEngine { _ ->
                val answer = byOrdinal[calls++] ?: throw java.net.ConnectException("connection refused")
                respond(
                    answer.second,
                    answer.first,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
    }

    private fun descriptorBody(serverId: String) =
        """{"service":"kode-voice","serverId":"$serverId","label":"x","version":1,"capabilities":[]}"""

    private fun exposure(runner: FakeRunner, client: HttpClient) = TailscaleExposure(
        cli = TailscaleCli(binary = "tailscale", runner = runner),
        httpClient = client,
        probeRetries = 2,
        probeRetryDelayMs = 1,
    )

    private fun runningRunner(serveExit: Int = 0, serveStderr: String = "") = FakeRunner(
        statusResult = TailscaleCli.CommandResult(0, runningStatusJson, ""),
        serveResult = TailscaleCli.CommandResult(serveExit, "", serveStderr),
    )

    @Test
    fun `off mode does nothing`(): Unit = runBlocking {
        val runner = runningRunner()
        val result = exposure(runner, probeClient()).establish(
            mode = TailscaleExposure.Mode.OFF, serverId = "srv", localPort = 8484, httpsPort = 8443,
        )
        assertNull(result)
        assertTrue(runner.commands.isEmpty())
    }

    @Test
    fun `auto without a tailscale binary falls back silently`(): Unit = runBlocking {
        val runner = FakeRunner(statusResult = TailscaleCli.CommandResult.UNAVAILABLE)
        val result = exposure(runner, probeClient()).establish(
            mode = TailscaleExposure.Mode.AUTO, serverId = "srv", localPort = 8484, httpsPort = 8443,
        )
        assertNull(result)
    }

    @Test
    fun `on mode without tailscale is a startup error`(): Unit = runBlocking {
        val runner = FakeRunner(statusResult = TailscaleCli.CommandResult.UNAVAILABLE)
        assertFailsWith<TailscaleExposure.TailscaleRequiredException> {
            exposure(runner, probeClient()).establish(
                mode = TailscaleExposure.Mode.ON, serverId = "srv", localPort = 8484, httpsPort = 8443,
            )
        }
    }

    @Test
    fun `fresh port enables serve and advertises the MagicDNS url`(): Unit = runBlocking {
        val runner = runningRunner()
        // Probe 0 (conflict check): nothing there. Probe 1 (verification): ours.
        val client = probeClient(1 to (HttpStatusCode.OK to descriptorBody("srv")))
        val result = exposure(runner, client).establish(
            mode = TailscaleExposure.Mode.AUTO, serverId = "srv", localPort = 8484, httpsPort = 8443,
        )

        assertNotNull(result)
        assertEquals("https://machine.tail1234.ts.net:8443", result.advertisedBaseUrl)
        assertNotNull(result.cleanup)
        assertTrue(
            runner.commands.any { it.containsAll(listOf("serve", "--bg", "--https=8443", "http://127.0.0.1:8484")) },
            runner.commands.toString(),
        )

        result.cleanup!!.invoke()
        assertTrue(runner.commands.any { it.containsAll(listOf("serve", "--https=8443", "off")) })
    }

    @Test
    fun `an existing mapping for this server is adopted without re-enabling`(): Unit = runBlocking {
        val runner = runningRunner()
        val client = probeClient(0 to (HttpStatusCode.OK to descriptorBody("srv")))
        val result = exposure(runner, client).establish(
            mode = TailscaleExposure.Mode.AUTO, serverId = "srv", localPort = 8484, httpsPort = 8443,
        )

        assertNotNull(result)
        assertEquals("https://machine.tail1234.ts.net:8443", result.advertisedBaseUrl)
        assertTrue(runner.commands.none { it.contains("--bg") }, "must not re-enable an adopted mapping")
        assertNotNull(result.cleanup)
    }

    @Test
    fun `a mapping for another service is refused and the tailnet ip is advertised`(): Unit = runBlocking {
        val runner = runningRunner()
        val client = probeClient(0 to (HttpStatusCode.OK to descriptorBody("someone-else")))
        val result = exposure(runner, client).establish(
            mode = TailscaleExposure.Mode.AUTO, serverId = "srv", localPort = 8484, httpsPort = 8443,
        )

        assertNotNull(result)
        assertEquals("http://100.101.102.103:8484", result.advertisedBaseUrl)
        assertNull(result.cleanup)
        assertTrue(runner.commands.none { it.contains("--bg") }, "must not clobber a foreign mapping")
    }

    @Test
    fun `serve failure falls back to the tailnet ip`(): Unit = runBlocking {
        val runner = runningRunner(serveExit = 1, serveStderr = "Access denied: serve not permitted")
        val result = exposure(runner, probeClient()).establish(
            mode = TailscaleExposure.Mode.AUTO, serverId = "srv", localPort = 8484, httpsPort = 8443,
        )

        assertNotNull(result)
        assertEquals("http://100.101.102.103:8484", result.advertisedBaseUrl)
        assertNull(result.cleanup)
    }

    @Test
    fun `unverified serve still advertises the https url`(): Unit = runBlocking {
        val runner = runningRunner()
        // No probe ever succeeds (cert still provisioning) — mapping is ours regardless.
        val result = exposure(runner, probeClient()).establish(
            mode = TailscaleExposure.Mode.AUTO, serverId = "srv", localPort = 8484, httpsPort = 8443,
        )

        assertNotNull(result)
        assertEquals("https://machine.tail1234.ts.net:8443", result.advertisedBaseUrl)
        assertNotNull(result.cleanup)
    }

    @Test
    fun `serve-not-enabled is classified and carries the enable link`() {
        val runner = FakeRunner(
            statusResult = TailscaleCli.CommandResult(0, runningStatusJson, ""),
            serveResult = TailscaleCli.CommandResult(
                // The CLI polls forever in this state; the runner timeout reports -1.
                -1,
                "Serve is not enabled on your tailnet.\nTo enable, visit:\n\n\thttps://login.tailscale.com/f/serve?node=abc123\n",
                "",
            ),
        )
        val result = TailscaleCli(runner = runner).enableServe(httpsPort = 8443, localPort = 8484)
        val failed = result as TailscaleCli.ServeResult.Failed
        assertEquals(TailscaleCli.ServeFailure.SERVE_NOT_ENABLED, failed.kind)
        assertEquals("https://login.tailscale.com/f/serve?node=abc123", failed.enableUrl)
    }

    @Test
    fun `status parsing strips the trailing dot and finds the ipv4`() {
        val cli = TailscaleCli(runner = FakeRunner(TailscaleCli.CommandResult(0, runningStatusJson, "")))
        val status = assertNotNull(cli.status())
        assertEquals("machine.tail1234.ts.net", status.dnsName)
        assertEquals("100.101.102.103", status.ipv4)
        assertTrue(status.running)
    }

    @Test
    fun `port 443 is omitted from the advertised url`(): Unit = runBlocking {
        val runner = runningRunner()
        val client = probeClient(1 to (HttpStatusCode.OK to descriptorBody("srv")))
        val result = exposure(runner, client).establish(
            mode = TailscaleExposure.Mode.AUTO, serverId = "srv", localPort = 8484, httpsPort = 443,
        )
        assertEquals("https://machine.tail1234.ts.net", assertNotNull(result).advertisedBaseUrl)
    }
}
