package com.silverbullet.kode.core.network

import com.silverbullet.kode.core.model.AssetCreateUrlInput
import com.silverbullet.kode.core.model.AssetCreateUrlResult
import com.silverbullet.kode.core.model.AssetResource
import com.silverbullet.kode.core.model.ClientOrchestrationCommand
import com.silverbullet.kode.core.model.DispatchResult
import com.silverbullet.kode.core.model.OrchestrationStreamDecoder
import com.silverbullet.kode.core.model.ServerConfig
import com.silverbullet.kode.core.model.ShellStreamItem
import com.silverbullet.kode.core.model.ThreadId
import com.silverbullet.kode.core.model.ThreadStreamItem
import com.silverbullet.kode.core.rpc.RpcConnection
import com.silverbullet.kode.core.rpc.RpcProtocolException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Typed facade over the untyped [RpcConnection], one function per RPC method.
 *
 * Method names mirror `WS_METHODS` in `packages/contracts/src/rpc.ts` and
 * `ORCHESTRATION_WS_METHODS` in `orchestration.ts`. Only the methods the app
 * actually calls are declared; the surface grows as features land.
 */
class T3EnvironmentClient(
    private val connection: RpcConnection,
    private val json: Json = ContractJson,
) {
    private val decoder = OrchestrationStreamDecoder(json)

    /**
     * `server.getConfig` — the first call on every session. Its success is what
     * proves the server is responsive, which is why the connection is not
     * reported as `connected` until it returns.
     */
    suspend fun getConfig(): ServerConfig = decode(
        connection.request(Methods.SERVER_GET_CONFIG),
        Methods.SERVER_GET_CONFIG,
    )

    /**
     * A cheap liveness check, used when the app returns to the foreground
     * instead of tearing down a healthy socket.
     *
     * `server.probe` exists only on servers that advertise the
     * `connectionProbe` capability. Calling it unconditionally would fail on
     * older servers and be read as a dead session, so this falls back to
     * `server.getConfig`, which every server has — the same choice
     * `RpcSessionFactory` makes.
     */
    suspend fun probe(config: ServerConfig) {
        if (config.environment.capabilities.connectionProbe) {
            connection.request(Methods.SERVER_PROBE)
        } else {
            connection.request(Methods.SERVER_GET_CONFIG)
        }
    }

    /**
     * `orchestration.subscribeShell` — projects and threads, as an initial
     * snapshot followed by live upsert/remove events.
     *
     * We deliberately omit `afterSequence`, so the server always sends a full
     * snapshot. That costs bandwidth on reconnect but bounds how far our
     * partial event handling can drift from the truth. See `ROADMAP.md`.
     */
    fun subscribeShell(): Flow<ShellStreamItem> =
        connection.stream(Methods.SUBSCRIBE_SHELL)
            .map(decoder::decodeShellItem)

    /**
     * `orchestration.subscribeThread` — one thread's detail snapshot followed by
     * its domain events.
     */
    fun subscribeThread(threadId: ThreadId): Flow<ThreadStreamItem> =
        connection.stream(
            Methods.SUBSCRIBE_THREAD,
            buildJsonObject { put("threadId", threadId.value) },
        ).map(decoder::decodeThreadItem)

    /**
     * `orchestration.dispatchCommand` — requests a state change.
     *
     * Acceptance is not application success: the command is turned into events
     * by the server's decider, and the results arrive over [subscribeThread].
     */
    suspend fun dispatchCommand(command: ClientOrchestrationCommand): DispatchResult = decode(
        connection.request(
            Methods.DISPATCH_COMMAND,
            json.encodeToJsonElement(ClientOrchestrationCommand.serializer(), command),
        ),
        Methods.DISPATCH_COMMAND,
    )

    /**
     * `assets.createUrl` — a short-lived, signed URL for one attachment.
     *
     * The returned [AssetCreateUrlResult.relativeUrl] is relative to the
     * environment's HTTP base and carries its own signature, so fetching it
     * needs no bearer header. It expires after an hour, which is why callers
     * cache the URL rather than treat it as stable.
     */
    suspend fun createAssetUrl(resource: AssetResource): AssetCreateUrlResult = decode(
        connection.request(
            Methods.ASSETS_CREATE_URL,
            json.encodeToJsonElement(
                AssetCreateUrlInput.serializer(),
                AssetCreateUrlInput(resource),
            ),
        ),
        Methods.ASSETS_CREATE_URL,
    )

    // ------------------------------------------------------------------ helpers

    private inline fun <reified T> decode(element: JsonElement?, method: String): T {
        val payload = element
            ?: throw RpcProtocolException("`$method` returned no payload.")
        return runCatching { json.decodeFromJsonElement(serializer<T>(), payload) }
            .getOrElse { throw RpcProtocolException("Could not decode the `$method` result.", it) }
    }

    object Methods {
        const val SERVER_GET_CONFIG = "server.getConfig"
        const val SERVER_PROBE = "server.probe"

        const val SUBSCRIBE_SHELL = "orchestration.subscribeShell"
        const val SUBSCRIBE_THREAD = "orchestration.subscribeThread"
        const val DISPATCH_COMMAND = "orchestration.dispatchCommand"

        const val ASSETS_CREATE_URL = "assets.createUrl"
    }

    companion object {
        val EmptyPayload: JsonElement = JsonObject(emptyMap())
    }
}
