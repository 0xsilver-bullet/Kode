package com.silverbullet.kode.core.network

import com.silverbullet.kode.core.model.AccessTokenResponse
import com.silverbullet.kode.core.model.ExecutionEnvironmentDescriptor
import com.silverbullet.kode.core.model.WebSocketTicketResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess

/**
 * The environment's HTTP surface, used to bootstrap a socket.
 *
 * The ladder is: bootstrap credential -> bearer access token -> single-use
 * WebSocket ticket. Long-lived tokens are deliberately never placed in the
 * socket URL; only the short-lived ticket is.
 *
 * See `docs/internals/environment-auth.md` and
 * `packages/client-runtime/src/authorization/remote.ts`.
 */
class EnvironmentAuthApi(
    private val httpClient: HttpClient,
) {

    /** `GET /.well-known/t3/environment` — identifies the server before pairing. */
    suspend fun fetchDescriptor(httpBaseUrl: String): ExecutionEnvironmentDescriptor {
        val response = httpClient.get(endpoint(httpBaseUrl, DESCRIPTOR_PATH))
        response.ensureSuccess("fetch the environment descriptor")
        return response.body()
    }

    /**
     * `POST /oauth/token` — RFC 8693 token exchange turning the one-time
     * pairing credential into a 30-day bearer session.
     *
     * The `client_*` fields are presentation hints for the server's authorized
     * clients list; they carry no authorization weight.
     */
    suspend fun exchangeBootstrapCredential(
        httpBaseUrl: String,
        credential: String,
        client: ClientPresentation = ClientPresentation.Default,
    ): AccessTokenResponse {
        val response = httpClient.submitForm(
            url = endpoint(httpBaseUrl, TOKEN_PATH),
            formParameters = Parameters.build {
                append("grant_type", GRANT_TYPE_TOKEN_EXCHANGE)
                append("subject_token", credential)
                append("subject_token_type", SUBJECT_TOKEN_TYPE_BOOTSTRAP)
                append("requested_token_type", REQUESTED_TOKEN_TYPE_ACCESS)
                append("scope", DEFAULT_SCOPES.joinToString(" "))
                append("client_label", client.label)
                append("client_device_type", client.deviceType)
                append("client_os", client.os)
            },
        )
        response.ensureSuccess("exchange the pairing credential")
        return response.body()
    }

    /**
     * `POST /api/auth/websocket-ticket` — a short-lived (5 minute) single-use
     * ticket carrying the session's scopes.
     */
    suspend fun issueWebSocketTicket(
        httpBaseUrl: String,
        accessToken: String,
    ): WebSocketTicketResponse {
        val response = httpClient.post(endpoint(httpBaseUrl, WEBSOCKET_TICKET_PATH)) {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
        }
        response.ensureSuccess("issue a WebSocket ticket")
        return response.body()
    }

    /**
     * Builds the authenticated `/ws` URL for one connection attempt.
     *
     * The ticket is the only credential that ever appears in a URL, and it is
     * single-use with a five-minute TTL.
     */
    fun socketUrl(wsBaseUrl: String, ticket: String): String =
        "${wsBaseUrl.trimEnd('/')}$WS_PATH?$WS_TICKET_PARAM=${ticket.encodeURLParameter()}"

    private fun endpoint(httpBaseUrl: String, path: String): String =
        PairingLinkResolver.normalizeBaseUrl(httpBaseUrl).trimEnd('/') + path

    private suspend fun HttpResponse.ensureSuccess(action: String) {
        if (status.isSuccess()) return
        throw EnvironmentAuthException(
            status = status,
            action = action,
            detail = runCatching { body<String>() }.getOrNull(),
        )
    }

    companion object {
        const val DESCRIPTOR_PATH = "/.well-known/t3/environment"
        const val TOKEN_PATH = "/oauth/token"
        const val WEBSOCKET_TICKET_PATH = "/api/auth/websocket-ticket"
        const val WS_PATH = "/ws"
        const val WS_TICKET_PARAM = "wsTicket"

        const val GRANT_TYPE_TOKEN_EXCHANGE = "urn:ietf:params:oauth:grant-type:token-exchange"
        const val SUBJECT_TOKEN_TYPE_BOOTSTRAP =
            "urn:t3:params:oauth:token-type:environment-bootstrap"
        const val REQUESTED_TOKEN_TYPE_ACCESS = "urn:ietf:params:oauth:token-type:access_token"

        /**
         * The scope set an ordinary pairing link grants. Requesting more than
         * the bootstrap credential holds is rejected by the server, so this is
         * exactly the client-operation set — no `access:*` or `relay:write`.
         */
        val DEFAULT_SCOPES = listOf(
            "orchestration:read",
            "orchestration:operate",
            "terminal:operate",
            "review:write",
            "relay:read",
        )
    }
}

/** Presentation hints shown in the environment's authorized-clients UI. */
data class ClientPresentation(
    val label: String,
    val deviceType: String,
    val os: String,
) {
    companion object {
        val Default = ClientPresentation(
            label = "Kode",
            deviceType = "mobile",
            os = "android",
        )
    }
}

class EnvironmentAuthException(
    val status: HttpStatusCode,
    val action: String,
    val detail: String?,
) : Exception("Could not $action (HTTP ${status.value}).${detail?.let { " $it" } ?: ""}")
