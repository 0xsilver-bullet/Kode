package com.silverbullet.kode.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Mirrors `ExecutionEnvironmentDescriptor` in
 * `packages/contracts/src/environment.ts`, returned by
 * `GET /.well-known/t3/environment` and embedded in [ServerConfig].
 */
@Serializable
data class ExecutionEnvironmentDescriptor(
    val environmentId: EnvironmentId,
    val label: String,
    val platform: ExecutionEnvironmentPlatform,
    val serverVersion: String,
    val capabilities: ExecutionEnvironmentCapabilities = ExecutionEnvironmentCapabilities(),
)

@Serializable
data class ExecutionEnvironmentPlatform(
    val os: String,
    val arch: String,
) {
    val isDarwin: Boolean get() = os == OS_DARWIN
    val isLinux: Boolean get() = os == OS_LINUX
    val isWindows: Boolean get() = os == OS_WINDOWS

    companion object {
        const val OS_DARWIN = "darwin"
        const val OS_LINUX = "linux"
        const val OS_WINDOWS = "windows"
    }
}

/**
 * Capability flags are additive and every one of them is optional on the wire:
 * older servers simply omit the newer keys. Absent must therefore mean
 * "unsupported", never "decode failure" — clients must not probe a capability
 * the server did not advertise.
 */
@Serializable
data class ExecutionEnvironmentCapabilities(
    val repositoryIdentity: Boolean = false,
    val connectionProbe: Boolean = false,
    val pullRequests: Boolean = false,
    val threadSettlement: Boolean = false,
    val threadSnooze: Boolean = false,
    val threadPinning: Boolean = false,
    val threadPinReorder: Boolean = false,
    val threadTitleRegeneration: Boolean = false,
)

/**
 * Partial mirror of `ServerConfig` in `packages/contracts/src/server.ts`.
 *
 * The full struct also carries keybindings, providers, settings, and
 * observability. We decode only what the app currently renders and rely on
 * `ignoreUnknownKeys` for the rest, which matches T3 Code's own
 * forward-compatibility stance for clients running against newer servers.
 */
@Serializable
data class ServerConfig(
    val environment: ExecutionEnvironmentDescriptor,
    val cwd: String,
)

/** `POST /oauth/token` response — RFC 8693 token exchange. */
@Serializable
data class AccessTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String,
    @SerialName("expires_in") val expiresInSeconds: Long? = null,
    @SerialName("issued_token_type") val issuedTokenType: String? = null,
    val scope: String? = null,
)

/** `POST /api/auth/websocket-ticket` response. */
@Serializable
data class WebSocketTicketResponse(
    val ticket: String,
    val expiresAt: String? = null,
)
