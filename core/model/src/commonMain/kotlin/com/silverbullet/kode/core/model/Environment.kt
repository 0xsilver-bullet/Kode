package com.silverbullet.kode.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

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
    /**
     * The configured provider instances and the models each offers. This is
     * where the model picker's options come from — there is no separate
     * catalogue RPC.
     */
    val providers: List<ServerProvider> = emptyList(),
)

/**
 * One configured provider instance, mirroring `ServerProvider` in
 * `packages/contracts/src/server.ts`.
 *
 * [instanceId] is the routing key — the only stable identity for provider
 * routing. [driver] is metadata, not a key.
 */
@Serializable
data class ServerProvider(
    val instanceId: String,
    val driver: String,
    val displayName: String? = null,
    val enabled: Boolean = false,
    val installed: Boolean = false,
    val status: String = ProviderState.UNKNOWN,
    /** Absent means available, which is what legacy servers imply. */
    val availability: String? = null,
    val unavailableReason: String? = null,
    /**
     * Some providers cannot switch models mid-conversation. Absent means they
     * can.
     */
    val requiresNewThreadForModelChange: Boolean = false,
    /**
     * Instances sharing a continuation group can pick up each other's
     * conversations; ones that do not cannot take over a started thread.
     */
    val continuation: ServerProviderContinuation? = null,
    /** Whether the plan/default toggle is meaningful for this provider. */
    val showInteractionModeToggle: Boolean = false,
    val models: List<ServerProviderModel> = emptyList(),
) {
    /** Port of `isProviderAvailable`: absent availability means available. */
    val isAvailable: Boolean get() = availability != ProviderAvailability.UNAVAILABLE

    /** Only enabled *and* available instances can be selected. */
    val isUsable: Boolean get() = enabled && isAvailable

    val label: String get() = displayName?.takeIf { it.isNotBlank() } ?: driver
}

@Serializable
data class ServerProviderModel(
    val slug: String,
    val name: String,
    val shortName: String? = null,
    val isCustom: Boolean = false,
    val isDefault: Boolean = false,
    val isLegacy: Boolean = false,
    /** Carries the per-model option descriptors: reasoning effort and friends. */
    val capabilities: ModelCapabilities? = null,
) {
    val label: String get() = shortName?.takeIf { it.isNotBlank() } ?: name
}

@Serializable
data class ModelCapabilities(
    val optionDescriptors: List<ProviderOptionDescriptor> = emptyList(),
)

/**
 * One tunable a model exposes — reasoning effort, thinking budget, fast mode.
 *
 * Deliberately **not** a sealed hierarchy keyed on `type`. The contract's union
 * is open-ended, and a sealed serializer would throw on a `type` a newer server
 * introduces — failing the whole `ServerConfig` decode and so the connection.
 * Unknown types simply render as nothing.
 */
@Serializable
data class ProviderOptionDescriptor(
    val id: String,
    val label: String,
    val type: String,
    val description: String? = null,
    /** Populated for `select`. */
    val options: List<ProviderOptionChoice> = emptyList(),
    val currentValue: JsonPrimitive? = null,
    /**
     * Values the provider injects via the prompt rather than offering as a
     * choice. T3 Code filters these out of the picker.
     */
    val promptInjectedValues: List<String> = emptyList(),
) {
    val isSelect: Boolean get() = type == SELECT
    val isBoolean: Boolean get() = type == BOOLEAN

    companion object {
        const val SELECT = "select"
        const val BOOLEAN = "boolean"
    }
}

@Serializable
data class ProviderOptionChoice(
    val id: String,
    val label: String,
    val description: String? = null,
    val isDefault: Boolean = false,
)

/** A chosen option value. `value` is a string for selects, a boolean otherwise. */
@Serializable
data class ProviderOptionSelection(
    val id: String,
    val value: JsonPrimitive,
)

@Serializable
data class ServerProviderContinuation(val groupKey: String)

object ProviderAvailability {
    const val AVAILABLE = "available"
    const val UNAVAILABLE = "unavailable"
}

/** `ServerProviderState`. */
object ProviderState {
    const val READY = "ready"
    const val WARNING = "warning"
    const val ERROR = "error"
    const val DISABLED = "disabled"
    const val UNKNOWN = "unknown"
}

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
