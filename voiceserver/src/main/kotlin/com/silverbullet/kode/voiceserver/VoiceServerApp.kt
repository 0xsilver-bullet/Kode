package com.silverbullet.kode.voiceserver

import com.silverbullet.kode.voice.contract.VoiceErrorResponse
import com.silverbullet.kode.voice.contract.VoicePairRequest
import com.silverbullet.kode.voice.contract.VoicePairResponse
import com.silverbullet.kode.voice.contract.VoiceProtocol
import com.silverbullet.kode.voice.contract.VoicePairingLink
import com.silverbullet.kode.voice.contract.VoiceRefineRequest
import com.silverbullet.kode.voice.contract.VoiceServerDescriptor
import com.silverbullet.kode.voiceserver.auth.ClientTokenStore
import com.silverbullet.kode.voiceserver.auth.PairingCodeRegistry
import com.silverbullet.kode.voiceserver.deepgram.DeepgramLiveClient
import com.silverbullet.kode.voiceserver.deepgram.ListenSession
import com.silverbullet.kode.voiceserver.glossary.ProjectGlossaryService
import com.silverbullet.kode.voiceserver.refine.TranscriptRefiner
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

class VoiceServices(
    val config: VoiceServerConfig,
    val serverId: String,
    val tokenStore: ClientTokenStore,
    val pairingCodes: PairingCodeRegistry,
    val adminSecret: String,
    val glossary: ProjectGlossaryService,
    val deepgram: DeepgramLiveClient,
    val refiner: TranscriptRefiner,
    /**
     * Base URL used when rendering pairing links. Mutable because the tailscale
     * exposure is established after the HTTP server is listening (its verification
     * probes this very server), upgrading the advertised address in place.
     */
    @Volatile var advertisedBaseUrl: String,
)

@Serializable
data class PairingLinkResponse(val code: String, val url: String)

fun Application.voiceServerModule(services: VoiceServices) {
    val log = LoggerFactory.getLogger("VoiceServer")

    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
    }
    install(WebSockets) {
        pingPeriodMillis = 15_000
        timeoutMillis = 30_000
        maxFrameSize = 1 shl 20
    }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            log.warn("Unhandled error on {}", call.request.let { "${it.local.method.value} ${it.local.uri}" }, cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                VoiceErrorResponse(error = "internal", detail = cause.message),
            )
        }
    }
    install(Authentication) {
        bearer("voice-bearer") {
            realm = "kode-voice"
            authenticate { credential ->
                if (services.tokenStore.isValid(credential.token)) UserIdPrincipal("paired-client") else null
            }
        }
    }

    routing {
        get(VoiceProtocol.WELL_KNOWN_PATH) {
            call.respond(
                VoiceServerDescriptor(
                    serverId = services.serverId,
                    label = services.config.label,
                    capabilities = listOf(VoiceProtocol.CAPABILITY_REFINEMENT),
                ),
            )
        }

        // The pairing link opens here if someone taps it in a browser; the code stays in
        // the fragment, which never reaches the server.
        get(VoicePairingLink.PAIR_PAGE_PATH) {
            call.respondText(
                "Kode voice server \"${services.config.label}\". " +
                    "Scan this link's QR code from Kode's environment settings to pair.",
            )
        }

        post(VoiceProtocol.PAIR_PATH) {
            val request = call.receive<VoicePairRequest>()
            if (!services.pairingCodes.redeem(request.code)) {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    VoiceErrorResponse(error = "invalid-code", detail = "Pairing code is unknown, expired, or already used"),
                )
                return@post
            }
            val token = services.tokenStore.issueToken(label = request.clientLabel, os = request.clientOs)
            log.info("Paired new client '{}' ({})", request.clientLabel, request.clientOs)
            call.respond(
                VoicePairResponse(
                    accessToken = token,
                    serverId = services.serverId,
                    label = services.config.label,
                ),
            )
        }

        // Loopback CLI surface: `voiceserver pair` authenticates with the per-run admin
        // secret from runtime-state.json, which only the same OS user can read.
        post("/v1/admin/pairing-links") {
            if (call.request.headers["X-Admin-Secret"] != services.adminSecret) {
                call.respond(HttpStatusCode.Unauthorized, VoiceErrorResponse(error = "unauthorized"))
                return@post
            }
            val code = services.pairingCodes.mint()
            call.respond(
                PairingLinkResponse(code = code, url = VoicePairingLink.build(services.advertisedBaseUrl, code)),
            )
        }

        authenticate("voice-bearer") {
            webSocket(VoiceProtocol.LISTEN_PATH) {
                ListenSession(client = this, deepgram = services.deepgram, glossary = services.glossary).run()
            }

            post(VoiceProtocol.REFINE_PATH) {
                val request = call.receive<VoiceRefineRequest>()
                call.respond(services.refiner.refine(request))
            }
        }
    }
}
